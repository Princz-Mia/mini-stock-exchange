# mse-simulator

Python bot simulator. One process trades for every provisioned bot account through the same public and
authenticated API as people use.

- **Credentials:** one `<accountId>.token` file per bot, written once by the exchange when the operator
  provisions bots. Compose mounts that volume read-only at `/run/mse/bot-tokens`.
- **Pacing:** `MSE_BOT_RATE` trading commands per second across all bots, round robin, one command in flight per
  bot and at most 32 calls in flight overall. `0`, the default, keeps the bots idle.
- **Strategy:** noise. Each bot draws from its own generator, seeded by `MSE_BOT_SEED` and its account, and
  places limit orders a few ticks either side of the mid, the last trade or the reference price, never for
  more than it holds. Old quotes are cancelled.
- **Safety:** a command keeps its idempotency key until the exchange answers it finally, so a retry after a busy,
  timed-out or lost answer can't apply twice. New orders pause while the market isn't open or market data
  isn't live and recent.

```powershell
uv sync --frozen; uv run --frozen ruff check .; uv run --frozen pytest -q
```
