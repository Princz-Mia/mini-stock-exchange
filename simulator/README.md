# mse-simulator

Python bot simulator. One worker process trades for the bots of the bot run the operator started, through the
same public and authenticated API as people use.

- **Control:** with `MSE_SIMULATOR_TOKEN`, the worker polls Execution for its assignment and holds the run's
  lease with a heartbeat that reports cumulative totals. One worker holds a run at a time; `MSE_WORKER_ID`
  (default: the host name) names it. Without the token the bots stay idle. Each heartbeat also says where the
  worker is with the run: `LOADING` so many bots of so many while it reads their token files and builds them, 500
  at a time, then `TRADING`. A stop that comes during the loading is taken once every bot is loaded: the worker
  then places nothing and cancels the bots' quotes, and the heartbeats go on reporting the loading until then.
  Among the totals are the bots' steps that could place nothing new, by why: market data too old or not live, the
  market not open, or another reason, such as none of the run's instruments listed yet.
- **Credentials:** one `<accountId>.token` file per bot, written once by the exchange when the operator
  provisions bots. Compose mounts that volume read-only at `/run/mse/bot-tokens`. The assignment names each
  bot's file, never its token.
- **Pacing:** the run's commands per second across its bots, round robin, one call in flight per bot, at most
  32 trading calls (orders and cancels) in flight overall, and reads (balances, open orders) limited apart, to
  64 by default. The rate counts commands sent: a bot that looks and decides to wait hands
  its turn to another, up to three looks per command. New commands stop if the lease isn't renewed in time.
- **Strategies:** each bot draws from its own generator, seeded by the run's seed and its account, so the same
  observations give the same decisions. Market makers take the first turns of a run. No bot orders more than it
  holds or than the run's position limit allows, and old orders are cancelled.
  - **Fair values:** each instrument of a run has a fair value that only the bots know. It wanders at random
    and is pulled back toward the instrument's reference price (half of a gap closes in four hours), so it
    moves a few percent and returns. It moves one step per market poll while the market is open, from the
    run's seed. Each bot sees it through a small bias of its own. A value never sets a price: it only shapes
    the orders a bot sends, and prices are made by orders meeting in the book.
  - **Where a value starts:** at the first poll of an open market, at a price the market supports: the last
    trade, which two accounts agreed on; with no trade yet, the mid of a narrow book; otherwise the reference
    price. A second run on a market that has traded, or a restarted worker, starts again from the last trade.
    A trade counts however far from the reference it is, so a market that collapsed climbs back rather than
    jumps; one share traded far away starts a run there just the same.
  - **The market's price:** where the bots take an instrument to trade now is the mid only of a narrow book,
    whose two sides are at most 100 basis points of the mid apart, or one tick; otherwise the last trade, else
    the reference price. A wide book's mid is no price anyone offered: one order left far away, such as a bid
    at 1.00 facing an offer at 500.00 around 100.00, would otherwise move the noise bots' prices, the momentum
    signal and a run's values halfway to it.
  - **Prices:** spreads and offsets are in basis points of the price, at least one tick, so a cheap instrument
    trades like a dear one. A limit is rounded to the tick once, a buy down and a sell up, which favours
    neither side.
  - **Market maker:** one bid and one ask on one instrument, ten basis points either side of a reservation
    price: the bot's idea of the value, leaning against its inventory. It quotes around the value, not around
    the current price, so it buys below the value and sells above it wherever the market is. The run's makers
    take its instruments in turn. A quote older than 30 seconds is cancelled and replaced in the same step,
    with a quote for the side that has none, the cancelled side last: a maker whose turns come further apart
    than that, as in a large run, then bids and asks in turn instead of only bidding, and one with closer
    turns keeps both. The replacement is a command of its own for the run's rate. Quotes that drift too far
    from their target, or would cross the other side, are cancelled before new ones go in.
  - **Momentum:** short and long moving averages of each instrument's price, sampled once per market poll for
    all momentum bots and started afresh after a halt. Past a threshold, the side is drawn with a bias toward
    the trend, never more than three orders in four. An order takes the best opposite quote only if it is
    within twenty basis points of the price and rests otherwise, one order at a time with a cooldown.
  - **Noise:** limit orders on the run's instruments, a random side, up to thirty basis points either side of
    a centre: the market's price moved a quarter of the way toward the bot's idea of the value.
- **Market view:** one view of the public market for all of a run's bots, polled every second: the phase and the
  simulation from Execution, then that simulation's instruments and quotes from Market Data. It asks for the
  simulation by its id, so a new simulation's bots see its instruments as soon as Market Data has them, even while
  Market Data still takes the one before for current.
- **Stop:** once the run is stopped or out of time, no new orders; every command in flight gets its final
  answer, every open order of the run's bots is cancelled (each one that can't be is reported), and the
  worker confirms the stop. A worker that stops without that leaves the run to the next one.
- **Market close:** a market that closes ends the run at once, with nothing for the worker to settle: the
  exchange itself cancels every order, revokes the bots' tokens and ends the run. The worker starts no new step
  and sends no cancel. It gives the commands already sent up to 15 seconds to be answered, reports its totals in
  one last heartbeat, which the exchange is expected to refuse and which is not sent again, logs one line, and
  goes back to asking for its next run. It learns of the close from its market poll, from the first refused
  token or from a heartbeat refused because the run is finished, whichever comes first. A halt is not a close:
  the run goes on, and new orders wait for the resume.
- **Refused tokens:** a bot whose token the exchange refuses takes no more steps in that run, since a token is
  never renewed, and the worker asks at once whether the market closed. So a close costs at most one refused
  call per bot, and a single revoked bot does not fail at every turn. The worker's log counts such bots.
- **Safety:** a command keeps its idempotency key until the exchange answers it finally, so a retry after a busy,
  timed-out, rate-limited or lost answer can't apply twice. New orders pause while the market isn't open or market data
  isn't live and recent: at most two seconds old, counted from when the poll's answers arrived, so a slow poll does
  not by itself make fresh data look old.

```powershell
uv sync --frozen; uv run --frozen ruff check .; uv run --frozen pytest -q
```
