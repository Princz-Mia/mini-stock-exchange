# ADR 0002: Analytics boundary and the Java split

Status: accepted

## Context

Candles need stateful stream processing. Flink 2.2 officially supports Java 17, while the services run on Java 25 with Spring Boot, whose Kafka client and Jackson versions differ from Flink's. Trading must keep working when analytics is down.

## Decision

The Flink job is a separate artifact, compiled for and tested on Java 17, and it runs on Flink's Java 17 image. It has no Spring Boot parent, and the build bans Spring from its graph and Flink from the services' graphs. It shares only `event-contracts`, which contains Avro schemas and a small codec compiled for release 17. The job reads and writes Kafka and never touches a business database. Each side manages its own Kafka client.

Delivery is at-least-once. Correctness comes from sequence checks, complete aggregates and stable event IDs, not from a claim of exactly-once processing.

## Consequences

- Flink, Kafka client and Boot upgrades do not force each other, but the compatibility gate must rerun after any of them.
- A Flink outage leaves trading and the live book working while candles go stale, and the UI must say so.
- Tests need a JDK 17 alongside JDK 25, through `JAVA17_HOME`.
- Avro 1.12.2 requires explicit class trust, handled once in `WireEvents`.

## Revisit when

Flink officially supports Java 25 with a compatible Kafka connector, or schema rollout between services needs runtime governance, which would justify a schema registry.
