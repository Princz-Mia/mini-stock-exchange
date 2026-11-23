# Runbook

What to do when something in the running stack needs attention. Commands are for the default Compose project, from the repository root. With another project or env file, add `-p NAME --env-file FILE` to `docker compose`, and `--project NAME --env-file FILE` to the scripts (`-Project`, `-EnvFile` in PowerShell).

Execution's database is the only authority. Kafka's topics, Market Data's projections and Flink's candles are all derived from it, and can be rebuilt from it. Most recoveries below rely on that.

## Seeing what's wrong

- **`docker compose ps`** shows each container's health. Execution is unhealthy while it starts, if its writer has halted, or if it has lost writer ownership. Market Data is unhealthy only if its database is unreachable: a feed that is behind is reported in the data it serves, not as unhealthy, so it can keep serving history.
- **Grafana** (observability profile, <http://localhost:13000>) shows commands and latency, the writer queue, the outbox backlog and its oldest event's age, and each Market Data feed's lag and state.
- **The operator screen's Health panel** shows the same at a glance, with no extra profile. The next section reads it line by line.
- **The terminal** shows "Market data: Stale" or "Connecting" when the public feed is behind or the stream is down, and the instrument list carries the feed's state.
- **Logs:** `docker compose logs --since 10m execution-service` (or `market-data-service`). Each section below names the lines to look for.

## Reading the Health panel

The Health panel sits under the market controls on the operator screen, once the market has opened. Execution's lines refresh every two seconds; the command figures among them cover the last minute. The Analytics line is checked every ten seconds. A line that needs attention is highlighted and says why in words.

- **Writer:** "Taking commands", or "Halted". Halted means trading has stopped until Execution restarts; see [Execution won't become healthy](#execution-wont-become-healthy).
- **Queue:** commands waiting for the writer, out of the queue's capacity. It says "More than half full" past half. A full queue turns commands away.
- **Commands per second:** the trading commands that finished in the last minute, committed or failed, averaged over that minute.
- **Within 150 ms:** the share of those commands that took 150 ms or less from admission to result. It says "Below 95%" under 95%. A dash means no command finished in the last minute.
- **Refused:** commands turned away before admission in the last minute, because the queue was full or new orders were paused. Rate-limited requests are not counted here. Any refusal says "Commands turned away".
- **New orders:** "Admitted", or "Paused" while the publication backlog is full; see [The outbox isn't draining](#the-outbox-isnt-draining).
- **Outbox:** how many events wait to be published to Kafka, and the age of the oldest. It says "Publishing is behind" once the oldest is more than five seconds old. "Not running here" means this Execution doesn't run the outbox relay, so it has nothing to count.
- **Market data:** the public feed's state, as the terminal shows it: Live, Catching up, Connecting, Stale, Paused or Offline, with how far behind it is when not live. It says "Not live", or "Not reachable" when Market Data doesn't answer.
- **Analytics:** how many trades the candles are behind, for the instrument furthest behind, read from each instrument's candles for the current minute. A few trades behind is normal while trading; past 100 it says "Falling behind". It reads "Up to date" when none is behind. It says "Not live" when the candles' feed isn't live, or "Not reachable" when no instrument's candles could be read; see [Flink restarted, or candles are behind](#flink-restarted-or-candles-are-behind).

If Execution doesn't answer at all, the panel shows "Trading health is not reachable: Execution may be down." If one refresh fails after a good reading, it keeps that reading and says "Could not refresh; showing the last reading."

## Back up and restore

```bash
./scripts/backup.sh
./scripts/restore.sh backups/20261109T220342Z
```

On Windows, use `.\scripts\backup.ps1` and `.\scripts\restore.ps1 backups\20261109T220342Z`.

**Backup** writes `backups/<UTC time>/` with:
- `execution_db.dump`: Execution's database, taken from one consistent snapshot, so trading can go on meanwhile;
- `manifest.txt`: the snapshot's time, the commit, the schema version, the last command and trade sequence, and how many events the outbox holds. Its figures are read in the same snapshot as the dump, so they describe exactly what the dump holds, however much trading went on while it was taken.

That one database is all a restore into the same stack needs; on another machine, or into a new stack, copy the bots' tokens too (see [Restoring on another machine, or into a new stack](#restoring-on-another-machine-or-into-a-new-stack)). It holds the bot runs too: their windows, their figures and the simulator manifest each run's worker reported, so a restore brings them all back. `backups/` is ignored by Git, and holds real data, so keep it private.

**Restore** puts Execution back exactly as it was at the backup and rebuilds everything derived from it. Anything newer would describe trades that, after the restore, never happened. It:
1. stops the simulator, Flink, Market Data and Execution;
2. restores `execution_db` into an emptied schema, in one transaction: all of it, or nothing;
3. marks every outbox event unpublished again;
4. empties Market Data's projections and checkpoints, and deletes Kafka's and Flink's volumes;
5. recreates the topics, and starts again whatever was running.

Execution then republishes its whole history in order. Market Data and the candles rebuild from it, which takes seconds for a demo-sized history. The restore asks before deleting anything unless given `--yes` (`-Yes`).

Before it trades again, Execution checks the whole ledger. On a large database that takes minutes: 14 on a ledger of 6.3 million entries. The restore's last step waits up to 30 minutes for every service to be healthy; `--wait-timeout` (`-WaitTimeout`) changes it, in seconds. If the wait runs out, the database is restored all the same and the restore says so: wait until `docker compose ps` shows every service healthy.

After a restore:
- **Trades and orders after the backup are gone.** So are sessions started since, so people sign in again, and trader logins added since, which the operator adds again.
- **A bot token revoked after the backup works again.** Revoke it again from the operator screen.
- **Market quality over a run's window comes back in full.** Market Data's spread minutes (`spread_minute`) are emptied with the rest of its projections and rebuilt from the market snapshots Execution republishes. A rebuild of Market Data from Kafka alone can't do that: it sees only the snapshots the compacted topic still holds (see [Rebuilding Market Data](#rebuilding-market-data)).
- **A backup from an older version** is restored as it was, tables added since included, and migrated forward when Execution starts. A backup from a newer version holds schema changes this code doesn't know, so restore it only with code at least as new as the backup's `commit`.

If a restore stops partway, run it again: it starts again only what was running when it began, so then start the rest with `docker compose --profile full up -d --wait`.

### Restoring on another machine, or into a new stack

The bots' tokens are not in the backup. Execution's database keeps only a hash of each; the tokens themselves are files in the stack's `bot-tokens` volume, which the simulator reads. A restore into the same stack keeps that volume, so its bots trade on. A restore into a new stack, or on another machine, brings the bots back without their tokens, and the simulator's log says `without a token file`: they can't trade until the volume is copied too.

Copy it beside the backup, from the stack the backup came from. The volume is named after the Compose project, `mini-stock-exchange_bot-tokens` unless the stack runs under another name, and the archive keeps the files' owner and permissions:

```bash
docker compose run --rm --no-deps -v mini-stock-exchange_bot-tokens:/tokens:ro -v "$PWD/backups/20261109T220342Z:/backup" --entrypoint tar postgres -czpf /backup/bot-tokens.tar.gz --numeric-owner -C /tokens .
```

Then, on the new stack, after the restore, replace the tokens in its volume with the archive's and restart the simulator. A new stack started with the demo seed holds its own bots' tokens, for accounts the restore removed, so the volume is emptied first:

```bash
docker compose run --rm --no-deps -v mini-stock-exchange_bot-tokens:/tokens -v "$PWD/backups/20261109T220342Z:/backup:ro" --entrypoint sh postgres -c 'find /tokens -mindepth 1 -delete && tar -xzpf /backup/bot-tokens.tar.gz --numeric-owner -C /tokens'
docker compose --profile full restart simulator
```

In PowerShell, write the folder as `"${PWD}\backups\20261109T220342Z:/backup"`. The archive holds the bots' credentials in plain text: keep it with the backup, as private as the backup itself.

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

**It stopped while the market was closing.** Nothing to do. A close cancels resting orders 5,000 to a transaction, and the market is closed from the first one. If Execution stops before the last, the next start cancels the rest before it takes commands, and logs `Finished the market's close at startup` with how many. A close that answered `504 COMMAND_OUTCOME_UNKNOWN` is still going on: send the same key again for its answer.

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
   docker compose exec postgres psql -U postgres -d market_data_db -c "TRUNCATE instrument_projection, market_snapshot, processed_event, projection_checkpoint, public_trade, instrument_session, session_minute, spread_minute, candle_1m"
   ```
3. `docker compose up -d --wait market-data-service`.

It reads every topic from the beginning, and reports each feed live once caught up. It refuses to look live before then.

One thing a rebuild can't bring back: each instrument's book minute by minute (`spread_minute`, behind market quality over a bot run's window). It comes from the market snapshots, and that topic is compacted, so a rebuild sees only the snapshots compaction has kept, at least each instrument's newest. The minutes before them stay empty. The same happens to snapshots compacted while the market feed was paused or Market Data was down, which under heavy bot load can take less than an hour: those minutes are missing, and market quality shows them held from the minute before, with nothing else to say so. A restore republishes every snapshot from Execution, so it rebuilds them all.

**A feed paused on a record it can't use.** Look for `Feed paused: Unusable record at …` in its log, which names the record's topic, partition and offset. The feed stops there instead of skipping it, and its earlier records stay committed. Fix the cause, a bad deployment or a schema mismatch, then restart Market Data, which reads that record again.

**A history gap.** `History gap on …` means Kafka deleted records (trades are kept seven days) before Market Data read them. Restoring from a backup rebuilds everything from Execution, history included.

## A bot run is stuck

The Bot runs panel on the operator screen shows the run in progress, and every run, a page at a time, under Recent runs. One simulator worker holds a run at a time and sends a heartbeat while it works. Each heartbeat keeps the run for that worker for another 15 seconds.

**Worker lost.** The run shows "Worker lost" when no heartbeat came in time while the run should still be running. Its bots place nothing meanwhile. Look at the simulator's log:

```bash
docker compose logs --since 10m simulator
```

`lease lost` means this worker no longer holds the run. `heartbeat not delivered` or `heartbeat answered …` means the worker can't reach Execution, or Execution refused it. Fix the cause, or restart the simulator:

```bash
docker compose restart simulator
```

A worker takes the run on its next heartbeat and continues from the totals already reported. A run that stays "Starting" has never had a worker: `no simulator token; bots stay idle` in the log means `MSE_SIMULATOR_TOKEN` is not set in `.env`.

**Ended shows "Not confirmed".** The run was stopped with no worker to confirm it: its worker went away first, or it never had one. A worker that comes along later settles a run its worker left: it cancels the run's remaining quotes and confirms, before it takes any new run. A running simulator does this within seconds; if the simulator is down, start it. A run that never had a worker has nothing to settle and stays "Not confirmed". Meanwhile the run's window has no end, so Recent runs marks it "Not finished" and it can't be compared. Starting the next run ends that window at the new run's start, and the old run becomes comparable.

**Figures never appear.** Execution works out each finished run's figures once, in a pass every five seconds, off the writer. Until then the comparison shows "Being worked out…". If that lasts, look in Execution's log:
- `Bot run figures could not be computed; trying again on the next pass`: the whole pass failed, for example because the database was unreachable;
- `The figures of bot run … could not be computed`: that one run failed, and the runs after it went on.

Each pass takes up to five finished runs that have no figures yet, oldest first, failed ones included, so once the cause is fixed they appear with nothing else to do. A run marked "Not finished" has no figures by design: its window hasn't ended.

**Runs recorded before windows were kept.** Runs from a version that didn't keep run windows show "No window kept" in Recent runs, and "Not comparable: recorded before run windows were kept" on the comparison. They have no window, so they never get figures and can't be compared. Start a new run to compare instead.

## Sign-in sessions after a PostgreSQL crash

Execution keeps sign-in sessions in its database, on connections of their own that commit without waiting for the disk. Only those connections do this; PostgreSQL's own settings stay durable. If PostgreSQL itself crashes, the session changes of its last fraction of a second may be lost:
- a trader who signed in just then has to sign in again;
- a sign-out made in that instant may be undone, so that session works again until it times out or is signed out again.

A session that a password change ended is still refused on its next request, because the password change itself is durable; an account stream it already had open runs on until the stream's own lifetime ends. Orders, trades, balances and every other write wait for the disk as usual, so none of them is lost. Restarting Execution, or a crash of Execution alone, loses no sessions.
