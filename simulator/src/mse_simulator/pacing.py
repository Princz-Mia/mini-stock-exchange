"""A run's command rate, counted in commands rather than in decisions.

A bot's step reads its balances and orders, then its strategy decides. Often the decision is to
wait. If the rate paced steps, every waiting decision would spend a command's share and the run
would send fewer commands than it was configured for. So steps spend command credit, and a step
that sends nothing gives its credit back.

A refund would let steps repeat without limit while bots have nothing to do, each costing two
reads, so steps have a budget of their own that is never refunded: a few per command.
"""

STEPS_PER_COMMAND = 3

# Idle time saves up at most this much credit, so a stall (every bot busy, a pause) is followed by
# a short catch-up, not a whole second of commands at once. A slow run still saves a second's
# worth, up to this many commands, or waiting steps would make it fall short of its rate.
BURST_SECONDS = 0.25
SLOW_RUN_SAVES = 4.0


class Pacer:
    """Command and step credit that accrue with the clock, up to a quarter second's worth (a
    second's, for a slow run) plus the one in hand, so a refund at the cap is never lost."""

    def __init__(self, commands_per_second: float, now: float) -> None:
        if commands_per_second <= 0:
            raise ValueError("a run needs a positive rate")
        self._rate = commands_per_second
        saved = max(commands_per_second * BURST_SECONDS, min(commands_per_second, SLOW_RUN_SAVES))
        self._commands_cap = saved + 1
        self._steps_cap = (saved + 1) * STEPS_PER_COMMAND
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

    def charge(self) -> None:
        """A step sent a second command: its credit is spent too, below zero if it must be, so
        the steps after it wait until the rate has paid for it."""
        self._commands -= 1
