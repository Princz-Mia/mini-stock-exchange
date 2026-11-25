"""What a market of the three strategies looks like over hours, with the settings a run really
uses: the demo's population and instruments in the toy market, built the way the runner builds a
run's bots."""

import math
from collections.abc import Callable
from functools import cache
from uuid import UUID, uuid4

import httpx
import numpy as np
import pytest
from toy_market import Account, Passive, ToyMarket

from mse_simulator.control import Assignment, Totals
from mse_simulator.market import MarketView
from mse_simulator.market_maker import MarketMaker
from mse_simulator.momentum import MomentumBot, PriceSignal
from mse_simulator.prices import BASIS_POINT
from mse_simulator.runner import Runner
from mse_simulator.settings import Settings
from mse_simulator.strategy import Place
from mse_simulator.values import FairValues

# The demo market: NOVA, ORBT, HELI, QUAD and VELO, with 4 market makers, 12 noise and 4 momentum
# bots, each with 100,000.00 and 1,000 shares of every instrument, at 4 commands a second.
REFERENCES = [10_000, 5_000, 20_000, 2_500, 8_000]
POPULATION = ["MARKET_MAKER"] * 4 + ["NOISE"] * 12 + ["MOMENTUM"] * 4
SETTINGS = Settings(execution_url="http://execution:8080", market_data_url="http://market:8080")
# The first minutes are left out of the figures: the book fills and the momentum window loads.
WARM_UP = 120


class Run:
    """One seeded run in the toy market, with what the tests look at."""

    def __init__(
        self,
        seed: int,
        commands: int,
        start: float = 1.0,
        references: list[int] = REFERENCES,
        population: list[str] = POPULATION,
        setup: Callable[[ToyMarket], None] | None = None,
        **settings,
    ) -> None:
        self.settings = SETTINGS.model_copy(update=settings)
        runner = Runner(self.settings, httpx.AsyncClient())
        run = Assignment(
            run_id=uuid4(),
            desired_state="RUNNING",
            seed=seed,
            commands_per_second=4.0,
            max_order_quantity=10,
            max_position=100,
            lease_seconds=30,
            instrument_ids=frozenset(),
            bots=(),
            totals=Totals(),
        )
        runner.start_run(run)
        self.market = ToyMarket(references)
        for book in self.market.books.values():
            if start != 1.0:
                book.last = round(book.reference * start)
        values: FairValues = runner._values
        self.fair: list[dict[UUID, float]] = []
        self.market.on_poll += [runner._signal.observe, values.observe, self._record(values)]
        # Every momentum order with the market view its bot saw.
        self.momentum_orders: list[tuple[Place, MarketView]] = []
        self.market.on_place.append(self._watch)
        # Every fill's price by instrument.
        self.fills: dict[UUID, list[int]] = {instrument: [] for instrument in self.market.books}
        self.market.on_trade.append(lambda instrument, price: self.fills[instrument].append(price))
        for index, name in enumerate(population):
            strategy = runner._strategy(name, np.random.default_rng([seed, index + 1]), run)
            self.market.join(strategy, cash=10_000_000, shares=1_000)
        if setup is not None:
            setup(self.market)
        self.market.run(commands, rate=4.0)

    def _record(self, values: FairValues):
        return lambda view: self.fair.append(
            {item.instrument_id: values.of(item.instrument_id) for item in view.instruments}
        )

    def _watch(self, account: Account, intent: Place, view: MarketView) -> None:
        if isinstance(account.strategy, MomentumBot):
            self.momentum_orders.append((intent, view))

    def polls(self):
        """(instrument, bid, ask, last, fair value) for every poll after the warm-up."""
        for quotes, fair in zip(self.market.history[WARM_UP:], self.fair[WARM_UP:], strict=True):
            for instrument, (bid, ask, last) in quotes.items():
                yield instrument, bid, ask, last, fair[instrument]


@cache
def demo(seed: int) -> Run:
    """About three hours of the demo market: 40,000 commands."""
    return Run(seed, commands=40_000)


SEEDS = (1, 2, 3)


