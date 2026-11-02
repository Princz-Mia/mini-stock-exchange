# mini-stock-exchange

A simulated, browser-based stock exchange. Humans and Python bots trade virtual cash and shares through the same API and rules, and prices emerge from executions against a price-time-priority limit order book. No real money or external market data is involved.

| Part | Technology | Role |
| --- | --- | --- |
| `services/execution-service` | Java 25, Spring Boot | Orders, matching, risk and settlement in one database transaction |
| `services/market-data-service` | Java 25, Spring Boot | Rebuildable public market projections |
| `analytics-job` | Java 17, Flink | Trade-to-candle aggregation |
| `event-contracts` | Avro | Wire contracts shared by the above |
| `web` | React, TypeScript | Trading terminal and reverse proxy |
| `simulator` | Python | Trading bots |

Each service owns its own PostgreSQL database. Events flow from Execution through Kafka to Flink and Market Data.

## Run it

Requires Docker with Compose v2. In PowerShell:

```powershell
.\scripts\init-env.ps1
docker compose --profile full up -d --build --wait
```

On Linux or macOS, use `./scripts/init-env.sh`. The application is then on <http://localhost:8080>.

## Learn more

- [Commands and conventions](CONTRIBUTING.md)
- [Milestones](docs/tasks/milestones.md)
- [Dependency baseline and compatibility results](docs/dependency-baseline.md)

Trading features are being built milestone by milestone; the current state is in the milestone list.
