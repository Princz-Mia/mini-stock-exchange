"""A small exchange in memory, for running the real strategies against each other without a
network: price-time priority, fills at the resting order's price, self-trade prevention, and cash
and shares held for open orders. It is not the exchange; it is enough of one for a test to see where
prices go over tens of thousands of orders."""

from collections.abc import Callable
from dataclasses import dataclass, field
from uuid import UUID

from mse_simulator.market import Instrument, MarketView
from mse_simulator.strategy import Cancel, Holdings, OpenOrder, Place, Strategy

MAX_PRICE = 100_000_000


@dataclass
class Resting:
    order_id: str
    account: int
    instrument: UUID
    side: str
    limit: int
    working: int
    created_at: float


@dataclass
class Account:
    strategy: Strategy
    cash: int
    shares: dict[UUID, int]
    cash_held: int = 0
    shares_held: dict[UUID, int] = field(default_factory=dict)
    orders: dict[str, Resting] = field(default_factory=dict)
    placed: int = 0
    refused: int = 0


@dataclass
class Book:
    reference: int
    # Best first: bids by price down, asks by price up, and the older order first at a price.
    bids: list[Resting] = field(default_factory=list)
    asks: list[Resting] = field(default_factory=list)
    last: int | None = None


class Passive:
    """An account that only holds what was placed for it: a person who placed orders and left."""

    def decide(self, *_) -> None:
        return None


