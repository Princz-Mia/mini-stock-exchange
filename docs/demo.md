# Five-minute demo

A scripted walk through the exchange: an operator runs the market, bots trade, a trader places and cancels orders and sees fills settle, the market halts and resumes, and analytics fails without stopping trading. The times are a guide for presenting it in five minutes.

## Before you start

Do this beforehand. The first build takes a few minutes.

```powershell
.\scripts\init-env.ps1
docker compose --profile full up -d --build --wait
```

On Linux or macOS, use `./scripts/init-env.sh`. The stack starts with a demo market already set up and open: NOVA, ORBT, HELI, QUAD and VELO, the trader funded, and 20 bots ready: 4 market makers, 12 noise and 4 momentum bots.

The passwords for `operator` and `trader` are `DEV_OPERATOR_PASSWORD` and `DEV_TRADER_PASSWORD` in `.env`. Use two browser windows, one of them private, so both can be signed in at once: the operator in one, the trader in the other. Open <http://localhost:8080> in both.

On each user's first sign-in a short tour of their screen opens. Select **Skip tour** to go straight on; **Help** in the top bar has it again, with a page on how the exchange works.

## 0:00 The operator starts the bots

Sign in as `operator`.

1. Point out the **Market** panel: the market is open, with the time it opened and the last command's sequence number.
2. In **Bot runs**, set **Market makers** to 4, **Noise** to 12 and **Momentum** to 4, and **Commands per second** to 20.00.
3. Select **Start run**. The run turns **Running** once the simulator picks it up, and its placed and cancelled counts start to climb.

Say: each bot is an ordinary client with its own token, and the run's seed makes its decisions repeatable.

## 0:45 The trader watches the live market

In the other window, sign in as `trader`. The terminal opens on the first instrument; select **NOVA** in the watchlist.

1. The order book's bids and asks and the recent trades move as the bots trade. The chart draws the trades.
2. The header says **Market data: Live** and **Connected**.

Say: the book and tape are public data, streamed by Market Data from Kafka, separately from trading.

## 1:30 A resting order, and a cancel

In the ticket, with **Buy** selected:
1. Enter 10 **Shares** and a **Limit price** a few dollars below the best bid.
2. Select **Buy NOVA**. The ticket says the order is resting, and it appears in **Open orders**.
3. **Available cash** drops by the amount held for it.
4. Select **Cancel** on that row. The order leaves the table and the cash is available again.

Say: the hold, the order and the ledger entries commit in one transaction before the answer comes back; nothing is shown optimistically.

## 2:15 A fill that settles

1. Enter 5 **Shares** and a **Limit price** about a dollar above the best ask, so it reaches past the first ask if that one is small.
2. Select **Buy NOVA**. It takes the cheapest asks first, each at its own price, until all 5 are filled. The ticket says it filled, and the trades appear at the top of the tape.
3. Open **Portfolio**:
   - **Holdings** shows NOVA up by 5 shares, its average cost now between the reference price and what you paid, and its estimated unrealized profit or loss;
   - **Executions** lists the fill, at the price of the order it matched;
   - the summary shows the cash spent.

Say: the private views refresh as soon as the trade commits, through the trader's own stream, and an order is filled at the resting order's price, so a higher limit can't cost more.

## 3:00 Halt and resume

In the operator window, select **Halt trading**.

1. In the trader's terminal, a **Trading halted** badge appears beside the symbol, and the ticket says new orders are paused while open orders can still be cancelled. **Buy NOVA** is disabled.
2. The bots' new orders stop, and the tape goes quiet.

Select **Resume trading**. The badge goes, the ticket accepts orders again, and the bots carry on.

Say: halt and resume are commands like any order, sequenced and audited, and the books keep every resting order's place.

## 3:45 Analytics fails, trading doesn't

In a terminal:

```bash
docker compose stop flink-jobmanager flink-taskmanager
```

Place another order as the trader, priced above the best ask again: it fills as before, and the tape and book stay live. Then start Flink again:

```bash
docker compose up -d --wait flink-jobmanager flink-taskmanager
```

The candles catch up from where they stopped.

Say: Kafka sits after the commit and analytics only reads Kafka, so it can stop without touching trading; it resumes from its checkpoint and counts nothing twice.

## 4:30 Wrap up

In the operator window, select **Stop run**. Once the simulator confirms, the run's row in **Recent runs** gets its end time with the final placed and cancelled counts, the form offers **Start run** again, and the bots' open orders are cancelled.

Close with what was shown: matching with price-time priority, settlement in one transaction, live public and private streams, operator control, and isolated analytics. [architecture.md](architecture.md) explains how; [benchmarks.md](benchmarks.md) has the measured load results, and [runbook.md](runbook.md) the recovery procedures.

## Resetting

To run the demo again from the start, delete the data and start again: the demo market is set up afresh.

```bash
docker compose --profile full down -v
docker compose --profile full up -d --wait
```
