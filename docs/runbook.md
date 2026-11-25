# Runbook

What to do when something in the running stack needs attention. Commands are for the default Compose project, from the repository root. With another project or env file, add `-p NAME --env-file FILE` to `docker compose`, and `--project NAME --env-file FILE` to the scripts (`-Project`, `-EnvFile` in PowerShell).

Execution's database is the only authority. Kafka's topics, Market Data's projections and Flink's candles are all derived from it, and can be rebuilt from it. Most recoveries below rely on that.

## Seeing what's wrong

- **`docker compose ps`** shows each container's health. Execution is unhealthy while it starts, if its writer has halted, or if it has lost writer ownership. Market Data is unhealthy only if its database is unreachable: a feed that is behind is reported in the data it serves, not as unhealthy, so it can keep serving history.
- **Grafana** (observability profile, <http://localhost:13000>) shows commands and latency, the writer queue, the outbox backlog and its oldest event's age, and each Market Data feed's lag and state.
- **The operator screen's Health panel** shows the same at a glance, with no extra profile. The next section reads it line by line.
- **The terminal** shows "Market data: Stale" when the public feed is behind, and "Reconnecting…" when its live stream is down; the instrument list carries the feed's state. The browser reconnects a dropped stream by itself, but an answer that is not a stream, such as the proxy's 502 while Market Data restarts, ends it for good, so the page opens it again after 1, 2, 4, 8 and 16 seconds and then every 30 seconds until Market Data answers. A trader whose own account stream is down is told that live updates to their account were interrupted; it is opened again the same way. A failed refresh after a good read leaves the page as it was, with "Last updated hh:mm:ss UTC, retrying"; only a page that never read anything says the service is not reachable.
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
- `manifest.txt`: the snapshot's time, the commit, the schema version, how many simulations the database holds, the current simulation's figures, and how many events the outbox holds. Its figures are read in the same snapshot as the dump, so they describe exactly what the dump holds, however much trading went on while it was taken.

Command and trade sequences start again from 1 in every simulation, so the manifest gives them for one simulation and says which: the current one, which is the one that has not closed or, once every one has, the one created last. Its lines are named `current_simulation_*`:

| Line | What it is |
| --- | --- |
| `simulations` | How many simulations the database holds, closed ones included |
| `current_simulation` | The current simulation's ID; nothing after the `=` on a database with no market yet |
| `current_simulation_name` | Its name, with any character outside ASCII shown as `?`; nothing for one nobody named |
| `current_simulation_state` | `SETUP`, `OPEN`, `HALTED` or `CLOSED` |
| `current_simulation_last_command_sequence` | Its last committed command, as `GET /api/v1/market` reports it |
| `current_simulation_trades` | How many trades it has |
| `current_simulation_last_trade_sequence` | The highest trade sequence any of its instruments has reached |
| `outbox_events` | Every simulation's events: the whole outbox, which a restore republishes |

A manifest written before simulations could follow each other has `last_command_sequence`, `trades` and `last_trade_sequence` instead, taken over the whole database, which then held one simulation. Such a backup restores as before: a restore reads only `outbox_events` from the manifest.

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
- a committed change the book couldn't apply;
- a market close that committed and then couldn't cancel every resting order (see below).

Commands answer `503 TRADING_UNAVAILABLE` meanwhile, and nothing more is written. **Restart it:**

```bash
docker compose restart execution-service
```

A new process takes writer ownership, rebuilds every book from the database and reconciles before taking commands. Commands that were in flight can be looked up by key afterwards (see below).

**Its startup reconciliation refused.** Look for `ReconciliationException` in its log. It lists each mismatch: a balance that differs from its ledger postings, a reservation that differs from the open orders' holds, or a journal batch that doesn't sum to zero. Execution won't trade on that state, and nothing repairs it automatically, because guessing which side is right would be worse.

Find what changed the database outside Execution. If it can't be explained, restore the last backup taken before it.

**Another instance owns the market.** `refusing to start` means one Execution is already running against the database; only one may.

**It stopped while the market was closing.** Nothing to do. A close cancels resting orders 5,000 to a transaction, and the market is closed from the first one. If Execution stops before the last, the next start cancels the rest before it takes commands, and logs `Finished the market's close at startup` with how many. A start that finds none left to cancel logs no such line. Every start on a closed market also removes its bots' token files, in case the close stopped before it had.

**It started after a market's set time.** Nothing to do. A market opened for a set time whose time passed while Execution was down is closed while Execution starts, before it takes commands, and the log says `Closed the market at startup: its close time, …, passed while the exchange was not running`, with the close's sequence and what it cancelled. The close is the one the schedule would have made, under the operator who opened the market; a start that finds the market closed already closes nothing. The status then says `closedOnSchedule: true` with a `closedAt` later than its `closesAt`, and the screens name both times rather than say it closed at its set time. If the start fails with `The market's close time had passed, and it could not be closed at startup`, the cause under it is what to fix; the next start tries again.

**A close answered `504 COMMAND_OUTCOME_UNKNOWN`.** The request waited 45 seconds and the close had not answered. No lookup by key serves a close, so send the same key again. On the operator screen that is **Ask again**, under the note that the close is taking longer than the exchange waits. The key stays that close's for good: sent after a new simulation has started, it still returns the close of the market it closed and leaves the new one alone. The answers:
- **`200`:** the close is done, and this is its answer.
- **`504` again:** it is still going on.
- **`503 MARKET_CLOSE_UNFINISHED` or `503 TRADING_UNAVAILABLE`:** Execution stopped trading meanwhile. Restart it, as the next entry says.

A close that can't finish usually shows this way first. With the database away, each further attempt at a transaction waits up to 30 seconds for a connection, so the close gives up after about a minute, when the request has stopped waiting.

**A close answered `503 MARKET_CLOSE_UNFINISHED`,** at once or when its key was sent again after a `504`. Restart Execution. The market is closed: its runs have ended and its bot tokens are revoked, but orders may still rest with their holds. `restingOrders` in the answer is an upper bound: it is Execution's own count when it stopped, and up to one transaction of the close (5,000 orders) fewer may rest in the database. A transaction of the close failed three times in a row (the database was away, say), or its outcome could not be known, and Execution stopped trading. What shows it:
- `docker compose ps` has Execution unhealthy. Its health check answers only `DOWN`, without a reason.
- The Health panel's Writer line says "Halted".
- The log has an `ERROR` line with `halted the writer`, the close's sequence, the count and the cause. Execution writes it when it stops, whether or not a request was still waiting for the answer.

Every close sent before the restart gets this answer again; other commands get `503 TRADING_UNAVAILABLE`. Nothing needs sending again. The restart cancels the rest before it takes commands, as above, and the key that closed the market then returns the close's answer. The operator screen says the same in its Market panel and in the panel that shows the market as ended, with how many orders still rest at most, shows its Health panel's writer as halted, and offers no retry: **Ask for the close's answer** sends the same key once Execution is back. If the answer itself was lost, to a `504` before it or to the proxy, the screen still says the market is closed once the status does, and offers the same button. A page opened afresh has no key to ask with; it says that the exchange has stopped and that orders the close had not cancelled still rest. Traders see those orders listed as waiting for the close, with no cancel offered, and what they reserve still shown as reserved. If the restart fails with `The market's close could not be finished`, the cause in the log is what to fix first.

A close can also halt the writer in its very first transaction, when the outcome of that commit is unknown. Then nobody knows yet whether the market closed: the log line says `halted the writer in its own transaction`, and every command, a close too, answers `503 TRADING_UNAVAILABLE`. Restart Execution and send the close's key again. It returns the close's answer if the market had closed, and closes it if not.

## A market with a set time did not close

`GET /api/v1/market` still says `OPEN` or `HALTED` after its `closesAt`. Orders and bot run starts are refused with `MARKET_CLOSED` from that time on whether or not the close has run, so nothing trades meanwhile; cancels are still taken. The close normally follows within a second. What to look at, in Execution's log:
- **`closing it under key`, then `The market closed at its close time`:** it is closing, or has. A close of a full market takes up to about half a minute.
- **Neither, and `The market of simulation … closes at …` from the start or the opening:** the schedule is armed and waiting. It compares the close time with Execution's clock, so a clock that is behind closes it late. The status's `closesAt` and the `Date` header of its answer are both on that clock.
- **`this process does not own the writer`:** this Execution lost its hold on the market. Its health is `DOWN` with `writer ownership lost`; restart it.
- **`the close could not be submitted` or `failed; asking again`:** the writer was busy, or the close rolled back; it is asked for again every 10 seconds (`mse.market.close-check-interval`).
- **`halted the writer`:** the close stopped half way, as an operator's close can. Restart Execution, as under [Execution won't become healthy](#execution-wont-become-healthy): it closes the market, or finishes the close, before it takes commands.

Closing by hand is always safe: **Close the market** on the operator screen, or `POST /api/v1/admin/market/close` with a new key. Only one close applies. If the scheduled one comes first, the hand close is refused with `409 MARKET_TRANSITION_NOT_ALLOWED` and state `CLOSED`, and the operator screen says the market had already closed at its set time; if the hand close comes first, the exchange makes none.

## Starting again: a new simulation, or wiping the stack

Two ways lead to a market that can trade again, and they keep very different things.

**A new simulation** keeps everything. Close the market, then, signed in as the operator, send `POST /api/v1/admin/simulations` with a name, `{"name": "Thursday seminar"}`, and an `Idempotency-Key`, as for the other operator commands. On the operator screen it is the **Start a new simulation** form under the panel that says the market has ended.

The closed simulation stays in the database as it closed, and nothing can write into it again. The new one is empty and in setup. What the operator does next, on the operator screen or through the API, in this order:
1. add the instruments (the symbols the closed simulation used are free again);
2. fund each trader, with the **Fund a trader** form or `POST /api/v1/admin/accounts`: logins are kept, accounts are not, and until then a trader who signs in is told they have no trading account. Adding a login that exists is refused with `USERNAME_TAKEN`;
3. provision the bots. They are new accounts with new tokens; the closed simulation's tokens stay revoked and its token files are gone;
4. open the market, and start a bot run.

The compatibility gate rehearses these steps through the API, after closing a market with orders resting and a bot run trading.

The demo seed does none of this for a simulation an operator started: it logs one line saying so and stops. It sets up only a market with no name, which is how it makes its own on a fresh stack. Even there it does steps 1 to 3 and never step 4: it leaves the market in setup, says so in its last log line, and the operator opens it.

The start's answers:
- **`409 SIMULATION_NOT_CLOSED`**, with the state: a simulation that has not closed exists. In `SETUP` it is set up further instead; `OPEN` or `HALTED`, close it first. The refusal is that key's answer for good, so start again with a new key.
- **`409 SIMULATION_NAME_TAKEN`:** names differ, whatever their case. Choose another, with a new key.
- **`504 COMMAND_OUTCOME_UNKNOWN`:** send the same key again; on the operator screen, **Ask again**. No lookup by key serves a start; the same key returns its first answer, even after later starts.
- **`503 TRADING_UNAVAILABLE`** after a start: Execution could not learn whether the start committed, and stopped. Restart it; it loads whichever simulation is current, and the same key then returns the start's answer or starts the simulation.

**Keys from the simulation before.** Every command of the new simulation needs a new `Idempotency-Key`. What an old key does when it is sent again after the start:
- **Open, halt, resume and close:** it returns the answer it was given in the simulation it was sent to, and changes nothing in the new one. This is what makes it safe to ask for a close's answer late: the key that closed Tuesday's market never closes Wednesday's.
- **The start of a simulation:** its first answer, as above.
- **Every other operator command** (instruments, accounts, traders, bots, bot runs): it is a new command in the new simulation. A script that derives its keys from its steps therefore sets up each simulation afresh, and a request sent again by mistake adds its instrument, account or bots to the new simulation. Do not send these again once a new simulation has started.

**Wiping the stack** (`docker compose down -v`) deletes every simulation, every login but the configured ones, and all history. It is for a development stack that should be empty again, not for running the next session.

A backup taken with several simulations in the database holds them all, and a restore brings them all back; see the manifest under [Back up and restore](#back-up-and-restore).

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

**After the host slept, or the network dropped,** a connection can die with one of the relay's queries in flight. The relay and the admission check read on connections of their own (`mse-outbox` in `pg_stat_activity`), and a read there that gets no answer fails after `mse.outbox.socket-timeout`, 60 seconds by default. The relay then backs off and carries on by itself, so the backlog stops growing within about a minute. If it still grows while Kafka is healthy, restart Execution.

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

## A bot job stopped, failed or is not moving

A job creates bots in parts of up to 1,000, and `GET /api/v1/admin/bot-jobs/current` shows it to any operator: its state, how many bots it has made of each strategy, and `failure` when it failed. Whatever happens to it, the bots it made stay, each complete with its token and endowment, and nothing makes a bot twice.

**It stopped.** An operator stopped it, and no part committed after the stop. To make the rest, start a new job for what is missing.

**It failed.** A part failed three times in a row and was rolled back each time, and the job was recorded as failed; `failure` gives the reason, and Execution's log has `Part … of bot job … failed 3 times` with the cause. The usual causes:
- `Could not hand over the tokens of … bot accounts`: the token directory (`mse.bots.token-directory`, the `bot-tokens` volume in Compose) is full or not writable by Execution's user. Make room or fix its owner (`mse`, mode `700`).
- `An instrument of the endowment was deleted before every bot was made`: someone deleted an instrument the job's endowment names, before any bot of the job held it.

Fix the cause, then start a new job for the rest.

**Execution restarted during the job.** Nothing to do. The process that starts next takes the job up again once its books are reconciled, and logs `Taking up bot job … again: … of … bots made in … parts`; `resumed` in the job counts these. A part that was being made when the process stopped either committed, and the job counts it, or did not, and is made again under the same key. Token files of a part that never committed may be left in the token directory under their account's name; no bot has that account, so their tokens authenticate nothing.

**It is running but not moving.** No part has committed for 15 seconds (`mse.bots.job-stalled-after`), so the job shows `stalled: true` with `lastProgressAt`, the time of its last part, and no estimate; the Bots panel says it is interrupted and since when. Look in Execution's log for `The writer stopped while bot job … was being made`: the writer halted or Execution is stopping, and the job stays running in the database. Restart Execution, as for a halted writer above; it takes the job up again. While the writer is halted, stopping the job is refused too, since a stop is a command on the writer. Until the job is done or stopped the market does not open (`409 BOT_JOB_RUNNING`); stop the job to open sooner.

## A bot run is stuck

The Bot runs panel on the operator screen shows the run in progress, and every run, a page at a time, under Recent runs. One simulator worker holds a run at a time and sends a heartbeat while it works. Each heartbeat keeps the run for that worker for another 15 seconds.

**Worker lost.** The run shows "Worker lost" when no heartbeat came in time while the run should still be running, and under it "Heartbeats stopped" with what the worker last reported: how far the loading had got, or the trading counts. Its bots place nothing meanwhile. Look at the simulator's log:

```bash
docker compose logs --since 10m simulator
```

`lease lost` means this worker no longer holds the run. `heartbeat not delivered` or `heartbeat answered …` means the worker can't reach Execution, or Execution refused it. Fix the cause, or restart the simulator:

```bash
docker compose restart simulator
```

A worker takes the run on its next heartbeat and continues from the totals already reported. A run that stays "Starting" has never had a worker: `no simulator token; bots stay idle` in the log means `MSE_SIMULATOR_TOKEN` is not set in `.env`.

**Running, but nothing is placed.** Read the run from `GET /api/v1/admin/bot-runs`. While `progress.phase` is `LOADING`, the worker is still reading the bots' token files and building them: `botsLoaded` of `botsTotal` grows with each heartbeat, and trading starts at `TRADING`. Once it trades, the paused counts in `activity` say why steps placed nothing new. `pausedStaleData` growing means the worker's view of the market is more than two seconds old or Market Data's feed is not live: look for `market data unavailable` in the simulator's log and at Market Data's health. `pausedMarketNotOpen` means the market is halted or not open yet. `pausedOther` means none of the run's instruments is listed by Market Data yet, as early in a new simulation before its first instrument reaches Market Data. A worker of an older version reports none of this: `progress` stays null and the counts stay 0. The operator screen shows the same on the run in progress: Loading bots with its bar and about how long is left, then Trading, and once a trading run places nothing between two heartbeats while its steps pause, the reason that grew most, such as "Paused: market data is stale". A worker that reports no phase shows as Running, with its counts only. A run stopped while it loads shows Stopping with the loading bar still moving: the worker loads every bot before it settles, so for a large run the stop takes as long as the rest of the loading.

**Ended shows "Not confirmed".** The run was stopped with no worker to confirm it: its worker went away first, or it never had one. A worker that comes along later settles a run its worker left: it cancels the run's remaining quotes and confirms, before it takes any new run. A running simulator does this within seconds; if the simulator is down, start it. A run that never had a worker has nothing to settle and stays "Not confirmed". Meanwhile the run's window has no end, so Recent runs marks it "Not finished" and it can't be compared. Starting the next run ends that window at the new run's start, and the old run becomes comparable. Its **Ran for** in Recent runs does not move with that: without a confirmation it reads "About" a length, counted to the worker's last report and never past the stop or the run's end time, or "Unknown" when no worker ever took the run.

**"Running for more than a day with no end time".** A run started with **Until I stop it** has no end: it trades until someone stops it, and a worker takes it up again after every restart of the simulator or the stack. The run in progress shows how long it has been going under **Elapsed**, and carries this warning once that is more than 24 hours. Elapsed, and a timed run's **Time left**, are counted on the exchange's clock as of its last answer, so a wrong clock on the operator's machine does not change them. If nobody meant it to run on, select **Stop run**. A run started **For a set time** ends by itself, and the form starts there, at 60 minutes.

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
