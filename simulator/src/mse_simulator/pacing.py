"""A run's command rate, counted in commands rather than in decisions.

A bot's step reads its balances and orders, then its strategy decides. Often the decision is to
wait. If the rate paced steps, every waiting decision would spend a command's share and the run
would send fewer commands than it was configured for. So steps spend command credit, and a step
that sends nothing gives its credit back.

A refund would let steps repeat without limit while bots have nothing to do, each costing two
reads, so steps have a budget of their own that is never refunded: a few per command.
"""

STEPS_PER_COMMAND = 3


class Pacer:
    """Command and step credit that accrue with the clock, up to one second's worth each plus the
    one in hand, so a refund at the cap is never lost."""

    def __init__(self, commands_per_second: float, now: float) -> None:
        if commands_per_second <= 0:
            raise ValueError("a run needs a positive rate")
        self._rate = commands_per_second
        self._commands_cap = commands_per_second + 1
        self._steps_cap = (commands_per_second + 1) * STEPS_PER_COMMAND
        # One command at once, so a run starts trading without waiting for credit to build up.
        self._commands = 1.0
        self._steps = float(STEPS_PER_COMMAND)
        self._at = now

    def accrue(self, now: float) -> None:
        elapsed = max(0.0, now - self._at)
        self._at = now
        self._commands = min(self._commands_cap, self._commands + elapsed * self._rate)
        self._steps = min(self._steps_cap, self._steps + elapsed * self._rate * STEPS_PER_COMMAND)

    def take(self) -> bool:
        """Spends a command's and a step's credit for one step, if both are there."""
        if self._commands < 1 or self._steps < 1:
            return False
        self._commands -= 1
        self._steps -= 1
        return True

    def refund(self) -> None:
        """A step sent nothing: its command credit returns, its step credit does not."""
        self._commands = min(self._commands_cap, self._commands + 1)
