"""How a population of bots is split between the strategies when it is provisioned."""

# In the order they are provisioned: runs that assign bots by creation order take market makers
# first, then noise, then momentum, so the tags and those runs agree.
STRATEGIES = ("MARKET_MAKER", "NOISE", "MOMENTUM")


def strategy_split(bots: int) -> dict[str, int]:
    """A fifth each to market makers and momentum, rounded down, and the rest noise, as the
    operator screen suggests for a run (20 bots give 4, 12 and 4). There is always at least one
    market maker, so two or more bots include a market maker and a noise bot, and a single bot is a
    market maker. Every strategy is listed, in provisioning order, even with no bots."""
    if bots < 0:
        raise ValueError(f"a population cannot be negative: {bots}")
    fifth = bots // 5
    makers = max(fifth, 1) if bots > 0 else 0
    return {"MARKET_MAKER": makers, "NOISE": bots - makers - fifth, "MOMENTUM": fifth}


def split_text(split: dict[str, int]) -> str:
    """'4 market makers, 12 noise, 4 momentum', leaving out strategies with no bots."""
    makers = split["MARKET_MAKER"]
    parts = [
        f"{makers} market maker{'' if makers == 1 else 's'}" if makers else None,
        f"{split['NOISE']} noise" if split["NOISE"] else None,
        f"{split['MOMENTUM']} momentum" if split["MOMENTUM"] else None,
    ]
    return ", ".join(part for part in parts if part) or "no bots"
