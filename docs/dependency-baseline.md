# Dependency baseline

Resolved on 2026-11-02. A change here means updating the pin, recording why, and rerunning `scripts/compat-gate`. Latest gate result: [below](#compatibility-gate-result).

## Runtimes and build tools

| Component | Version | Pinned in |
| --- | --- | --- |
| JDK, services | Oracle JDK 25.0.5 | `JAVA_HOME` locally (25.0.5.0.3); service `Dockerfile`s; enforcer range `[25.0.5,26)` |
| JDK, Flink | 17 (Temurin 17.0.20 locally and in the job build; 17.0.20.1 in the Flink image) | `JAVA17_HOME`; `analytics-job/Dockerfile` |
| Maven / wrapper | 3.9.16 / wrapper 3.3.4, `only-script` | `.mvn/wrapper/maven-wrapper.properties`, with SHA-256 |
| Node.js / npm | 24.22.2 / lockfile v3 | `web/.node-version`, `web/package.json` engines |
| Python / uv | 3.14.10 / 0.13.6 | `simulator/.python-version`, `requires-python`, `uv_build==0.13.6` |

## Backend

| Component | Version | Source |
| --- | --- | --- |
| Spring Boot | 4.1.2 | parent of both services |
| Spring Kafka / kafka-clients (services) | 4.1.2 / 4.2.2 | Boot BOM |
| Spring Session JDBC | 4.1.1 | Boot BOM; schema copied into Execution `V1__foundation.sql` |
| Flyway / PostgreSQL JDBC | 12.4.0 / 42.7.14 | Boot BOM |
| JUnit / AssertJ / Testcontainers | 6.0.3 / 3.27.7 / 2.0.5 | Boot BOM; the two non-Boot modules pin the same JUnit and AssertJ |
| ArchUnit | 1.5.1 | Execution POM, test scope |
| Avro and `avro-maven-plugin` | 1.12.2 | `event-contracts/pom.xml` |
| Flink | 2.2.1 | `analytics-job/pom.xml` |
| Flink Kafka connector / kafka-clients (Flink) | 5.0.0-2.2 / 4.2.0 | `analytics-job/pom.xml`; connector-managed and shaded into the job jar only |
| Jackson | 3.1.7 for Boot and 2.21.7 for Avro in the services; 2.18.2 shaded in the job | Boot BOM; connector |
| Project modules | 0.1.0 | Initializr's `0.0.1-SNAPSHOT` replaced: snapshot dependencies are not allowed |

Non-Boot modules pin their Maven plugins to the versions in the Boot 4.1.2 BOM: compiler 3.15.0, surefire 3.5.6, resources 3.5.0, jar 3.5.1, install 3.1.4, clean 3.5.0, deploy 3.1.4, enforcer 3.6.3, shade 3.6.2.

## Containers

All are referenced by tag and digest; `compose.yaml` and the `Dockerfile`s are the source of truth.

| Image | Digest |
| --- | --- |
| `postgres:18.6` | `sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81` |
| `apache/kafka:4.3.2` | `sha256:852a0d49fc4e656b022e7f23785411802c9a088c78a7c5e39f33969de9c227a7` |
| `flink:2.2.1-java17` | `sha256:c95f48391db3e191345549b4d06406c08616fe8f99ee57fa4f4c44268c912a46` |
| `container-registry.oracle.com/java/jdk-no-fee-term:25.0.5` | `sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960` |
| `eclipse-temurin:17.0.20.1_1-jdk` | `sha256:91011320978c10ec51d2cde1eb6f616db6d2a739a0e521096c343dd3fed9abe6` |
| `node:24.22.2-alpine` | `sha256:8a32c8cf649a49915a66e81e7a609223c708d0adabf288ee3f4077d765542f85` |
| `nginxinc/nginx-unprivileged:1.30.6-alpine` | `sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03` |
| `python:3.14.10-slim` | `sha256:930e1abba7c4e8f96ed93e8012c35d174d7732752731bbd5e8e757d7ec6f3966` |
| `ghcr.io/astral-sh/uv:0.13.6` | `sha256:af22a52f5eafef3d78e551599d405c02df61919d18028f0cea8eda93bc9986d0` |
| `prom/prometheus:v3.15.1` | `sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2` |
| `grafana/grafana:13.2.5` | `sha256:9f7aa1e1e3246aade5792d35a738049f7f32f89cd51d1956cf426a4e86268a84` |

## Frontend and simulator

Exact versions live in `web/package.json` with `web/package-lock.json`, and `simulator/pyproject.toml` with `simulator/uv.lock`.

| Frontend | Version | Simulator | Version |
| --- | --- | --- | --- |
| React / React DOM | 19.3.1 | httpx | 0.28.1 |
| TypeScript | 6.0.3 | NumPy | 2.5.4 |
| Vite / plugin-react | 8.3.5 / 6.2.0 | Pydantic | 2.14.2 |
| React Router | 8.5.1 | pytest | 9.2.0 |
| TanStack Query / Table | 5.105.1 / 9.3.1 | Ruff | 0.17.3 |
| React Hook Form / resolvers / Zod | 7.91.0 / 7.0.1 / 4.6.7 | | |
| Radix Separator | 1.2.0 | | |
| Lightweight Charts | 5.2.1 | | |
| Vitest / Testing Library React | 5.0.5 / 16.3.3 | | |
| ESLint / typescript-eslint | 10.14.0 / 8.72.1 | | |
| Playwright | 1.64.1 (browsers not installed yet) | | |

## Decisions and deviations

| Decision | Why |
| --- | --- |
| Initializr metadata was inspected, not used to generate anything new | The API reports Boot 4.0.0 as its default and only value. The checked-in POMs keep 4.1.2. |
| Replaced Initializr's service-level wrappers | They pinned Maven 3.10.0. One root wrapper now pins 3.9.16. |
| Replaced Initializr's test images | `postgres:latest` and `apache/kafka-native:latest` are floating; tests use the Compose digests. |
| TypeScript 6.0.3, not npm's latest 7.0.2 | The project targets 6.x, and typescript-eslint 8.72.1 requires `<6.1.0`. |
| `@types/node` follows Node 24, not latest 26 | Types must match the pinned runtime. |
| TanStack Table v9, whose API differs from v8 | Latest stable. `useTable` takes `features`; examples for v8 do not apply. |
| `WireEvents` trusts each contract class by exact name | Avro 1.12.2 rejects untrusted generated classes at decode time. A system property would need repeating in every JVM and a package-wide trust would be broader. |
| Linux build stages verify the Maven `tar.gz` hash | The JDK images have no `unzip`, so `mvnw` falls back to the `tar.gz`; the committed hash covers the Windows `zip`. |
| Oracle `jdk-no-fee-term` image for services | Publicly pullable Oracle JDK 25.0.5 build (`25.0.5+9`). The local install is the `25.0.5.0.3` update. It is a full JDK, so images are larger than a JRE base. |
| Flink job bundles Avro; `flink-avro` not used | `flink-dist` has no Avro, so the job carries exactly 1.12.2 and decodes through `WireEvents`. |
| `flink-connector-base` is `provided` | It is present in `flink-dist-2.2.1.jar`, confirmed by listing the jar. |
| Seeded random JUnit tests instead of a property-testing library | One less dependency. `Samples` generates boundary and seeded random inputs. There is no automatic shrinking, so a failure reports the original input. |
| Proxy returns 404 for `/api/v1/internal/*` | Execution owns these paths, but they must stay off the public origin. The simulator calls Execution directly on the Compose network. |
| Proxy upstreams are nginx variables | Per-request DNS lets either service stop or start without the proxy failing. |

## Known limitations

- `npm audit`: 11 moderate advisories, all in the ESLint toolchain through `minimatch` (GHSA-g3hv-75wg-m249). Development only and not in the production bundle. The suggested fix is a downgrade to `eslint@4`, so none was applied.
- Spring Boot 4.2 milestones and Flink 2.3.0 exist and are deliberately unused. Flink 2.3 is not in this baseline's connector compatibility.
- Mockito prints a JDK 25 dynamic-agent warning in `*IT` runs. It does not fail today and may once the JDK disables dynamic agents by default.
- The main web chunk is 590 kB (185 kB gzip) because the compatibility probe imports everything at once. Revisit with route splitting in M2.

## Compatibility gate result

Run on 2026-11-02 on Windows 11, Docker Desktop 29.8.2 (16 CPUs, 16 GB for containers). `scripts\compat-gate.ps1` and `scripts/compat-gate.sh` (under Git Bash) both passed. The POSIX form was also run from a clean Git worktree, and the full gate runs on Linux in CI. Image layers came from the build cache; volumes and databases were new.

| Step | Evidence |
| --- | --- |
| Toolchain | Oracle 25.0.5.0.3, Temurin 17.0.20, Maven 3.9.16, Node 24.22.2, Python 3.14.10 |
| Maven verify | 48 tests pass: contracts 5, Execution 33 (17 unit, 16 with real PostgreSQL), Market Data 7 with real PostgreSQL, Flink job 3 on JDK 17. Bytecode major 69 for services, 61 for contracts and job |
| Frontend | `npm ci`, typecheck, lint, 3 tests and production build |
| Simulator | Frozen sync, Ruff, 4 tests |
| Compose | Full profile healthy; Flink tests also pass inside the Linux JDK 17 image build |
| Runtimes | Services on Oracle `25.0.5`, Flink on `17.0.20.1`, job `RUNNING`, checked containers non-root |
| Kafka topics | Three partitions each; compacted market topic; seven-day trades and candles |
| Migrations | Every row of each `flyway_schema_history` was installed successfully by that service's owner role; no foreign-owned tables |
| Database isolation | Owner and app roles of each service get `permission denied` on the other database and on `postgres`; app roles have no DDL and cannot touch migration history; owners cannot install `dblink` |
| Proxy | SPA, path routing to each owner, `/api/v1/internal/*` is 404, Actuator not reachable |
| Login | A CSRF-protected login through the proxy starts a session that `GET /me` accepts |
| Independence | Stopping either service gives a fast 502 or 504 on its routes only; the other stays ready; both restart healthy |
| Avro | Boot client 4.2.2 on JDK 25 publishes golden fills plus one redelivery; Flink on JDK 17 with client 4.2.0 aggregates; Boot client on JDK 25 reads the expected candle (O 10000, H 10100, L 10000, C 10100, volume 10, 3 trades, notional 100300, 3 distinct event IDs) |
| Outage isolation | Both services stay ready with Kafka stopped. Neither service uses Kafka at runtime yet, so M2 and M3 must repeat this |

Checked once by hand, not part of the gate:

- Negative control: with Flink stopped, `CandleConsumeSmoke` fails at its 120-second timeout, so a pass depends on Flink.
- Recovery: after a Kafka restart the running job processed new trades.
- `init-env` in both forms; base profile alone; `observability` profile, with Prometheus reporting both services `up` and Grafana healthy.

