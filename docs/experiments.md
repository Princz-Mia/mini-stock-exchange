# Experiments

**Simulation-model evidence.** These results come from this exchange's own bots trading with each other in one local simulation. They show how this model behaves, not how any real market would. Each comes with the commit and the environment it ran on; rerun the command before relying on a number.

## Market-maker share at a fixed total rate

```powershell
uv run --project simulator python -m mse_simulator.loadtest.experiment   # about 45 minutes
```

The command starts its own throwaway stack (Compose project `mse-experiment`, port 38090), tags the bots the shares need on five instruments at 100.00, each bot with 100,000.00 cash and 200 shares of each instrument, and runs one bot run per market-maker share and seed in turn. Every run has the same number of bots and the same total rate, so only the share of market makers changes; the bots that are not market makers are three noise bots to one momentum bot. Each run is stopped a set time after its bots begin trading. Execution works out its figures from its command window, and Market Data's minute-by-minute book gives its average spread and volatility. `--help` lists the options (bots, rate, duration, shares, seeds, `--keep-stack`).

The runs are not independent. They take place one after another in one simulation, sharing the bots' accounts and the order books, and nothing is topped up between runs, so each run starts where the runs before it left off. The share order turns by one for each seed so that no share always goes first, and the report lists the runs in the order they ran. A seed repeats each bot's decisions for the same observations, but live timing does not repeat: the seeds show the spread between runs, not an exact repeat.

### 2026-11-21, 40 bots at 20 commands a second

Shares of 10%, 30% and 50%, seeds 1 and 2, each run stopped 5 minutes after its bots began trading. Commit `96232bc721c4`; the simulator reported version `0.1.0+96232bc721c4`, numpy 2.5.4, Python 3.14.10 and the same settings in every run. Windows 11, Docker Desktop (WSL 2) with 16 CPUs and 15.2 GiB; Execution limited to 2 CPUs and 2 GiB, Market Data to 1.5 CPUs, the simulator to 1 CPU; PostgreSQL 18.6 with `fsync` and `synchronous_commit` on, except that Execution's sign-in session store commits asynchronously on its own connections.

The runs, in the order they ran:

| Run | Seed | Market makers | Ran for | Achieved rate | Trades | Volume | Average spread | Volatility |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 10% (4 of 40) | 5 min 14 s | 19.94/s | 2,847 | 9,289 | 0.0209 | 0.0937% |
| 2 | 1 | 30% (12 of 40) | 5 min 14 s | 20.02/s | 2,429 | 8,353 | 0.0247 | 0.0580% |
| 3 | 1 | 50% (20 of 40) | 5 min 13 s | 19.96/s | 2,106 | 7,600 | 0.0200 | 0.0499% |
| 4 | 2 | 30% (12 of 40) | 5 min 13 s | 20.05/s | 2,345 | 8,209 | 0.0176 | 0.0540% |
| 5 | 2 | 50% (20 of 40) | 5 min 13 s | 19.97/s | 1,990 | 7,212 | 0.0212 | 0.0549% |
| 6 | 2 | 10% (4 of 40) | 5 min 14 s | 20.04/s | 2,804 | 9,125 | 0.0248 | 0.0613% |

Each strategy (P&L in all, and per bot):

| Run | Market-maker fill | Market-maker P&L | Noise P&L | Momentum P&L | Refused |
| --- | --- | --- | --- | --- | --- |
| 1 | 48.6% | -1,139.93 (-284.98 a bot) | -7,790.35 (-288.53 a bot) | -2,589.72 (-287.75 a bot) | 0 |
| 2 | 45.9% | -2,547.05 (-212.25 a bot) | -4,503.21 (-214.44 a bot) | -1,488.64 (-212.66 a bot) | 0 |
| 3 | 40.1% | -2,060.50 (-103.02 a bot) | -1,521.27 (-101.42 a bot) | -515.52 (-103.10 a bot) | 0 |
| 4 | 43.7% | -2,758.58 (-229.88 a bot) | -4,891.97 (-232.95 a bot) | -1,609.96 (-229.99 a bot) | 1 |
| 5 | 38.2% | -2,625.95 (-131.30 a bot) | -1,932.88 (-128.86 a bot) | -654.12 (-130.82 a bot) | 2 |
| 6 | 48.8% | -907.27 (-226.82 a bot) | -6,285.12 (-232.78 a bot) | -2,056.70 (-228.52 a bot) | 2 |

By share, the mean over the two seeds and their range:

| Market makers | Trades | Average spread | Volatility | Market-maker P&L a bot |
| --- | --- | --- | --- | --- |
| 10% | 2,826 (2,804 to 2,847) | 0.0228 (0.0209 to 0.0248) | 0.0775% (0.0613% to 0.0937%) | -255.90 (-284.98 to -226.82) |
| 30% | 2,387 (2,345 to 2,429) | 0.0212 (0.0176 to 0.0247) | 0.0560% (0.0540% to 0.0580%) | -221.07 (-229.88 to -212.25) |
| 50% | 2,048 (1,990 to 2,106) | 0.0206 (0.0200 to 0.0212) | 0.0524% (0.0499% to 0.0549%) | -117.16 (-131.30 to -103.02) |

What it shows, in this model and these six runs:

- The total rate held: every run achieved 19.9 to 20.1 commands a second, so the shares were compared at the same load.
- With more market makers, and so fewer noise and momentum bots, there were fewer trades (about 2,800 at 10%, 2,400 at 30%, 2,050 at 50%), and a smaller share of the market makers' quoted quantity filled (48.7% at 10%, 39.2% at 50%).
- One-minute volatility was lower with more market makers (0.078% at 10%, 0.052% at 50%), though the two 10% runs differed widely.
- The average spread hardly moved (0.021 to 0.023 a share), and the seeds' ranges overlap at every share.
- P&L is not a strategy's skill here. Within a run every strategy lost about the same per bot, because each bot holds 1,000 shares in all, 200 of each of the five instruments, and the estimate values them at the marks: it mostly measures how far prices fell during the run, and the trades between bots largely cancel out.

Two seeds a share are few, and each run inherits the books and inventories the earlier runs left: these are descriptions of what the runs did, not effects measured with confidence.

Definitions. Ran for: from the run's start to when its worker confirmed the stop, which ends its window, the bots' loading and the stop included. Achieved rate: the placements and cancels answered in the window over that time. Average spread and volatility: from Market Data's book at the end of each minute of the window, averaged over the five instruments (all five had values in every run); volatility is the standard deviation of one-minute returns of the last price, not annualised. Market-maker fill: the share of the quantity the market makers' orders accepted in the window asked for that filled in the window. P&L: an estimate, the cash change plus the change in holdings valued at the marks. Refused: the commands the exchange recorded a refusal for; a command refused before it recorded anything, such as for its rate, isn't counted.
