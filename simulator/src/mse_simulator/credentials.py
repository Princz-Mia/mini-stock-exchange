"""Bot tokens from the secret directory the exchange writes at provisioning."""

import logging
from pathlib import Path
from uuid import UUID

logger = logging.getLogger(__name__)

SUFFIX = ".token"
PREFIX = "mse_bot_"


def load_tokens(directory: Path) -> dict[UUID, str]:
    """Account ID to token, one ``<accountId>.token`` file per bot.

    Anything else in the directory is skipped. A missing directory means no bots yet. Tokens are
    never logged.
    """
    tokens: dict[UUID, str] = {}
    try:
        files = sorted(directory.iterdir())
    except FileNotFoundError:
        return tokens
    for file in files:
        if file.suffix != SUFFIX:
            continue
        try:
            account = UUID(file.stem)
        except ValueError:
            continue
        token = file.read_text(encoding="ascii").strip()
        if not token.startswith(PREFIX):
            logger.warning("skipping a malformed token file for account %s", account)
            continue
        tokens[account] = token
    return tokens
