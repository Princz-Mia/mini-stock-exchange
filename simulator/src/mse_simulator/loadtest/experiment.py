"""A small, repeatable experiment on the share of market makers at a fixed total rate: one bot run
per share and seed, one after another on a throwaway stack of its own, and a report of what each
run did, labelled simulation-model evidence.

From the repository root, with Docker running:

    uv run --project simulator python -m mse_simulator.loadtest.experiment

The stack is its own Compose project ("mse-experiment") on its own ports, with fresh random
passwords, apart from a development stack, a gate run and the load test. It is deleted afterwards
unless --keep-stack is given, which leaves it up so the runs can be opened in the operator's run
comparison. The exit code is 0 when every run ran, stopped and had its figures worked out.
"""

import argparse
import logging
import math
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from functools import partial
from pathlib import Path
from typing import Any

from mse_simulator.loadtest import manifests
from mse_simulator.loadtest.stack import (
    SYMBOLS,
    Ports,
    Session,
    Stack,
    activity,
    environment_lines,
    listing,
    read_environment,
    read_manifest,
    run_rate,
    set_up,
    start_run,
    stop_any_run,
    wait_for,
)
from mse_simulator.population import split_text

logger = logging.getLogger("mse_simulator.loadtest.experiment")

PROJECT = "mse-experiment"
# Apart from a development stack's ports, a gate run's and the load test's.
PORTS = Ports(web=38090, postgres=35442, kafka=39102, flink_ui=38091)
# Execution lets each bot send at most this many trading commands a second.
BOT_COMMANDS_PER_SECOND = 2
# Volatility is the spread of one-minute returns: three minutes give the two it needs at least.
SHORTEST_RUN = 180
# The longest the bots may take to load, a run's figures to be worked out, and Market Data to catch
# up with a run's window.
LOADING = 600
FIGURES = 120
CATCHING_UP = 120
# A run's last snapshots may reach Market Data a little after its window ends.
SETTLING = 60

EVIDENCE = (
    "**Simulation-model evidence.** These figures come from this exchange's own bots trading "
    "with each other in one local simulation. They show how this model behaves, not how any real "
    "market would."
)
SHARED = (
    "The runs took place one after another in one simulation. They share the bots' accounts, "
    "their cash and shares and the order books, and nothing is topped up between runs, so each "
    "run starts where the runs before it left off: order matters, and the runs are listed in the "
    "order they ran, the share order turned by one for each seed so that no share always goes "
    "first. A seed repeats each bot's decisions for the same observations, but live timing does "
    "not repeat, so two runs with the same seed and share differ too: the seeds show the spread "
    "between runs, not an exact repeat."
)


@dataclass
class Options:
    root: Path
    bots: int = 40
    rate: str = "20.00"
    duration: int = 300
    shares: tuple[Decimal, ...] = (Decimal("0.1"), Decimal("0.3"), Decimal("0.5"))
    seeds: tuple[int, ...] = (1, 2)
    keep_stack: bool = False


@dataclass
class Outcome:
    """One combination: what was asked for, and what came back."""

    index: int
    seed: int
    share: Decimal
    split: dict[str, int]
    run_id: str | None = None
    detail: dict | None = None
    quality: dict[str, dict | str] = field(default_factory=dict)
    problem: str | None = None

    @property
    def complete(self) -> bool:
        return (
            self.problem is None and self.detail is not None and self.detail["figures"] is not None
        )


