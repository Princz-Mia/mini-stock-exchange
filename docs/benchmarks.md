# Benchmarks

Measured results, each with the environment it was measured in. They are results on one machine, not guarantees: rerun the command on yours before relying on a number.

## Steady load

**Scenario:** five instruments and 1,000 bots (200 market makers, 200 momentum, 600 noise) trading at 100 commands a second. Placements and cancels are mixed. There is a two-minute warm-up, then ten minutes are measured.

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

### 2026-11-08

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
