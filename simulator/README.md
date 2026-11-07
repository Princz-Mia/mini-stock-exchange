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
- **Strategies:** each bot draws from its own generator, seeded by the run's seed and its account, so the same
  observations give the same decisions. Market makers take the first turns of a run. No bot orders more than it
  holds or than the run's position limit allows, and old orders are cancelled.
  - **Market maker:** one bid and one ask on one of the run's instruments, a few ticks either side of a
    reservation price: the mid, the last trade or the reference, leaning against the bot's inventory. Quotes
    that drift too far from their target, or would cross the other side, are cancelled before new ones go in.
  - **Momentum:** short and long moving averages of each instrument's price, sampled once per market poll for
    all momentum bots. Past a threshold, the side is drawn with a bias toward the trend, at the best opposite
    quote, one order at a time with a cooldown after each.
  - **Noise:** limit orders on the run's instruments, a random side a few ticks either side of the mid, the
    last trade or the reference price.
- **Stop:** once the run is stopped or out of time, no new orders; every command in flight gets its final
  answer, every open order of the run's bots is cancelled (each one that can't be is reported), and the
  worker confirms the stop. A worker that stops without that leaves the run to the next one.
- **Safety:** a command keeps its idempotency key until the exchange answers it finally, so a retry after a busy,
  timed-out, rate-limited or lost answer can't apply twice. New orders pause while the market isn't open or market data
  isn't live and recent.

```powershell
uv sync --frozen; uv run --frozen ruff check .; uv run --frozen pytest -q
```
