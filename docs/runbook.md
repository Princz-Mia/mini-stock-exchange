# Runbook

What to do when something in the running stack needs attention. Commands are for the default Compose project, from the repository root. With another project or env file, add `-p NAME --env-file FILE` to `docker compose`, and `--project NAME --env-file FILE` to the scripts (`-Project`, `-EnvFile` in PowerShell).

Execution's database is the only authority. Kafka's topics, Market Data's projections and Flink's candles are all derived from it, and can be rebuilt from it. Most recoveries below rely on that.

## Seeing what's wrong

- **`docker compose ps`** shows each container's health. Execution is unhealthy while it starts, if its writer has halted, or if it has lost writer ownership. Market Data is unhealthy only if its database is unreachable: a feed that is behind is reported in the data it serves, not as unhealthy, so it can keep serving history.
- **Grafana** (observability profile, <http://localhost:13000>) shows commands and latency, the writer queue, the outbox backlog and its oldest event's age, and each Market Data feed's lag and state.
- **The terminal** shows "Market data: Stale" or "Connecting" when the public feed is behind or the stream is down, and the instrument list carries the feed's state.
- **Logs:** `docker compose logs --since 10m execution-service` (or `market-data-service`). Each section below names the lines to look for.

## Back up and restore

```bash
./scripts/backup.sh
./scripts/restore.sh backups/20261109T220342Z
```

On Windows, use `.\scripts\backup.ps1` and `.\scripts\restore.ps1 backups\20261109T220342Z`.

**Backup** writes `backups/<UTC time>/` with:
- `execution_db.dump`: Execution's database, taken from one consistent snapshot, so trading can go on meanwhile;
- `manifest.txt`: the time, commit, schema version, last command and trade sequence, and how many events the outbox holds.

That one database is all a restore needs. `backups/` is ignored by Git, and holds real data, so keep it private.

**Restore** puts Execution back exactly as it was at the backup and rebuilds everything derived from it. Anything newer would describe trades that, after the restore, never happened. It:
1. stops the simulator, Flink, Market Data and Execution;
2. restores `execution_db` in one transaction;
3. marks every outbox event unpublished again;
4. empties Market Data's projections and checkpoints, and deletes Kafka's and Flink's volumes;
5. recreates the topics, and starts again whatever was running.

Execution then republishes its whole history in order. Market Data and the candles rebuild from it, which takes seconds for a demo-sized history. The restore asks before deleting anything unless given `--yes` (`-Yes`).

After a restore:
- **Trades and orders after the backup are gone.** So are sessions started since, so people sign in again.
- **A bot token revoked after the backup works again.** Revoke it again from the operator screen.
- **A backup from an older version** is migrated forward when Execution starts. A backup from a newer version holds schema changes this code doesn't know, so restore it only with code at least as new as the backup's `commit`.

## Execution won't become healthy

**Its writer halted.** Look for `halted the writer` in its log. The writer stops when the database and the in-memory book might disagree:
- a commit whose outcome is unknown;
- another process claiming the market;
- a committed change the book couldn't apply.

Commands answer `503 TRADING_UNAVAILABLE` meanwhile, and nothing more is written. **Restart it:**

```bash
docker compose restart execution-service
```

A new process takes writer ownership, rebuilds every book from the database and reconciles before taking commands. Commands that were in flight can be looked up by key afterwards (see below).

**Its startup reconciliation refused.** Look for `ReconciliationException` in its log. It lists each mismatch: a balance that differs from its ledger postings, a reservation that differs from the open orders' holds, or a journal batch that doesn't sum to zero. Execution won't trade on that state, and nothing repairs it automatically, because guessing which side is right would be worse.

Find what changed the database outside Execution. If it can't be explained, restore the last backup taken before it.

**Another instance owns the market.** `refusing to start` means one Execution is already running against the database; only one may.

## A command's outcome is unknown

An order or cancel answered `504 COMMAND_OUTCOME_UNKNOWN` was admitted but not answered in time. It may still have committed. The terminal resolves this by itself. By hand, look the key up:

```
GET /api/v1/commands/{idempotency key}
```

- **`200` or `201`:** it committed, and this is its answer.
- **`404`:** it never committed. Send it again with the same key and body; it can't apply twice.

Don't send the same intent with a new key until the first is resolved: that could place it twice.

## The outbox isn't draining

Every trade is committed with its events in Execution's outbox, and the relay publishes them to Kafka in order. If Kafka is unreachable:
- the outbox grows, shown on Grafana as the backlog and its oldest event's age;
- Execution logs `Outbox relay paused for … ms` and retries.

Trading goes on, and the public market and candles fall behind, and say so.

**Fix Kafka:** `docker compose ps kafka`, then `docker compose up -d --wait kafka`. The relay resumes by itself and publishes the backlog in order. Market Data catches up and turns live again.

**If the backlog reaches its limit,** Execution stops admitting new orders (`New orders paused`), so the database can't fill up. Cancels are still accepted. New orders resume as soon as the backlog drains (`New orders resumed`).

## Flink restarted, or candles are behind

The JobManager resumes from its newest completed checkpoint, so candles continue where they stopped, with nothing counted twice. Until it catches up, the chart's candles say they are behind. Trading is never affected.

To check that the candles are exact, Market Data recomputes every stored candle from Execution's trade export:

```bash
docker compose exec market-data-service curl -s http://localhost:8080/internal/reconciliation/candles
```

`"reconciled":true` means every candle matches. Otherwise the answer lists the mismatched buckets.

If the checkpoints are lost or the job can't resume, delete the `flink-checkpoints` volume and start Flink again. The job then reads the topics from the beginning and recomputes every candle.

## Rebuilding Market Data

Market Data's database is derived from Kafka. To rebuild it, for example after a bad migration or to start clean:
1. `docker compose stop market-data-service`.
2. Empty its projections:
   ```bash
   docker compose exec postgres psql -U postgres -d market_data_db -c "TRUNCATE instrument_projection, market_snapshot, processed_event, projection_checkpoint, public_trade, instrument_session, session_minute, candle_1m"
   ```
3. `docker compose up -d --wait market-data-service`.

It reads every topic from the beginning, and reports each feed live once caught up. It refuses to look live before then.

**A feed paused on a record it can't use.** Look for `Feed paused: Unusable record at …` in its log, which names the record's topic, partition and offset. The feed stops there instead of skipping it, and its earlier records stay committed. Fix the cause, a bad deployment or a schema mismatch, then restart Market Data, which reads that record again.

**A history gap.** `History gap on …` means Kafka deleted records (trades are kept seven days) before Market Data read them. Restoring from a backup rebuilds everything from Execution, history included.
