"""Container health check: the worker loop must have written a recent heartbeat."""

import os
import sys
import tempfile
import time
from pathlib import Path

HEARTBEAT_FILE = Path(tempfile.gettempdir()) / "mse-simulator.heartbeat"
MAX_AGE_SECONDS = 30.0


def is_healthy(path: Path = HEARTBEAT_FILE, now: float | None = None) -> bool:
    try:
        age = (time.time() if now is None else now) - os.path.getmtime(path)
    except OSError:
        return False
    return age <= MAX_AGE_SECONDS


if __name__ == "__main__":
    sys.exit(0 if is_healthy() else 1)