def test_prices_follow_their_values_and_never_run_away() -> None:
    """In two weeks of the old strategies every instrument lost 70 to 99%. Here each price stays
    within 2.5% of its instrument's fair value at every poll, and the fair value within a few
    percent of the reference, so no price leaves a band around where it started."""
    for seed in SEEDS:
        run = demo(seed)
        assert run.market.trades > 8_000
        gaps = [
            abs(math.log((bid + ask) / 2 / fair))
            for _, bid, ask, _, fair in run.polls()
            if bid is not None and ask is not None
        ]
        assert max(gaps) < 0.025, (seed, max(gaps))
        assert sum(gaps) / len(gaps) < 0.004
        for instrument, book in run.market.books.items():
            traded = [last for item, _, _, last, _ in run.polls() if item == instrument and last]
            assert 0.85 * book.reference < min(traded) and max(traded) < 1.15 * book.reference


def test_bots_disagree_a_little_about_the_values() -> None:
    """Each market maker and noise bot sees the values through a bias of its own, within the set
    spread, so they do not all want the same side at the same price."""
    run = demo(1)
    biases = [
        account.strategy._view._bias
        for account in run.market.accounts
        if not isinstance(account.strategy, MomentumBot)
    ]
    spread = run.settings.view_spread_bp * BASIS_POINT
    assert len(biases) == 16 and len(set(biases)) == 16
    assert all(abs(bias) <= spread for bias in biases)
    assert min(biases) < -spread / 2 and max(biases) > spread / 2


def test_prices_move_both_ways() -> None:
    """A market that does not fall should not only rise either: over the seeds and instruments,
    about as many end above their reference as below."""
    ends = [
        run.market.price(instrument) / book.reference - 1
        for run in map(demo, SEEDS)
        for instrument, book in run.market.books.items()
    ]
    risen = sum(change > 0 for change in ends)
    assert 4 <= risen <= len(ends) - 4, ends
    assert abs(sum(ends) / len(ends)) < 0.02
    assert max(abs(change) for change in ends) > 0.005, "and they do move"


def test_a_market_maker_keeps_both_sides_of_its_instrument_quoted() -> None:
    """Four makers take four of the five instruments, one each. At 4 commands a second a bot gets
    a turn every five seconds, so a quote that was taken is back within a few polls: an instrument
    with a maker shows both sides in at least two polls of three."""
    for seed in SEEDS:
        run = demo(seed)
        made = {
            account.strategy._instrument
            for account in run.market.accounts
            if isinstance(account.strategy, MarketMaker)
        }
        assert len(made) == 4
        for instrument in made:
            sides = [
                bid is not None and ask is not None
                for item, bid, ask, _, _ in run.polls()
                if item == instrument
            ]
            assert sum(sides) / len(sides) > 0.66, (seed, instrument)


def test_a_cheap_instrument_trades_like_a_dear_one() -> None:
    """Spreads are a share of the price: QUAD at 25.00 and HELI at 200.00 show about the same
    spread in basis points. With spreads in ticks QUAD's was eight times HELI's."""
    for seed in SEEDS:
        run = demo(seed)
        spreads: dict[UUID, list[float]] = {instrument: [] for instrument in run.market.books}
        for instrument, bid, ask, _, _ in run.polls():
            if bid is not None and ask is not None:
                spreads[instrument].append((ask - bid) / ((bid + ask) / 2) / BASIS_POINT)
        average = {instrument: sum(seen) / len(seen) for instrument, seen in spreads.items()}
        assert max(average.values()) < 2 * min(average.values()), average
        assert all(5 < spread < 40 for spread in average.values()), average


def test_momentum_is_a_small_part_of_the_flow_and_stays_near_the_price() -> None:
    for seed in SEEDS:
        run = demo(seed)
        placed = sum(account.placed + account.refused for account in run.market.accounts)
        share = len(run.momentum_orders) / placed
        assert 0.02 < share < 0.12, "there, and a small part: a fifth of the bots"
        reach = run.settings.momentum_reach_bp * BASIS_POINT
        narrow = run.settings.narrow_book_bp * BASIS_POINT
        for intent, view in run.momentum_orders:
            item = next(i for i in view.instruments if i.instrument_id == intent.instrument_id)
            if intent.side == "BUY":
                assert intent.limit <= item.anchor(narrow) * (1 + reach)
            else:
                assert intent.limit >= item.anchor(narrow) * (1 - reach)
        sides = [intent.side for intent, _ in run.momentum_orders]
        assert 0.35 < sides.count("BUY") / len(sides) < 0.65, "it joins both sides"


