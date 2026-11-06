"""Bot tokens from the secret directory the exchange writes at provisioning."""

import logging
from pathlib import Path
from uuid import UUID

logger = logging.getLogger(__name__)

SUFFIX = ".token"
PREFIX = "mse_bot_"


def load_token(directory: Path, credential_ref: str) -> str | None:
    """One bot's token, by the file name its assignment gives.

    Only a bare ``<accountId>.token`` name is accepted, so a reference can't reach outside the
    directory. None if the file is missing or malformed. Tokens are never logged.
    """
    path = Path(credential_ref)
    if path.name != credential_ref or path.suffix != SUFFIX:
        logger.warning("ignoring a credential reference that isn't a token file name")
        return None
    try:
        UUID(path.stem)
        token = (directory / path.name).read_text(encoding="ascii").strip()
    except ValueError, OSError:
        return None
    if not token.startswith(PREFIX):
        logger.warning("skipping a malformed token file")
        return None
    return token