class ToyMarket:
    """Bots take turns, market makers first, at ``rate`` commands a second of simulated time. As in
    the simulator, every bot sees the same market view, refreshed once per poll, while its own
    balances and orders are current."""

    def __init__(self, references: list[int], poll_seconds: float = 1.0) -> None:
        self.books = {
            UUID(int=index + 1): Book(reference) for index, reference in enumerate(references)
        }
        self.accounts: list[Account] = []
        self.now = 0.0
        self.trades = 0
        self.self_trades = 0
        # One entry per poll and instrument: (bid, ask, last).
        self.history: list[dict[UUID, tuple[int | None, int | None, int | None]]] = []
        self.on_poll: list[Callable[[MarketView], None]] = []
        self.on_place: list[Callable[[Account, Place, MarketView], None]] = []
        self._poll_seconds = poll_seconds
        self._next_poll = 0.0
        self._next_bot = 0
        self._next_order = 0
        self._view = self._snapshot()

    def join(self, strategy: Strategy, cash: int, shares: int) -> Account:
        account = Account(strategy, cash, {instrument: shares for instrument in self.books})
        self.accounts.append(account)
        return account

    def rest(self, account: Account, intent: Place) -> None:
        """Places an order for an account outside the turns, as a person's order would be: to set
        up a book before the bots start."""
        self._place(self.accounts.index(account), account, intent)

    def run(self, commands: int, rate: float) -> None:
        for _ in range(commands):
            self.now += 1.0 / rate
            while self.now >= self._next_poll:
                self._poll()
                self._next_poll += self._poll_seconds
            self._command()

    def price(self, instrument: UUID) -> float:
        """Where the instrument trades now: the mid, else the last trade, else the reference."""
        book = self.books[instrument]
        if book.bids and book.asks:
            return (book.bids[0].limit + book.asks[0].limit) / 2
        return float(book.last if book.last is not None else book.reference)

    def _snapshot(self) -> MarketView:
        return MarketView(
            state="OPEN",
            live=True,
            instruments=tuple(
                Instrument(
                    instrument_id=instrument,
                    symbol=f"T{instrument.int}",
                    tick=1,
                    reference=book.reference,
                    last=book.last,
                    bid=book.bids[0].limit if book.bids else None,
                    ask=book.asks[0].limit if book.asks else None,
                    max_price=MAX_PRICE,
                    min_quantity=1,
                    max_quantity=10_000,
                )
                for instrument, book in self.books.items()
            ),
            fetched_at=self.now,
        )

    def _poll(self) -> None:
        self._view = self._snapshot()
        self.history.append(
            {item.instrument_id: (item.bid, item.ask, item.last) for item in self._view.instruments}
        )
        for observer in self.on_poll:
            observer(self._view)

    def _command(self) -> None:
        """The next bot with something to do does it; a bot with nothing to do passes its turn."""
        for _ in range(len(self.accounts)):
            index = self._next_bot % len(self.accounts)
            self._next_bot += 1
            account = self.accounts[index]
            holdings = Holdings(
                cash=account.cash - account.cash_held,
                shares={
                    instrument: owned - account.shares_held.get(instrument, 0)
                    for instrument, owned in account.shares.items()
                },
                positions=dict(account.shares),
            )
            orders = [
                OpenOrder(o.order_id, o.created_at, o.instrument, o.side, o.working, o.limit)
                for o in account.orders.values()
            ]
            intent = account.strategy.decide(self._view, True, holdings, orders, self.now)
            if isinstance(intent, Cancel):
                self._cancel(account, intent.order_id)
                return
            if isinstance(intent, Place):
                for observer in self.on_place:
                    observer(account, intent, self._view)
                self._place(index, account, intent)
                return

    def _cancel(self, account: Account, order_id: str) -> None:
        order = account.orders.pop(order_id, None)
        if order is None:
            return
        book = self.books[order.instrument]
        (book.bids if order.side == "BUY" else book.asks).remove(order)
        self._release(account, order, order.working)

    @staticmethod
    def _release(account: Account, order: Resting, quantity: int) -> None:
        if order.side == "BUY":
            account.cash_held -= order.limit * quantity
        else:
            account.shares_held[order.instrument] -= quantity

    def _place(self, index: int, account: Account, intent: Place) -> None:
        book = self.books[intent.instrument_id]
        buying = intent.side == "BUY"
        if buying:
            enough = intent.limit * intent.quantity <= account.cash - account.cash_held
        else:
            held = account.shares_held.get(intent.instrument_id, 0)
            enough = intent.quantity <= account.shares[intent.instrument_id] - held
        if not enough or not 1 <= intent.limit <= MAX_PRICE or intent.quantity < 1:
            account.refused += 1
            return
        account.placed += 1
        remaining = intent.quantity
        opposite = book.asks if buying else book.bids
        while remaining and opposite:
            maker = opposite[0]
            if (maker.limit > intent.limit) if buying else (maker.limit < intent.limit):
                break
            if maker.account == index:
                # As the exchange does: the rest of the incoming order is cancelled, not the maker.
                self.self_trades += 1
                return
            quantity = min(remaining, maker.working)
            other = self.accounts[maker.account]
            buyer, seller = (account, other) if buying else (other, account)
            buyer.cash -= maker.limit * quantity
            seller.cash += maker.limit * quantity
            buyer.shares[intent.instrument_id] += quantity
            seller.shares[intent.instrument_id] -= quantity
            self._release(other, maker, quantity)
            maker.working -= quantity
            remaining -= quantity
            book.last = maker.limit
            self.trades += 1
            if maker.working == 0:
                opposite.pop(0)
                del other.orders[maker.order_id]
        if not remaining:
            return
        self._next_order += 1
        order = Resting(
            str(self._next_order),
            index,
            intent.instrument_id,
            intent.side,
            intent.limit,
            remaining,
            self.now,
        )
        account.orders[order.order_id] = order
        own = book.bids if buying else book.asks
        # Behind every order at the same price: time priority.
        place = len(own)
        for position, resting in enumerate(own):
            if (resting.limit < order.limit) if buying else (resting.limit > order.limit):
                place = position
                break
        own.insert(place, order)
        if buying:
            account.cash_held += order.limit * remaining
        else:
            account.shares_held[order.instrument] = (
                account.shares_held.get(order.instrument, 0) + remaining
            )
