"""Foundation worker: stays idle until the assignment API and strategies exist."""

import asyncio
import logging
import signal

import httpx

from mse_simulator.healthcheck import HEARTBEAT_FILE
from mse_simulator.settings import Settings

logger = logging.getLogger("mse_simulator")


async def run(settings: Settings, stop: asyncio.Event) -> None:
    limits = httpx.Limits(max_connections=settings.max_in_flight)
    async with httpx.AsyncClient(limits=limits, timeout=5.0):
        logger.info("simulator idle: no assignment source yet")
        while not stop.is_set():
            HEARTBEAT_FILE.touch()
            try:
                await asyncio.wait_for(stop.wait(), settings.heartbeat_seconds)
            except TimeoutError:
                continue


def main() -> None:
    logging.basicConfig(level=logging.INFO)

    async def entrypoint() -> None:
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for name in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(name, stop.set)
            except NotImplementedError:  # Windows event loops have no signal handlers.
                pass
        await run(Settings.from_env(), stop)

    asyncio.run(entrypoint())


if __name__ == "__main__":
    main()