def test_an_ordinary_wander_of_the_value_is_rarely_a_trend() -> None:
    """The windows and the threshold are set against the fair value's own movement: left to
    itself, it reads as a trend in fewer than one poll in ten. At the old threshold of 0.1% it
    was one in three, so momentum bots were nearly always following something."""
    settings = SETTINGS
    trending = polls = 0
    for seed in range(10):
        values = FairValues(
            seed,
            settings.fair_value_half_life_hours,
            settings.fair_value_volatility,
            settings.market_poll_seconds,
            settings.narrow_book_bp,
        )
        signal = PriceSignal(settings.momentum_short_polls, settings.momentum_long_polls)
        market = ToyMarket([10_000])
        nova = UUID(int=1)
        for _ in range(3_600):
            view = market._snapshot()
            values.observe(view)
            # The market following the value exactly: the last trade at the value.
            market.books[nova].last = round(values.of(nova))
            signal.observe(market._snapshot())
            strength = signal.strength(nova)
            if strength is not None:
                polls += 1
                trending += abs(strength) >= settings.momentum_threshold
    assert 0.005 < trending / polls < 0.10, trending / polls


def far_orders(market: ToyMarket) -> None:
    """Before the run, two people each leave one share on every instrument: a bid at a hundredth
    of its reference and an offer at five times it. For NOVA, 1.00 and 500.00 from 100.00."""
    buyer = market.join(Passive(), cash=10_000_000, shares=1_000)
    seller = market.join(Passive(), cash=10_000_000, shares=1_000)
    for instrument, book in market.books.items():
        market.rest(buyer, Place(instrument, "BUY", 1, book.reference // 100))
        market.rest(seller, Place(instrument, "SELL", 1, book.reference * 5))


GATE_PAIR = {"references": [10_000], "population": ["MARKET_MAKER", "NOISE"]}


def test_one_far_order_on_each_side_does_not_move_a_run() -> None:
    """The gate's case: one maker and one noise bot on NOVA, whose book holds only the two far
    orders. Their mid of 250.50 is no price: the value starts at the reference and the market
    trades within a few percent of it, as it does on every instrument of the demo market,
    VELO among them, which has no maker. When a side of the book near the price is taken, the
    far order is the best on that side for a moment; noise and momentum then go by the last
    trade, not by a mid halfway to the far order."""
    runs = [Run(1, commands=4_000, setup=far_orders, **GATE_PAIR)]
    runs += [Run(seed, commands=4_000, setup=far_orders) for seed in SEEDS]
    for run in runs:
        assert run.market.history[0][UUID(int=1)] == (100, 50_000, None)
        for instrument, book in run.market.books.items():
            assert run.fair[0][instrument] == pytest.approx(book.reference)
            prices = run.fills[instrument]
            assert len(prices) > 150
            assert max(abs(price / book.reference - 1) for price in prices) < 0.04
            assert all(abs(fair[instrument] / book.reference - 1) < 0.05 for fair in run.fair)
        # Nobody ever traded with a far order: each is still there, whole.
        people = [a for a in run.market.accounts if isinstance(a.strategy, Passive)]
        assert [len(a.orders) for a in people] == [len(run.market.books)] * 2
        assert all(order.working == 1 for a in people for order in a.orders.values())


def test_a_book_counted_as_narrow_however_wide_brings_the_climb_back() -> None:
    """With the setting at 20,000 basis points the far orders' mid is a price again: the value
    starts at 250.50 and the bots trade NOVA up toward it within minutes, as before."""
    run = Run(1, commands=4_000, setup=far_orders, narrow_book_bp=20_000, **GATE_PAIR)
    nova = UUID(int=1)
    assert run.fair[0][nova] == pytest.approx(25_050)
    assert max(run.fills[nova]) > 20_000


def test_a_collapsed_market_climbs_back_toward_its_reference() -> None:
    """A market left at a tenth of its reference prices, as the old strategies left one, is not
    stuck there: the values start where the market stands and close half of the gap, in
    proportion, every four hours. After about three hours each price is near a quarter of its
    reference."""
    run = Run(seed=4, commands=40_000, start=0.1)
    for instrument, book in run.market.books.items():
        assert 0.20 < run.market.price(instrument) / book.reference < 0.29