def split(bots: int, share: Decimal) -> dict[str, int]:
    """The run's bots for a market-maker share: the market makers rounded half up, the rest three
    noise bots to one momentum bot, as the demo market and the load test tag them."""
    makers = int((Decimal(bots) * share).to_integral_value(rounding=ROUND_HALF_UP))
    rest = bots - makers
    return {"MARKET_MAKER": makers, "NOISE": rest - rest // 4, "MOMENTUM": rest // 4}


def population(bots: int, shares: tuple[Decimal, ...]) -> dict[str, int]:
    """The bots to tag by strategy so every run has its own: the most any share needs of each.
    A run takes the oldest bots tagged with each strategy, so runs share bots."""
    splits = [split(bots, share) for share in shares]
    return {strategy: max(each[strategy] for each in splits) for strategy in splits[0]}


def order(shares: tuple[Decimal, ...], seeds: tuple[int, ...]) -> list[tuple[int, Decimal]]:
    """Seed by seed, every share in turn, the shares turned by one for each seed."""
    combinations = []
    for index, seed in enumerate(seeds):
        turn = index % len(shares)
        combinations += [(seed, share) for share in (*shares[turn:], *shares[:turn])]
    return combinations


def run_detail(operator: Session, run_id: str) -> dict:
    response = operator.http.get(f"/api/v1/admin/bot-runs/{run_id}")
    response.raise_for_status()
    return response.json()


def run_one(operator: Session, outcome: Outcome, options: Options, minutes: int) -> None:
    """Starts the combination's run, lets it trade for the duration once its bots trade, stops it,
    and waits for its figures. The run's id is kept as soon as it starts."""
    started = start_run(operator, outcome.split, outcome.seed, options.rate, minutes)
    outcome.run_id = started["runId"]
    logger.info(
        "run %d: share %s (%s), seed %d: %s",
        outcome.index,
        outcome.share,
        split_text(outcome.split),
        outcome.seed,
        outcome.run_id,
    )
    wait_for("the run to be RUNNING", lambda: activity(operator)["status"] == "RUNNING", 180)
    wait_for("the bots to trade", lambda: activity(operator)["activity"]["placed"] > 0, LOADING)
    time.sleep(options.duration)
    operator.expect(200, f"/api/v1/admin/bot-runs/{outcome.run_id}/stop")
    wait_for("the run to stop", lambda: activity(operator)["status"] == "STOPPED", 600)
    outcome.detail = wait_for(
        "the run's figures",
        lambda: (detail := run_detail(operator, outcome.run_id))["figures"] is not None and detail,
        FIGURES,
    )


def caught_up(operator: Session, path: str) -> dict | None:
    """Market Data's figures for a window, once its feed has caught up or won't: live, stale (no
    snapshots coming) or off. None while it is still connecting, catching up or paused."""
    response = operator.http.get(path)
    response.raise_for_status()
    quality = response.json()
    return quality if quality["feed"]["state"] in ("LIVE", "STALE", "DISABLED") else None


def read_quality(operator: Session, outcome: Outcome) -> None:
    """Market Data's spread and volatility over the run's window, per instrument it traded, once
    Market Data has caught up with it; why not, where it could not be read."""
    assert outcome.detail is not None
    run = outcome.detail["run"]
    window = run["window"]
    for instrument in run["config"]["instruments"]:
        path = (
            f"/api/v1/instruments/{instrument['instrumentId']}/market-quality"
            f"?fromSequence={window['startSequence']}&toSequence={window['endSequence']}"
        )
        try:
            outcome.quality[instrument["symbol"]] = wait_for(
                f"Market Data to catch up with run {outcome.index}",
                partial(caught_up, operator, path),
                CATCHING_UP,
            )
        except RuntimeError as error:
            outcome.quality[instrument["symbol"]] = str(error)


def ended(outcome: Outcome) -> datetime | None:
    """When the run's worker confirmed its stop, which ends its window; None if it never did."""
    if outcome.detail is None or outcome.detail["run"]["stoppedAt"] is None:
        return None
    return datetime.fromisoformat(outcome.detail["run"]["stoppedAt"])


def run_all(operator: Session, outcomes: list[Outcome], options: Options, minutes: int) -> None:
    """Every combination in turn. A run that fails is stopped and the next one started; if it
    cannot be confirmed stopped, the rest are not run, as the next start would be refused or would
    end its window early."""
    for outcome in outcomes:
        try:
            run_one(operator, outcome, options, minutes)
        except Exception as error:  # noqa: BLE001 - the runs before it are still reported
            logger.exception("run %d could not be completed", outcome.index)
            outcome.problem = f"{type(error).__name__}: {error}"
            if not stop_any_run(operator):
                return


def run(options: Options) -> int:
    stack = Stack(options.root, PROJECT, "experiment", PORTS)
    combinations = order(options.shares, options.seeds)
    outcomes = [
        Outcome(index + 1, seed, share, split(options.bots, share))
        for index, (seed, share) in enumerate(combinations)
    ]
    logger.info("starting a clean stack as Compose project %s", PROJECT)
    stack.compose("down", "-v", "--remove-orphans", check=False)
    started_at = datetime.now(UTC)
    try:
        stack.compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", timeout=1800)
        operator = Session("operator", stack.passwords["operator"], stack.web_url)
        set_up(operator, population(options.bots, options.shares), stack.passwords["trader"])
        wait_for(
            "Market Data to list the instruments",
            lambda: len(listing(stack.web_url)["instruments"]) >= len(SYMBOLS),
            120,
        )
        # Ends by itself a little after the duration, should the experiment stop before stopping it.
        minutes = math.ceil((options.duration + LOADING) / 60) + 1
        run_all(operator, outcomes, options, minutes)
        last = max((end for o in outcomes if (end := ended(o)) is not None), default=None)
        if last is not None:
            time.sleep(max(0.0, SETTLING - (datetime.now(UTC) - last).total_seconds()))
        for outcome in outcomes:
            if outcome.detail is not None:
                read_quality(operator, outcome)
        env = read_environment(stack)
        readings = [
            (f"Run {o.index}", o.run_id, read_manifest(operator, o.run_id))
            for o in outcomes
            if o.run_id is not None
        ]
        text = render(
            options,
            outcomes,
            started_at,
            manifests.report(readings, str(env["commit"])),
            environment_lines(env),
        )
        path = stack.run_dir / f"experiment-{datetime.now(UTC):%Y%m%d-%H%M}.md"
        path.write_text(text, encoding="utf-8")
        print(text)
        logger.info("report written to %s", path)
        return 0 if all(outcome.complete for outcome in outcomes) else 1
    finally:
        if options.keep_stack:
            logger.info(
                "stack kept: %s, the operator's password in %s; docker compose -p %s",
                stack.web_url,
                stack.env_file,
                PROJECT,
            )
        else:
            stack.compose("down", "-v", "--remove-orphans", check=False)


@dataclass(frozen=True)
class Figures:
    """What a completed run did, as the report shows it."""

    seconds: float
    achieved_rate: float
    trades: int
    volume: int
    spread: Decimal | None
    spread_instruments: int
    volatility: Decimal | None
    volatility_instruments: int
    instruments: int
    fill: dict[str, Decimal | None]
    pnl: dict[str, Decimal]
    bots: dict[str, int]
    refused: int

    def pnl_per_bot(self, strategy: str) -> Decimal | None:
        """A strategy's P&L shared over its bots: their number differs with the share."""
        if strategy not in self.pnl:
            return None
        return self.pnl[strategy] / self.bots[strategy]


def _mean(values: list[Decimal]) -> Decimal | None:
    return sum(values, Decimal(0)) / len(values) if values else None


def figures(outcome: Outcome) -> Figures | None:
    """A completed run's figures; None for a run that did not complete."""
    end = ended(outcome)
    if not outcome.complete or end is None:
        return None
    assert outcome.detail is not None
    run, worked = outcome.detail["run"], outcome.detail["figures"]
    seconds = max((end - datetime.fromisoformat(run["startedAt"])).total_seconds(), 1.0)
    commands = sum(each["placements"] + each["cancels"] for each in worked["strategies"])
    read = [quality for quality in outcome.quality.values() if isinstance(quality, dict)]
    spreads = [Decimal(q["averageSpread"]) for q in read if q["averageSpread"] is not None]
    volatilities = [Decimal(q["volatility"]) for q in read if q["volatility"] is not None]
    return Figures(
        seconds=seconds,
        achieved_rate=commands / seconds,
        trades=sum(each["trades"] for each in worked["instruments"]),
        volume=sum(each["volume"] for each in worked["instruments"]),
        spread=_mean(spreads),
        spread_instruments=len(spreads),
        volatility=_mean(volatilities),
        volatility_instruments=len(volatilities),
        instruments=len(run["config"]["instruments"]),
        fill={
            each["strategy"]: (
                Decimal(each["filledQuantity"]) / each["placedQuantity"]
                if each["placedQuantity"]
                else None
            )
            for each in worked["strategies"]
        },
        pnl={each["strategy"]: Decimal(each["pnl"]) for each in worked["strategies"]},
        bots={each["strategy"]: each["bots"] for each in worked["strategies"]},
        refused=sum(r["commands"] for each in worked["strategies"] for r in each["refusals"]),
    )


def _duration(seconds: float) -> str:
    minutes, rest = divmod(round(seconds), 60)
    return f"{minutes} min {rest} s" if minutes else f"{rest} s"


def _percent_of(share: Decimal) -> str:
    """A share as a percentage, as written: 0.1 is "10%", 0.125 is "12.5%"."""
    return f"{(share * 100).normalize():f}%"


def _share(outcome: Outcome) -> str:
    makers = outcome.split["MARKET_MAKER"]
    return f"{_percent_of(outcome.share)} ({makers} of {sum(outcome.split.values())})"


def _spread(value: Decimal | None) -> str:
    return "—" if value is None else f"{value:.4f}"


def _volatility(value: Decimal | None) -> str:
    return "—" if value is None else f"{value * 100:.4f}%"


def _money(value: Decimal | None) -> str:
    return "—" if value is None else f"{value:+,.2f}"


def _pnl(each: Figures, strategy: str) -> str:
    """A strategy's P&L in all, and for each of its bots: "+10.00 (+2.50 a bot)"."""
    if strategy not in each.pnl:
        return "—"
    return f"{_money(each.pnl[strategy])} ({_money(each.pnl_per_bot(strategy))} a bot)"


def _fill(value: Decimal | None) -> str:
    return "—" if value is None else f"{value * 100:.1f}%"


def _count(value: Decimal | None) -> str:
    return "—" if value is None else f"{value:,.0f}"


def _range(values: list[Decimal], text: Callable[[Decimal | None], str]) -> str:
    """The mean, and the range when the runs differ: "0.0400 (0.0300 to 0.0500)"."""
    if not values:
        return "—"
    mean = sum(values, Decimal(0)) / len(values)
    low, high = min(values), max(values)
    return text(mean) if low == high else f"{text(mean)} ({text(low)} to {text(high)})"


def _cell(text: str) -> str:
    """Text as one Markdown table cell: on one line, with its bars escaped."""
    return " ".join(text.split()).replace("|", "\\|")


def render(
    options: Options,
    outcomes: list[Outcome],
    started_at: datetime,
    simulator: list[str],
    environment: list[str],
) -> str:
    """The report: the label, the settings, every run in the order it ran, each strategy, the
    runs grouped by share, then the simulator and the environment."""
    worked = [(outcome, figures(outcome)) for outcome in outcomes]
    tagged = population(options.bots, options.shares)
    lines = [
        f"# Market-maker share experiment: {started_at:%Y-%m-%d %H:%M} UTC",
        "",
        EVIDENCE,
        "",
        SHARED,
        "",
        f"- {options.bots} bots in every run, at {options.rate} commands a second in all, each "
        f"run stopped {_duration(options.duration)} after its bots began trading.",
        "- Shares of market makers: "
        + ", ".join(_percent_of(share) for share in options.shares)
        + "; seeds: "
        + ", ".join(str(seed) for seed in options.seeds)
        + ". The bots that are not market makers are three noise bots to one momentum bot.",
        f"- {len(SYMBOLS)} instruments at 100.00 on a fresh stack; {split_text(tagged)} tagged, "
        "each with 100,000.00 cash and 200 shares of each instrument. A run takes the oldest "
        "bots tagged with each strategy, so the runs share bots.",
        "",
        "## Runs, in the order they ran",
        "",
        "| Run | Seed | Market makers | Ran for | Achieved rate | Trades | Volume "
        "| Average spread | Volatility |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for outcome, each in worked:
        if each is None:
            reason = outcome.problem or "not run: an earlier run could not be confirmed stopped"
            lines.append(
                f"| {outcome.index} | {outcome.seed} | {_share(outcome)} | {_cell(reason)} "
                "| | | | | |"
            )
            continue
        lines.append(
            f"| {outcome.index} | {outcome.seed} | {_share(outcome)} | {_duration(each.seconds)} "
            f"| {each.achieved_rate:.2f}/s | {each.trades:,} | {each.volume:,} "
            f"| {_spread(each.spread)} ({each.spread_instruments} of {each.instruments}) "
            f"| {_volatility(each.volatility)} ({each.volatility_instruments} of "
            f"{each.instruments}) |"
        )
    lines += [
        "",
        "## Each strategy",
        "",
        "| Run | Market-maker fill | Market-maker P&L | Noise P&L | Momentum P&L | Refused |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for outcome, each in worked:
        if each is not None:
            lines.append(
                f"| {outcome.index} | {_fill(each.fill.get('MARKET_MAKER'))} "
                f"| {_pnl(each, 'MARKET_MAKER')} | {_pnl(each, 'NOISE')} "
                f"| {_pnl(each, 'MOMENTUM')} | {each.refused:,} |"
            )
    lines += [
        "",
        "## By share of market makers",
        "",
        "The mean over the runs at each share, and the range when they differ.",
        "",
        "| Market makers | Runs | Trades | Average spread | Volatility | Market-maker P&L a bot |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for share in options.shares:
        done = [each for outcome, each in worked if outcome.share == share and each is not None]
        trades = [Decimal(each.trades) for each in done]
        spreads = [each.spread for each in done if each.spread is not None]
        volatilities = [each.volatility for each in done if each.volatility is not None]
        pnl = [p for each in done if (p := each.pnl_per_bot("MARKET_MAKER")) is not None]
        lines.append(
            f"| {_percent_of(share)} | {len(done)} | {_range(trades, _count)} "
            f"| {_range(spreads, _spread)} | {_range(volatilities, _volatility)} "
            f"| {_range(pnl, _money)} |"
        )
    lines += [
        "",
        "Ran for: from the run's start to when its worker confirmed the stop, which ends its "
        "window, the bots' loading and the stop included. Achieved rate: the placements and "
        "cancels answered in the window over that time. Average spread and volatility: from "
        "Market Data's book at the end of each minute of the window, averaged over the "
        "instruments that had one (how many in brackets); "
        "volatility is the standard deviation of one-minute returns of the last price, not "
        "annualised. Market-maker fill: the share of the quantity the market makers' orders "
        "accepted in the window asked for that filled in the window. P&L: an estimate, the cash "
        "change plus the change in holdings valued at the marks, for all the strategy's bots in "
        "the run and for each of them, as their number differs with the share. Refused: the "
        "commands the exchange recorded a refusal for; a command refused before it recorded "
        "anything, such as for its rate, isn't counted.",
        "",
        *simulator,
        *environment,
    ]
    return "\n".join(lines)


def _list(text: str, name: str, parse: Callable[[str], Any]) -> tuple:
    items = [item.strip() for item in text.split(",") if item.strip()]
    if not items:
        raise ValueError(f"--{name} lists nothing")
    try:
        values = tuple(parse(item) for item in items)
    except (ValueError, InvalidOperation) as error:
        raise ValueError(f"--{name} is not a list of numbers: {text!r}") from error
    if len(set(values)) != len(values):
        raise ValueError(f"--{name} lists a value twice: {text!r}")
    return values


def parse(argv: list[str]) -> Options:
    parser = argparse.ArgumentParser(
        prog="python -m mse_simulator.loadtest.experiment",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--bots", type=int, default=40, help="bots in every run")
    parser.add_argument("--rate", default="20.00", help="commands a second in all, such as 20")
    parser.add_argument(
        "--duration", type=int, default=300, help="seconds each run trades before it is stopped"
    )
    parser.add_argument("--shares", default="0.1,0.3,0.5", help="market-maker shares, 0 to 1")
    parser.add_argument("--seeds", default="1,2", help="seeds, each run once at every share")
    parser.add_argument("--keep-stack", action="store_true")
    args = parser.parse_args(argv)
    root = Path.cwd()
    try:
        if not (root / "compose.yaml").is_file():
            raise ValueError("run it from the repository root, where compose.yaml is")
        if args.bots < 1:
            raise ValueError("--bots must be at least 1")
        rate = run_rate(args.rate)
        if Decimal(rate) > BOT_COMMANDS_PER_SECOND * args.bots:
            raise ValueError(
                f"--rate {rate} is more than {args.bots} bots may send: each sends at most "
                f"{BOT_COMMANDS_PER_SECOND} commands a second"
            )
        if args.duration < SHORTEST_RUN:
            raise ValueError(
                f"--duration must be at least {SHORTEST_RUN} s, so that each run has the "
                "one-minute returns its volatility needs"
            )
        shares = _list(args.shares, "shares", Decimal)
        if any(not Decimal(0) <= share <= Decimal(1) for share in shares):
            raise ValueError(f"--shares must each be from 0 to 1: {args.shares!r}")
        makers: dict[int, Decimal] = {}
        for share in shares:
            count = split(args.bots, share)["MARKET_MAKER"]
            if count in makers:
                raise ValueError(
                    f"--shares {makers[count]} and {share} both give {count} market makers of "
                    f"{args.bots} bots"
                )
            makers[count] = share
        seeds = _list(args.seeds, "seeds", int)
        if any(seed < 0 for seed in seeds):
            raise ValueError(f"--seeds must not be negative: {args.seeds!r}")
    except ValueError as error:
        parser.error(str(error))
    return Options(root, args.bots, rate, args.duration, shares, seeds, args.keep_stack)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    sys.exit(run(parse(sys.argv[1:])))
