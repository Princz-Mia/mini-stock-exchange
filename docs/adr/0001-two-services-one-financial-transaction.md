# ADR 0001: Two services, one financial transaction

Status: accepted

## Context

An exchange has an obvious decomposition into matching, risk, ledger, market data and identity services. But an order's risk check, matching and settlement must succeed or fail together, and accounts share cash across symbols.

## Decision

Build two business services. `execution-service` owns authentication, accounts, orders, matching, risk, settlement and the ledger, and commits all financial effects of a command in one PostgreSQL transaction. `market-data-service` owns rebuildable public projections and has no authority over money. Each service has its own database, owner role, runtime role and migration history; they communicate only through events and APIs.

## Consequences

- No distributed transaction or saga is needed for settlement, so a failed command leaves no partial financial effect.
- Execution is a larger service, and it scales only as one instance. Internal boundaries must be kept by packages, not by the network.
- Public reads cannot slow trading through shared queries, though a shared local PostgreSQL server still shares CPU and disk.
- Public data is eventually consistent with trading, and the UI must show that lag.

## Revisit when

Measured load shows one Execution instance cannot meet its latency budget after profiling, or a capability needs an independent release cycle that packages cannot provide.
