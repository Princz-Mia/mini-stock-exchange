# mse-simulator

Python bot simulator. One worker process trades for the bots of the bot run the operator started, through the
same public and authenticated API as people use.

- **Control:** with `MSE_SIMULATOR_TOKEN`, the worker polls Execution for its assignment and holds the run's
  lease with a heartbeat that reports cumulative totals. One worker holds a run at a time; `MSE_WORKER_ID`
  (default: the host name) names it. Without the token the bots stay idle.
- **Credentials:** one `<accountId>.token` file per bot, written once by the exchange when the operator
  provisions bots. Compose mounts that volume read-only at `/run/mse/bot-tokens`. The assignment names each
  bot's file, never its token.
- **Pacing:** the run's commands per second across its bots, round robin, one command in flight per bot and at
  most 32 calls in flight overall. New commands stop if the lease isn't renewed in time.
- **Strategy:** noise; momentum and market-maker bots stay idle until their strategies arrive. Each bot draws
  from its own generator, seeded by the run's seed and its account, and places limit orders on the run's
  instruments a few ticks either side of the mid, the last trade or the reference price. It never orders more
  than it holds or more than the run's position limit allows. Old quotes are cancelled.
- **Stop:** once the run is stopped or out of time, no new orders; every command in flight gets its final
  answer, every open order of the run's bots is cancelled (each one that can't be is reported), and the
  worker confirms the stop. A worker that stops without that leaves the run to the next one.
- **Safety:** a command keeps its idempotency key until the exchange answers it finally, so a retry after a busy,
  timed-out or lost answer can't apply twice. New orders pause while the market isn't open or market data
  isn't live and recent.

```powershell
uv sync --frozen; uv run --frozen ruff check .; uv run --frozen pytest -q
```
