# Benchmarks

Measured results, each with the environment it was measured in. They are results on one machine, not guarantees: rerun the command on yours before relying on a number.

**Release v0.2.0** (commit `2b082a3`, 2026-11-21 and 22), each in its section below:
- [Steady load](#release-v020-2026-11-21): 10,000 bots at 100 commands a second for ten minutes, every budget met, p99 9.7 ms from admission to committed result.
- [Open loop](#release-v020-2026-11-22): ten minutes at 400 and then at 1,000 commands a second, every budget met; p99 18.4 ms and 444.6 ms.
- [Highest one-minute rate](#group-commit-on-2026-11-19): 1,200 commands a second within every budget, measured on 2026-11-19 with the same writer.
- [Market Data catch-up](#market-data-catch-up): a backlog applied a poll at once, 6,500 to 8,000 snapshots and 15,000 to 18,000 trades a second, spread minutes included.

## Matcher

The order book alone, with no database (JMH, `MatcherBenchmark`). Each invocation replays the same recorded 10,000 commands from a fresh book. Each command does matching, both sides' 20-level snapshot depth, then apply or cancel. 2 forks, 20 measured iterations, Oracle JDK 25.0.5.

| Book | Per command |
| --- | --- |
| 200 resting orders | 427 ± 13 ns |
| 5,000 resting orders | 596 ± 22 ns |

**In runs of 32, 2026-11-18.** The same commands planned the way a batching writer will plan them: each against an overlay of its run's earlier commands, with the same snapshot depth, the run applied to the book at its end. Same checksum and final book as one by one.

| Book | One by one | In runs of 32 |
| --- | --- | --- |
| 200 resting orders | 443 ± 10 ns | 1,166 ± 25 ns |
| 5,000 resting orders | 592 ± 15 ns | 1,360 ± 16 ns |

The overlay adds about 0.7 to 0.8 µs a command, mostly combining the run's depth changes for each snapshot. A durable commit costs about 2 ms, so that is the price of sharing it.

## Market Data catch-up

How fast Market Data works through a backlog (`FeedCatchUpBench`): 6,000 market snapshots and 6,000 trades for five instruments, published to a three-partition topic in Testcontainers, then one feed timed from its start until it is live. The same seeded events are applied four times, alternately one record at a time and a whole poll at once, in one run on 2026-11-18:

| Events a second | One record at a time | A poll at once |
| --- | --- | --- |
| Snapshots | 449 and 460 | 5,865 and 15,443 |
| Trades | 410 and 411 | 16,645 and 40,994 |

One record at a time, each record is its own savepoint and its own statements in the poll's transaction. A poll at once is a few multi-row statements. Only the newest snapshot per instrument is written, since a snapshot replaces the ones before it; the others are still offered to the tables' checks in one more statement per table, so a snapshot the tables would refuse stops the poll exactly as it would one at a time. Trades are inserted together and each session's figures are summed once. The bench reaches PostgreSQL from the Windows host through Docker, where each round trip costs more than inside Compose. The first figure in each cell includes the JVM warming up.

**Release v0.2.0, 2026-11-22.** The same bench on the release candidate, where each poll also writes the spread minutes that market quality reads, in one more statement:

| Events a second | One record at a time | A poll at once |
| --- | --- | --- |
| Snapshots | 366 and 352 | 7,955 and 6,507 |
| Trades | 409 and 562 | 17,876 and 15,027 |

A poll at once is still more than ten times faster than one record at a time. Snapshots and trades at once fell within or near the range of the 2026-11-18 run; that run's 40,994 trades a second was its outlier, as two passes of the same events differed by more than double.

## Writer replay

The same seeded 12,000 commands (2,000 warm-up, 10,000 measured), one at a time, through the real engine and PostgreSQL in Testcontainers (`EngineReplayBench`). Every run below produced the same digest of answers, so they did identical work.

The change between the two columns: each instrument's newest 50 trades, published with every market snapshot, are now kept in memory by the writer instead of being re-read from the database for every command.

| Three runs each | Before | After |
| --- | --- | --- |
| Throughput | 124 to 126 commands a second | 131 to 133 commands a second |
| Mean | 7.96 to 8.09 ms | 7.52 to 7.61 ms |
| p50 | 6.55 to 6.66 ms | 6.04 to 6.11 ms |
| p95 | 17.6 to 18.4 ms | 16.5 to 17.0 ms |
| p99 | 23.3 ms | 22.0 to 22.4 ms |

About 6% more throughput and 5 to 8% lower latency. Most of a command's time is spent waiting on database round trips, and this removes one of them.

**These runs had fsync off.** Testcontainers starts PostgreSQL with `fsync=off` unless told otherwise, so the figures above leave out the cost of a durable commit. The bench now prints the settings, and `-Dmse.test.fsync=on` keeps fsync on, as Compose does. One run each, on 2026-11-16, same machine, identical work (the same answers digest):

| | fsync off | fsync on |
| --- | --- | --- |
| Throughput | 130 commands a second | 77 commands a second |
| Mean | 7.7 ms | 12.9 ms |
| p50 | 5.9 ms | 10.7 ms |
| p95 | 17.5 ms | 24.4 ms |
| p99 | 23.1 ms | 32.6 ms |

A durable commit costs about 5 ms a command here. The bench reaches PostgreSQL from the Windows host through Docker, so each round trip costs more than inside Compose, where the steady-load run's p50 is 5.1 ms with fsync on.

**One opening statement instead of three, 2026-11-17.** Each command used to open its transaction with three round trips: lock the market row (checking the writer epoch), look up the idempotency key, and take the next command sequence. One statement now does all three. Two runs each with fsync on, alternating, same digest:

| | Before | After |
| --- | --- | --- |
| Throughput | 105 to 106 commands a second | 114 commands a second |
| Mean | 9.4 to 9.5 ms | 8.8 ms |
| p50 | 7.6 to 7.7 ms | 7.0 ms |
| p95 | 20.1 to 20.5 ms | 19.3 to 19.5 ms |
| p99 | 24.9 to 25.5 ms | 24.3 to 24.5 ms |

About 0.7 ms less per command, 8% more throughput. The fsync-on figures are higher than the 77 a second above, measured a day earlier on the same machine, so compare runs from the same session only.

**The writer counts each account's resting orders itself, and reads cash and shares in one statement, 2026-11-17.** Before an order rests, the per-account open-order limit was checked with a count in the database; the books now keep that count. Two runs each with fsync on, alternating with the version above, same digest:

| | Before | After |
| --- | --- | --- |
| Throughput | 110 to 113 commands a second | 115 commands a second |
| Mean | 8.9 to 9.1 ms | 8.7 ms |
| p50 | 7.1 to 7.2 ms | 6.6 ms |

A small gain: one statement fewer for every order, two for one that rests. Cancels and the fills behind the p95 and p99 are unchanged.

**The writer keeps each instrument and numbers trades itself, 2026-11-17.** A market snapshot no longer reads the instrument's row, and trades are numbered from a counter the writer keeps, written once per command that traded instead of once per trade. Two runs each with fsync on, alternating with the version above, same digest:

| | Before | After |
| --- | --- | --- |
| Throughput | 118 commands a second | 123 commands a second |
| Mean | 8.5 ms | 8.1 ms |
| p95 | 18.9 to 19.2 ms | 17.8 to 18.0 ms |
| p99 | 24.3 to 24.5 ms | 22.6 to 22.8 ms |

The tail moved this time: the commands with trades, which make it, lost a statement for each trade. Against the first row of this section, 105 a second, the three changes together are about 17% more, each step measured against the one before in the same session.

**A command's writes in one statement per kind of row, 2026-11-17.** Each command now collects its writes (maker fills, trades, journal postings, balances, account versions, outbox events) and sends them just before its answer, one statement per kind of row, so the number of statements no longer grows with the number of fills: any number of fills costs 11. Two runs each with fsync on, alternating with the version above, same digest:

| | Before | After |
| --- | --- | --- |
| Throughput | 122 to 123 commands a second | 181 to 182 commands a second |
| Mean | 8.2 ms | 5.5 ms |
| p95 | 18.5 ms | 7.8 ms |
| p99 | 23.1 to 23.5 ms | 8.8 ms |

The largest step so far, and the tail most of all: commands with fills now cost the same statements as any other. From the first row of this section the bench has gone from 105 to 182 commands a second.

**A cancel of an order resting in the book, without reading it first, 2026-11-17.** One statement fewer for each successful cancel. On this bench, no measurable change (182 commands a second before and after, same digest): many of its cancels target orders already filled, which are answered as before, and the read saved is cheap here.

## Startup reconciliation

Before it takes commands, Execution checks every customer balance against the sum of its ledger postings, and every journal batch for balance per asset. Both checks read the whole ledger.

**A restored development database, 2026-11-22.** With 2.47 million journal batches and 6.3 million ledger entries (903 MB), Execution took about 14 minutes to become ready, nearly all of it in the batch check. PostgreSQL ran it as a nested loop over every batch, looking up each batch's entries through an index, because the check stops after 20 failures and it expected some soon. In a balanced ledger there are none, so it read every entry by a random lookup: about 96 GB read from disk for a 1 GB table. The two checks now run with index and bitmap scans turned off, so each reads the ledger in one pass. Their SQL and what they find are unchanged.

`LedgerReconciliationBench` generates a ledger of that size from a fixed seed: 10,000 accounts, 3.1 million batches, 6.3 million entries (`ledger_entry` 1,025 MB with its indexes). Each check is timed twice, alternating between the plan PostgreSQL chooses on its own and the one pass, in Testcontainers (128 MB of shared buffers) on 2026-11-22:

| Check | Planner's own choice | One pass |
| --- | --- | --- |
| Balances against the ledger | 14.9 s and 9.2 s | 1.1 s and 1.0 s |
| Unbalanced batches | 21.0 s and 21.0 s | 2.7 s and 2.5 s |

The whole reconciliation then took 3.7 s. Here the ledger fits in the operating system's cache, so the planner's own plan costs seconds, not minutes; on the restored database it had to read from disk. The one pass reads each table once, so its time grows with the ledger's size, not with how much of it is cached.

## Closing a full market

A close cancels every resting order and releases its hold, 5,000 orders to a transaction. `MarketCloseBench` times it at the most a market can hold: 200,000 resting orders, twenty for each of 10,000 accounts on five instruments. The market is built by SQL, each order with its hold and its ledger batch; Execution's own startup rebuilds the books from it and reconciles it, and the close then runs through the real engine against PostgreSQL in Testcontainers. On 2026-11-23, each row on a market of its own:

| Orders per transaction | Transactions | Whole close | Longest transaction | Median |
| --- | --- | --- | --- | --- |
| 1,000 | 200 | 38.4 s | 0.29 s | 0.19 s |
| 5,000 | 40 | 24.8 s and 30.1 s | 0.76 s and 0.89 s | 0.62 s and 0.74 s |
| 20,000 | 10 | 27.5 s | 3.01 s | 2.83 s |
| 5,000, with fsync on | 40 | 26.1 s and 30.6 s | 0.82 s and 0.94 s | 0.64 s and 0.76 s |

That is about 0.13 to 0.15 ms an order, nearly all of it in the statements: of the longest transaction at 5,000, the commit took 0.07 to 0.09 s. Applying all 200,000 cancels to the books took 0.05 s, startup on the full market (books rebuilt and reconciled) about 1 s, and reconciliation after the close 0.7 to 1.0 s. Durable commits change little, since a close commits 40 times.

So 5,000 it is: larger transactions make the whole close no faster and hold the writer for seconds at a time, and smaller ones make it slower. The second close in each pair was the slower one; it ran beside the first market's rows, and other work shared the machine during these runs, so the figures are a range, not a limit. The close's request waits 45 seconds for the answer (`mse.market.close-timeout`); a close that takes longer answers `504` and goes on.

## Creating bots

Each bot is an account, a genesis batch funding it (four ledger rows for cash and one instrument), its balance rows, a token hash, and a token file in the simulator's secret directory, synced before the commit that creates the bot. Measured on 2026-11-24, Windows 11 and Docker Desktop, PostgreSQL 18.6 in Testcontainers with fsync on; each command made 100 bots with cash and one holding.

| | Answer, per 100 bots | Writer's statements | Commit | Token files |
| --- | --- | --- | --- | --- |
| One statement after another, files synced one at a time on the writer | 857 ms | 841 ms (files included) | 4.9 ms | 365 ms |
| One statement for all the bots, files synced together before the command is queued | 173 ms | 56 ms | 4.9 ms | 94 ms |

- **The statements:** a dozen round trips for each bot, about 0.38 ms each through Docker Desktop's port, became one statement with the bots' arrays. Seven statements, one per table, still took 146 ms: each statement carrying large arrays cost about 45 ms more on this machine.
- **The token files:** each file still gets its own sync before it is renamed into place, and the directory is synced once the names are in place. Writing a batch's files first and then syncing them on 32 threads lets the syncs share the file system's journal commits. Per 100 and per 1,000 files:

| Token files | One at a time | 32 at once |
| --- | --- | --- |
| Windows NTFS, 100 / 1,000 | 336 ms / 3.37 s | 94 ms / 0.97 s |
| ext4 in Docker Desktop's VM, 100 / 1,000 | 348 ms / 3.49 s | 33 ms / 0.27 s |

The files are written on the thread that sends the command, not on the writer, so other commands no longer wait behind them. Database round trips here go through Docker Desktop's port forwarding, and "ext4" is the disk inside its VM, not a native Linux host.

## Steady load

**Scenario:** five instruments and 10,000 bots (2,000 market makers, 2,000 momentum, 6,000 noise) trading at 100 commands a second. Placements and cancels are mixed. There is a two-minute warm-up, then ten minutes are measured. Until 2026-11-15 the scenario was 1,000 bots; its last result is kept below. Neither is a limit: the exchange limits neither the bot population nor the bots in one run, and `--bots` sets the number.

**Command:**

```powershell
uv run --project simulator python -m mse_simulator.loadtest
```

It runs in its own throwaway Compose project and writes its report to `runtime/load-test/`. The README's "Load test" section describes what it does.

### Budgets

| | Budget |
| --- | --- |
| Latency from admission to committed result | p95 within 150 ms, p99 within 500 ms |
| Writer queue | no sustained growth |
| Unexpected infrastructure failures | under 0.1% of commands |
| Accounting | no violations, no lost acknowledged trades, no duplicate financial effects |
| Publication and the feeds | the outbox's unpublished events and every Market Data feed's lag grow by no more than two seconds of the rate (at least 200) over the window, and every feed stays live |

### Release v0.2.0, 2026-11-21

The release candidate, with every change since v0.1.0: runs of up to 32 commands in one transaction, the session store on its own connections, bot runs that keep their windows and figures, and Market Data's spread minutes. All budgets met.

| | Result |
| --- | --- |
| Offered | 56,931 commands in 600 s, 94.8 a second (bots plus the probe trader) |
| Admitted and committed | 56,913, 94.8 a second |
| Within 150 ms and within 500 ms | 100.000% of commands |
| Estimated p50 / p95 / p99 | 3.9 / 6.8 / 9.7 ms |
| Client p50 / p95 / p99, through the proxy | 7.9 / 11.7 / 14.3 ms (1,200 probe commands) |
| Writer queue depth | first minute 0.1, last minute 0.0, peak 1 |
| Unexpected failures, business rejections, refusals | 0, 0, 0 |
| Outbox, unpublished events at peak | 9 |
| Market Data feed lag at peak | 15 records (candles), 4 (market), 7 (trades) |
| Public feed not live | 0 of 120 samples |

- **The writer:** 3.97 ms of service time per command at a mean run of 1.01 commands, so at this rate commands rarely shared a commit. WAL flushes took 1.61 ms on average, the disk's fast state.
- **Starting and stopping:** provisioning the 10,000 bots took 79 s, the run's first commands came 8 s after it started, and stopping it, every bot's open orders cancelled, took 28 s. The simulator used 81 MiB of its 512 MiB.
- **Processor, mean (peak) of one CPU:** Execution 29% (47%), PostgreSQL 38% (72%), the simulator 26% (48%), Kafka 20% (158%), Market Data 8% (27%).
- **Accounting:** the restart's reconciliation (6 s), the public trades and the candles all checked out.
- **The simulator** reported version `0.2.0+2b082a3d6ab9` with the run.

**Environment:** Windows 11, Docker Desktop (WSL 2) with 16 CPUs and 15.2 GiB; Execution limited to 2 CPUs and 2 GiB; PostgreSQL 18.6 with `fsync` and `synchronous_commit` on, the session store's own connections committing asynchronously; Oracle JDK 25.0.5. Commit `2b082a3`.

### Where a command's time goes, 2026-11-16

The load test now reports the writer's timers and PostgreSQL's own statistics (through a load-test-only Compose overlay that enables `pg_stat_statements` and I/O timing; durability unchanged). The same 10,000-bot scenario at 100 commands a second met every budget again (99.969% within 150 ms, 100% within 500 ms, no failures). Means over the window:

| Per command | Queue | Statements | Commit | Begin, apply |
| --- | --- | --- | --- | --- |
| Order | 2.8 ms | 3.6 ms | 2.1 ms | under 0.01 ms |
| Cancel | 4.5 ms | 2.1 ms | 2.0 ms | under 0.01 ms |

- One writer at that service time (5.3 ms) sustains about 188 commands a second.
- Each WAL flush took 1.6 ms. `pg_test_fsync` on the same volume: `fdatasync` 1.59 ms, about 630 a second. One durable commit per command therefore caps the writer below that, however few the statements.
- PostgreSQL's own time per statement is a few hundredths of a millisecond, so most of the statement time is round trips.
- The restart, its reconciliation included, took 6 s; Execution averaged 35% of a CPU, PostgreSQL 49%, the simulator 35%, Kafka 21% (peak 140%).

### 2026-11-15, 10,000 bots

All budgets met.

| | Result |
| --- | --- |
| Offered | 57,657 commands in 600 s, 96.0 a second (bots plus the probe trader) |
| Admitted and committed | 58,086, 96.7 a second |
| Within 150 ms | 100.000% of commands |
| Within 500 ms | 100.000% |
| Estimated p50 / p95 / p99 | 5.1 / 12.5 / 55.6 ms |
| Client p50 / p95 / p99, through the proxy | 10.9 / 18.5 / 31.0 ms (1,200 probe commands) |
| Writer queue depth | first minute 0.2, last minute 0.0, peak 23 |
| Unexpected failures | 0 |
| Business rejections (committed refusals such as insufficient cash) | 0 |
| Refused before admission (busy, publication backlog, rate limit) | 0, 0, 0 |
| Outbox, unpublished events at peak | 13 |
| Public feed not live | 0 of 120 samples |

**Starting and stopping,** reported rather than budgeted, since they grow with the bots:

| | Result |
| --- | --- |
| Provisioning 10,000 bots in batches of 100 | 93 s |
| Starting the run to its first commands counted (the lease taken, the bots loaded), to within a heartbeat (5 s) | 6 s |
| Asking the run to stop to stopped (every bot's open orders cancelled) | 28 s |
| Simulator memory at the end of the window | 83 MiB of 512 MiB |

Admitted is a little above offered because the bots' counts come from heartbeats, so each end of the window may lag by one. The accounting checks after the run passed as below, and the environment is the same as for 2026-11-08, at commit `fb532d1` with the load test's own reporting of the figures above.

### 2026-11-08, 1,000 bots

All budgets met.

| | Result |
| --- | --- |
| Offered | 61,337 commands in 600 s, 102.2 a second (bots plus the probe trader) |
| Admitted and committed | 61,241, 102.0 a second |
| Within 150 ms | 99.749% of commands |
| Within 500 ms | 100.000% |
| Estimated p50 / p95 / p99 | 5.8 / 59.9 / 118.9 ms |
| Client p50 / p95 / p99, through the proxy | 11.4 / 28.0 / 94.3 ms (1,200 probe commands) |
| Writer queue depth | first minute 0.5, last minute 0.8, peak 11 |
| Unexpected failures | 0 |
| Business rejections (committed refusals such as insufficient cash) | 1 |
| Refused before admission (busy, publication backlog, rate limit) | 0, 0, 0 |
| Outbox, unpublished events at peak | 13 |
| Public feed not live | 0 of 120 samples |

**Accounting after the run:**
- Execution restarted, and its startup reconciliation passed. It rebuilds the order books, checks reservations and the ledger, and refuses to start on any mismatch.
- Every trade reached Market Data, with the same count, shares, notional and last sequence per instrument.
- The candles caught up and reconciled exactly with the trade export.

**How to read the latency figures:**
- The two budget shares are exact: 150 ms and 500 ms are bucket edges of Execution's histogram.
- The p50, p95 and p99 are interpolated inside their buckets, so they are estimates.
- Client latency is the probe trader's own timing, from sending a command to its answer, through the reverse proxy.

**Environment:**

| | |
| --- | --- |
| Host | Windows 11, Docker Desktop 29.8.2 on WSL2 (kernel 6.18), 16 CPUs and 15.2 GiB visible to Docker |
| Load generator | the simulator container, on the same host |
| Commit | `64f3dbb` |
| Execution JVM | Oracle JDK 25.0.5, `-XX:MaxRAMPercentage=75` |
| PostgreSQL | 18.6, `fsync=on`, `synchronous_commit=on` |
| Container limits | Execution 2 GiB, 2 CPUs; PostgreSQL 2 GiB, 2 CPUs; Kafka 2 GiB, 2 CPUs; Market Data 1.5 GiB, 1.5 CPUs; Flink JobManager 1.5 GiB, 1 CPU; Flink TaskManager 1.8 GiB, 2 CPUs; simulator 512 MiB, 1 CPU; proxy 256 MiB, 0.5 CPU |

## Highest rate

The rate at which the exchange still meets the budgets above, found two ways on 2026-11-17, same environment as the 2026-11-08 run (container limits unchanged), with durable PostgreSQL. Both modes are described in the README's "Load test" section.

### Ramp, 10,000 bots

```powershell
uv run --project simulator python -m mse_simulator.loadtest --ramp
```

A new bot run at each rate, 30 s of warm-up (2 minutes for the first), 2 minutes measured. **Highest rate that met every budget: 150 commands a second.**

| Rate | Admitted | Within 150 ms | Within 500 ms | p99 (estimate) | Queue peak | Refused for load | Writer service time | Met |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100/s | 96.8/s | 100.00% | 100.00% | 24.1 ms | 1 | 0 | 5.7 ms | yes |
| 125/s | 116.9/s | 100.00% | 100.00% | 26.9 ms | 1 | 0 | 5.4 ms | yes |
| 150/s | 143.4/s | 99.81% | 100.00% | 141.6 ms | 5 | 0 | 5.6 ms | yes |
| 175/s | 137.7/s | 87.58% | 100.00% | 218.2 ms | 31 | 0 | 5.7 ms | no: p95, and under 90% of the rate admitted |

Processor use, mean / peak in percent of one CPU:

| Rate | Execution | PostgreSQL | Simulator | Kafka | Market Data |
| --- | --- | --- | --- | --- | --- |
| 100/s | 34 / 39 | 50 / 55 | 26 / 44 | 10 / 43 | 11 / 15 |
| 125/s | 41 / 50 | 60 / 87 | 30 / 36 | 18 / 115 | 12 / 18 |
| 150/s | 48 / 58 | 71 / 93 | 43 / 97 | 23 / 161 | 15 / 24 |
| 175/s | 54 / 67 | 74 / 96 | 93 / 98 | 27 / 121 | 14 / 18 |

- One writer handles a command in about 5.6 ms, so it tops out near 178 a second, and queueing pushes the p95 past 150 ms before that.
- The bots offer about 95% of the asked rate. At 175 a second the simulator also reached its 1-CPU limit (about 3 ms of processor per bot command at the lower rates), so that step is limited by the generator as well as the writer.

### Open-loop stress, no bots

```powershell
uv run --project simulator python -m mse_simulator.loadtest --stress
```

Trader logins send at fixed times from the host through the proxy, 10 s of warm-up (2 minutes for the first rate), 60 s measured, latency from each command's planned time. **Highest rate that met every budget: 150 commands a second.**

| Rate | Answered | Refused or timed out | Failed | p50 | p95 | p99 | Generator lag p99 | Met |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100/s | 100.0/s | 0 | 0 | 12.5 ms | 24.4 ms | 28.8 ms | 15.0 ms | yes |
| 125/s | 125.0/s | 0 | 0 | 12.4 ms | 16.7 ms | 21.9 ms | 8.1 ms | yes |
| 150/s | 150.0/s | 0 | 0 | 12.6 ms | 17.9 ms | 23.9 ms | 8.3 ms | yes |
| 175/s | 175.0/s | 0 | 0 | 13.2 ms | 171.1 ms | 362.3 ms | 8.1 ms | no: p95 |

Without the bots' generator in the way, the limit is the same: 175 a second is just under the writer's capacity, so commands queue and the p95 passes 150 ms. These latencies include the proxy and Docker Desktop's port forwarding.

The accounting checks passed after both runs.

### Open-loop stress after the writer changes, and PostgreSQL's settings, 2026-11-18

The same stress run after the writer changes recorded under "Writer replay" above (one statement to open a command, writer-owned counts and instrument data, a command's writes sent once per kind of row, a cancel from the book). Twice: first without, then with PostgreSQL's settings in `compose.yaml` (512 MB of shared buffers, 32 MB of WAL buffers, checkpoints every 15 minutes or 4 GB of WAL, WAL compressed with lz4; durability unchanged). Both runs sent the same 102,337 commands. **Highest rate that met every budget: 250 commands a second**, against 150 before the writer changes.

| Rate | p95 without the settings | p99 without | p95 with | p99 with |
| --- | --- | --- | --- | --- |
| 200/s | 18.6 ms | 34.4 ms | 18.1 ms | 31.4 ms |
| 250/s | 119.9 ms | 181.5 ms | 28.6 ms | 63.6 ms |
| 300/s | 17.4 s | 23.5 s | 14.5 s | 20.8 s |

- At 300 a second the writer is saturated either way.
- With the settings, 76% fewer full-page images were written to the WAL (3,116 against 12,728) and 10% less WAL in all (5.6 KB a command against 6.2 KB), which is where the tail at 250 a second went.
- Below 200 a second there is no measurable difference.

### Open-loop stress with publication and the feeds judged, 2026-11-18

The stress run now also requires the publication backlog and every Market Data feed's lag not to grow over the measured window, and every feed to stay live. It ran on `main` with the PostgreSQL settings and Market Data's batched polls, but without the writer's write sets and cancel from the book, and with the load test's raised admission limit (500,000 events or 1 GiB, as the report read back from Execution). **Highest rate that met every budget: 250 commands a second.**

| Rate | p50 | p95 | p99 | Backlog peak | Feed lag peak | Met |
| --- | --- | --- | --- | --- | --- | --- |
| 100/s | 12.2 ms | 24.7 ms | 28.2 ms | 0 | 5 | yes |
| 200/s | 11.9 ms | 18.7 ms | 25.1 ms | 0 | 9 | yes |
| 250/s | 46.2 ms | 147.4 ms | 408.0 ms | 0 | 9 | yes |
| 300/s | 6.5 s | 22.5 s | 30.2 s | 0 | 8 | no: p95, p99, generator |

- Publication and the feeds kept up at every rate, 300 a second included: the relay never had a backlog worth sampling, and Market Data was never more than nine records behind. The writer is still the limit.

### What binds near the highest rate, 2026-11-18

The stress run now also reports, per rate, the writer's phases from Execution's timers, each container's processor, and how often each was held back at its CPU limit. Three runs at 240 to 270 a second and one 10-minute window at 250, on `main` with every open writer change merged:

| Run | Highest passing | p99 at 250 | Writer service time | One writer sustains |
| --- | --- | --- | --- | --- |
| 1 | 260/s | 53.6 ms | 3.72 to 3.84 ms | 261 to 269/s |
| 2 | 250/s | 183.0 ms | 3.71 to 3.84 ms | 261 to 269/s |
| 3 | 260/s | 53.3 ms | 3.66 to 3.81 ms | 262 to 273/s |
| 10 minutes at 250 | none | 157 s | 4.26 ms | 235/s |

- The writer binds: about 1.45 ms of statements and 2.2 to 2.4 ms of commit per command, most of the commit being the WAL flush.
- CPU does not: Execution and PostgreSQL were never held back at their limits and used at most 60% and 105% of one CPU of the two each may use.
- Over 10 minutes the commit rose to 2.8 ms and the queue grew without end at 250 a second, so the sustainable rate on this host is nearer 235.

### Group commit on, 2026-11-19

Execution now commits the orders and cancels waiting in its queue together, as one transaction of up to 32 (`MSE_TRADING_MAX_BATCH` in `compose.yaml`). Each keeps its own answer, sequence and rows. The same open-loop stress run as above, with durable commits, in the same environment except for these changes:

- **The proxy keeps its connections to the services open.** It used to open one per request. Each closed connection then waits a minute in TIME_WAIT, so the proxy ran out of its 28,232 local ports near 470 requests a second: at 500 a second, 2,034 commands in a minute were answered 502.
- **The proxy's CPU limit is 2 instead of 0.5.** At 0.5 it saturated near 300 commands a second, before the writer.
- **The stress generator runs one process per 400 commands a second.** One Python process falls behind its schedule somewhere between 800 and 1,000 a second. Past that, its own lag failed the rate, not the exchange.

**Highest rate that met every budget: 1,200 commands a second**, against 250 on 2026-11-18.

| Rate | Generator processes | p50 | p95 | p99 | Generator lag p99 | Met |
| --- | --- | --- | --- | --- | --- | --- |
| 400/s | 1 | 13.5 ms | 19.0 ms | 26.4 ms | 5.9 ms | yes |
| 600/s | 2 | 15.3 ms | 24.1 ms | 33.8 ms | 9.9 ms | yes |
| 800/s | 2 | 17.0 ms | 28.0 ms | 47.8 ms | 10.1 ms | yes |
| 1,000/s | 3 | 19.3 ms | 32.2 ms | 53.5 ms | 11.2 ms | yes |
| 1,200/s | 3 | 25.0 ms | 70.1 ms | 109.4 ms | 11.6 ms | yes |
| 1,500/s | 4 | 8.4 s | 31.8 s | 43.1 s | 870 ms | no: 1,019 refused by the account limit, latency |

Up to 1,200 a second, every planned command was answered: none refused, none failed. The accounting checks passed afterwards, and restarting Execution with its reconciliation took 7 s.

**How the writer behaves.** Means over each measured minute, from Execution's timers and `docker stats`; CPU is in percent of one CPU, and each limit is 2 CPUs:

| Rate | Mean run | Statements per run | Commit per run | Writer time per command | Execution CPU | PostgreSQL CPU | Proxy CPU |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 400/s | 1.7 | 1.75 ms | 2.35 ms | 2.40 ms | 60% | 79% | 9% |
| 600/s | 2.7 | 1.85 ms | 2.47 ms | 1.61 ms | 87% | 112% | 13% |
| 800/s | 4.0 | 2.08 ms | 2.74 ms | 1.20 ms | 114% | 116% | 17% |
| 1,000/s | 5.5 | 2.33 ms | 2.90 ms | 0.95 ms | 149% | 146% | 25% |
| 1,200/s | 8.4 | 3.23 ms | 3.12 ms | 0.75 ms | 186% | 168% | 28% |
| 1,500/s | 19.4 | 10.62 ms | 5.08 ms | 0.81 ms | 203% | 180% | 34% |

- **The writer is busy almost all the time at every rate,** 92 to 98%. It is always in a transaction, and its runs grow with the load.
- **A transaction costs 4 to 6 ms up to runs of about 8.** So the writer's time per command falls as runs grow: from 2.4 ms at 400 a second to 0.75 ms at 1,200. At one command per transaction, the writer topped out near 250 a second.
- **At 1,500 a second, Execution is at its 2-CPU limit, and PostgreSQL is close to its own.** The writer still empties its queue (14 ms of queue wait on average), but the HTTP threads share those CPUs. Requests wait before they reach the queue and then arrive in bursts, which the per-account rate limit refuses. More throughput needs higher CPU limits for Execution and PostgreSQL, not a writer change.
- **A cold start at a high rate is slow for about a minute.** A stack started straight at 700 a second took 35 to 90 ms per transaction while the JVM compiled. Then the commands that piled up in the generator drained slowly: each trader login sends 7.9 a second against its account's limit of 10, so it can catch up by only 2 a second. The runs above start at 400 a second.

**Ten minutes at a rate.** Each rate below was measured for 10 minutes after a 2-minute warm-up, following 10 minutes at 400 a second so that it started warm. **Highest rate that met every budget for ten minutes: 800 commands a second.**

| Rate | Runs | Answered | Refused or timed out | p50 | p95 | p99 | Generator lag p99 | Met |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 400/s | 7 | 400.0/s | 0 | 13.4 to 23.7 ms | 18.0 to 36.5 ms | 22.1 to 61.0 ms | 5.3 to 8.6 ms | yes, every run |
| 800/s | 1 | 800.0/s | 0 | 54.4 ms | 103.8 ms | 164.9 ms | 12.6 ms | yes |
| 1,000/s | 5 | 997.2 to 1,000.0/s | 0 to 1,667 | 135 to 687 ms | 706 ms to 12.1 s | 1.05 to 26.0 s | 10.3 to 560 ms | no: p95 and p99 |
| 1,200/s | 1 | 1,166.9/s | 19,888 | 54 s | 415 s | 523 s | 3.0 s | no |

- **Execution's database pool limits ten minutes at 1,000 a second.** The third window, on 2026-11-20, sampled Execution's connection pools every 5 seconds. The shared pool has 10 connections, and one of them always holds the writer's ownership lock. It stayed full the whole time: up to 190 requests waited for a connection, about 33 ms per borrow at first and 12 ms by the end. Each trading request borrows a connection four times: to load its session, to check its password version, to look up its account, and to save its session. Saving the session is an `UPDATE` whose commit waits for the WAL flush, so most likely it holds its connection longest; the pool's metrics can't split the time by statement. The writer has a separate pool and kept up: its admission-to-commit p99 stayed between 45 and 90 ms each minute.
- **The missed percentiles come from backlogs that clear slowly.** With the pool full, Execution handles only slightly more than 1,000 a second, so after a short stall the requests queue for Tomcat's 200 request threads. That happened about 10 s into the rate, leaving about 3.5 s of backlog that took 5 minutes to clear. It happened again when a checkpoint finished, adding about 1.4 s. Once the backlog had cleared, p95 and p99 were about 165 and 200 ms.
- **Durable commits become twice as slow, and the slowdown is in the disk below PostgreSQL.** Every failed ten-minute window ran with the writer's commit at about 6.5 ms per transaction at 1,000 a second, against 2.9 ms when commits are fast. Where the samples caught the change, it took about 10 seconds, and commits stayed slow until the load stopped. A fourth run, on 2026-11-20, timed PostgreSQL's WAL syncs. A separate container timed synced 8 kB writes on the same Docker disk. Both slowed at the same moment: WAL syncs went from 1.75 to about 3.9 ms, and the probe's writes from 2.0 to about 4.3 ms. Windows' own disk write time rose about fourfold with them. Within 5 seconds of the load stopping, the probe was back at its idle 0.7 ms. PostgreSQL flushes its WAL back to back in both states, about 520 times a second when fast and 250 when slow, so the time per flush sets how many commits it can make durable.
- **What starts the slowdown is not known.** In three runs it came 10.6 to 14.5 minutes into the load, with no common amount of WAL or commands written before it. A fifth run made 4 GB of WAL files before the load, so PostgreSQL reused them and created none during the run, and it still slowed. In one run the slowdown began as the first checkpoint started writing. In the other two, nothing in PostgreSQL's counters or the VM's memory changed first: no checkpoint, no full-page images, a steady WAL rate. A 10-second pause between rates didn't end it. The one-minute ramp above ran while commits were fast. Windows' disk counters leave out flushes, so they can't tell whether the extra time is spent in Docker Desktop's virtual disk or in the SSD itself.
- **At 1,200 a second the generator broke down as well.** Once answers slowed, its three processes reached their CPU limit and fell behind (lag 3 s, 13,298 requests timed out). That window measures the generator as much as the exchange.
- Every run's accounting checks passed, even the failed ones. Restarting Execution with its reconciliation took 7 to 17 s.

**Where Execution's CPU goes (JFR).** Execution was recorded for 50 s inside measured windows, with JFR's profiling settings. Its CPU, in percent of one CPU, and the share of its CPU samples by thread:

| | Run limit 1, 200/s | Run limit 32, 200/s | Run limit 32, 1,000/s |
| --- | --- | --- | --- |
| Execution CPU | 43% | 39% | 164% |
| HTTP request threads | 63% | 69% | 86% |
| The writer | 32% | 19% | 10% |
| Kafka producer | 4% | 7% | 3% |
| GC pauses, worst | 10 ms | 8 ms | 67 ms |

- **At 200 a second, runs cut the writer's share of the CPU from 32% to 19%,** and Execution's CPU from 43% to 39%.
- **At 1,000 a second, the HTTP request threads take 86% of Execution's CPU, and the writer only 10%.** What binds Execution at 1,500 a second is the work per request, not the writer.
- **Where the request threads' samples land:** Micrometer's per-request metrics about 20%, Tomcat 18%, Spring's web and other layers 24%, Spring Security 9%, the JDBC driver 5%, Jackson 3%, sessions read and deserialized about 7%, and our own code 1%.
- JFR's profiling settings cost enough CPU to fail the 1,000-a-second window they were recorded in (p95 547 ms), so these recordings are kept apart from the results above.

**The steady-load scenario still meets every budget.** The 10,000-bot run at 100 a second from "Steady load", with runs on:

| | 2026-11-15 | 2026-11-19, runs on |
| --- | --- | --- |
| Committed | 96.7 a second | 94.8 a second |
| Estimated p50 / p95 / p99 | 5.1 / 12.5 / 55.6 ms | 4.1 / 7.3 / 9.8 ms |
| Client p50 / p95 / p99, through the proxy | 10.9 / 18.5 / 31.0 ms | 9.7 / 13.7 / 15.5 ms |
| Within 150 ms | 100.000% | 100.000% |
| Unexpected failures | 0 | 0 |

At 100 a second, commands rarely wait for each other: the mean run was 1.00. The bots themselves cannot offer much more: the simulator reached its 1-CPU limit near 175 a second on 2026-11-17.

**Environment:** as for the 2026-11-08 run, except the proxy's CPU limit (2). Commit `d92d659` for the ten-minute windows and the JFR recordings; the one-minute ramp and the bot run used the same exchange code, before the generator's last two fixes.

### Sessions committed without waiting for the flush, 2026-11-20

The session store now has its own small connection pool, whose commits don't wait for the WAL flush (`docs/architecture.md`). Orders, trades, balances and everything else stay durable. The same ten-minute stress run as above: ten minutes at 400 a second, then ten at 1,000, each after a 2-minute warm-up, in Compose with runs of up to 32.

**Ten minutes at 1,000 commands a second now meets every budget**, where all five earlier runs failed (p99 1.05 to 26 s).

| Rate | Answered | Refused or failed | p50 | p95 | p99 | Generator lag p99 | Met |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 400/s | 400.0/s | 0 | 9.9 ms | 15.3 ms | 21.6 ms | 5.2 ms | yes |
| 1,000/s | 1,000.0/s | 0 | 22.9 ms | 47.5 ms | 112.7 ms | 11.0 ms | yes |

- **It held while the disk was slow.** At minute 16, PostgreSQL's WAL sync went from 1.7 to 4.0 ms, as in the earlier runs, so the whole 1,000-a-second window ran in that state. The writer's commit was 5.0 to 5.7 ms per transaction, at runs of 9 to 10 commands.
- **The pools at 1,000 a second:**
  - the application's pool took about 2,130 borrows a second, the password check and the account lookup, with no waits;
  - the session store's pool took about 3,000 a second, with 0.4 to 0.8 ms of mean wait and at most 12 threads waiting. Its 4 connections are enough for the budgets but close to full.
- **Fewer WAL flushes:** at 400 a second, about 365 a second against 530 before, since only the writer and the outbox relay wait for them.
- **CPU at 1,000 a second:** Execution 146% of one CPU on average (204% at peak), PostgreSQL 148% (192%).
- **Accounting:** the restart's reconciliation (11 s), the public trades and the candles all checked out.

**Environment:** as for the group-commit run above. The exchange code matched `main` after the session store and runs in Compose were both merged.

### Release v0.2.0, 2026-11-22

The same ten-minute stress run on the release candidate: ten minutes at 400 a second, then ten at 1,000, each after a 2-minute warm-up.

```powershell
uv run --project simulator python -m mse_simulator.loadtest --stress 400,1000 --stress-warmup 120 --stress-duration 600
```

| Rate | Answered | Refused or failed | p50 | p95 | p99 | Generator lag p99 | Feed lag peak | Met |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 400/s | 400.0/s | 0 | 9.4 ms | 13.8 ms | 18.4 ms | 4.6 ms | 18 | yes |
| 1,000/s | 1,000.0/s | 0 | 18.8 ms | 33.0 ms | 444.6 ms | 9.4 ms | 70 | yes |

- **Every budget met at both rates,** with no command refused or failed and the publication backlog never above zero.
- **The p99 at 1,000 a second was 444.6 ms,** within the 500 ms budget but four times the 112.7 ms of 2026-11-20, while the median and p95 were lower than then. At 0.98 ms of service time per command one writer sustains about 1,020 a second, so at 1,000 it ran close to its limit, and short bursts queued: the mean queue time was 5.85 ms. Two runs don't say which tail is typical.
- **The writer:** 2.47 ms of service time per command at 400 a second and 0.98 ms at 1,000, as runs grew.
- **Processor at 1,000 a second, mean (peak):** Execution 139% (207%), PostgreSQL 147% (187%), Kafka 26% (186%), the proxy 24% (44%).
- **Accounting:** the restart's reconciliation (11 s), the public trades and the candles all checked out.

**Environment:** as for the steady run above. Commit `2b082a3`.
