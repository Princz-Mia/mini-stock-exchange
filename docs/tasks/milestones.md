# Milestones

Ordered work for the Mini Stock Exchange. A milestone is done only when its exit check has been run and its result recorded. Each milestone depends on the one before it.

Status: `[x]` done with evidence · `[ ]` open

## M0 Foundation and compatibility

Exit check: `.\scripts\compat-gate.ps1` passes, plus the open items below. Gate evidence: [dependency baseline](../dependency-baseline.md#compatibility-gate-result).

- [x] M0-1 Pinned baseline in `docs/dependency-baseline.md`, POMs, lockfiles, version files and Compose digests. Check: gate step "Toolchain versions".
- [x] M0-2 Maven wrapper 3.9.16, aggregator and four independently buildable modules. Check: gate step "Maven verify".
- [x] M0-3 Two services with separate databases, owners, runtime roles and Flyway histories. Check: gate steps "Migrations" and "Database isolation", `DatabaseIsolationIT` in both services.
- [x] M0-4 Health checks, non-root images, reverse-proxy routing, independent start/stop. Check: gate steps "Runtimes", "Proxy", "Independence", "Outage isolation".
- [x] M0-5 Real Avro through Kafka and Flink and back. Check: gate step "Avro".
- [x] M0-6 Frontend and simulator build with chosen packages. Check: gate steps "Frontend", "Simulator".
- [ ] M0-7 CI skeleton with SHA-pinned actions. Check: first green run on GitHub.
- [x] M0-8 Login skeleton (F01 shape only): session cookie, CSRF, seeded development user. Check: `AuthenticationIT`, and gate step "Login".
- [x] M0-9 ADRs for the decisions already taken, in `docs/adr/`: two-service ownership, analytics boundary, session authentication. Check: files exist with consequence and revisit condition.

## M1 Trading correctness

Covers F02, F03, F04, F09. No UI polish compensates for a failed invariant.

- [x] M1-1 Exact units, checked arithmetic and API decimal parsing. Check: `MoneyTest` and `PriceAndQuantityTest`, seeded random tests for overflow and format/parse round trip. Error-code mapping for HTTP comes with M1-5.
- [ ] M1-2 Reference matcher and optimized book. Check: property test comparing them; golden fixtures.
- [ ] M1-3 Schema: simulation, accounts, instruments, orders, trades, journal, balances, command results, outbox. Check: migration ITs; runtime role cannot UPDATE or DELETE journal rows; deferred balance trigger rejects an unbalanced batch.
- [ ] M1-4 Single writer: bounded queue, one transaction per command covering risk, matching, settlement, command result and outbox. Check: forced failure before commit leaves no financial effect and no book change.
- [ ] M1-5 Placement, cancel and idempotency. Check: repeat and concurrent keys return one result; conflicting body returns 409.
- [ ] M1-6 Writer ownership and reconstruction. Check: second instance refused; stale epoch fenced; restart rebuilds the book; a ledger mismatch fails closed.

## M2 Usable vertical slice

Covers F01-F06.

- [ ] M2-1 Outbox relay and `MarketSnapshot`/`TradeExecuted` publication. Check: duplicate after acknowledgement is harmless; no uncommitted event reaches Kafka.
- [ ] M2-2 Market Data projections, deduplication and public REST/SSE. Check: database rebuild from retained topics; an empty projection is not live-ready.
- [ ] M2-3 OpenAPI contract and generated client. Check: CI drift check.
- [ ] M2-4 Trading terminal and portfolio, removes `web/src/compat`. Check: browser test of login, submit, partial fill, cancel, portfolio.
- [ ] M2-5 Operator SETUP, open, halt and resume; small funded bot population. Check: browser test; audit rows written.

## M3 Full simulation and analytics

Covers F07, F08.

- [ ] M3-1 Complete Flink contract: sequence gaps as errors, eight-day state cleanup, recovery from checkpoints across JobManager restarts. Check: restart and replay reconcile exactly.
- [ ] M3-2 Avro writer-schema catalogue and retained-writer compatibility check. Check: CI decodes every retained writer in both services and the job.
- [ ] M3-3 Candle projection and API with freshness. Check: OHLCV/count/notional reconcile with the trade export.
- [ ] M3-4 Noise, momentum and market-maker strategies; lease and heartbeat. Check: seeded decisions repeat; stop resolves in-flight commands.
- [ ] M3-5 Outage verification: Flink-only and Kafka outages with live trading. Check: failure tests.

## M4 Scale and finish

Ends with the feature freeze.

- [ ] M4-1 1,000 logical bots at 100 commands/s for 10 minutes. Check: `docs/benchmarks.md` records the budgets with the environment.
- [ ] M4-2 JMH and JFR, with one before/after optimization. Check: comparison on identical data.
- [ ] M4-3 Responsive and error states; Grafana dashboard. Check: screens at 1440x900, 1280x720 and narrow width.

## M5 Hardening

Covers F09, F10.

- [ ] M5-1 Failure tests complete. Check: authoritative and derived totals compared after each recovery.
- [ ] M5-2 Security and accessibility checks, secret and dependency scanning. Check: reports attached; findings closed or listed.
- [ ] M5-3 Backup/restore command, `docs/runbook.md`, `docs/architecture.md`, remaining ADRs. Check: restore rehearsed.

## M6 Release buffer

- [ ] M6-1 Clean-clone start on the target machine. Check: full acceptance list.
- [ ] M6-2 Five-minute scripted demo in `docs/demo.md`. Check: rehearsed end to end.
- [ ] M6-3 Release workflow: a version tag builds the images and publishes them to GitHub Container Registry. Check: images pull and start from a clean machine.
- [ ] M6-4 Tagged release with honest limitations. Check: unmet targets listed.
