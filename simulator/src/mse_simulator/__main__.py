"""Worker entry point: the bot runner plus a heartbeat file for the container health check."""

import asyncio
import logging
import signal

import httpx

from mse_simulator.healthcheck import HEARTBEAT_FILE
from mse_simulator.runner import Runner
from mse_simulator.settings import Settings

logger = logging.getLogger("mse_simulator")


async def heartbeat(settings: Settings, stop: asyncio.Event) -> None:
    while not stop.is_set():
        HEARTBEAT_FILE.touch()
        try:
            await asyncio.wait_for(stop.wait(), settings.heartbeat_seconds)
        except TimeoutError:
            continue


async def run(settings: Settings, stop: asyncio.Event) -> None:
    # Room for every trading call and read at once, and a few for the control plane and the market.
    connections = settings.max_in_flight + settings.max_reads_in_flight + 8
    limits = httpx.Limits(max_connections=connections, max_keepalive_connections=connections)
    async with httpx.AsyncClient(limits=limits, timeout=10.0) as http:
        await asyncio.gather(heartbeat(settings, stop), Runner(settings, http).run(stop))


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    # Request lines would include nothing secret, but per-request logs drown the activity summary.
    logging.getLogger("httpx").setLevel(logging.WARNING)

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
