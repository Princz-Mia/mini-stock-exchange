# Benchmarks

Measured results, each with the environment it was measured in. They are results on one machine, not guarantees: rerun the command on yours before relying on a number.

## Matcher

The order book alone, with no database (JMH, `MatcherBenchmark`). Each invocation replays the same recorded 10,000 commands from a fresh book. Each command does matching, both sides' 20-level snapshot depth, then apply or cancel. 2 forks, 20 measured iterations, Oracle JDK 25.0.5.

| Book | Per command |
| --- | --- |
| 200 resting orders | 427 ± 13 ns |
| 5,000 resting orders | 596 ± 22 ns |

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
