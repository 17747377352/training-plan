"""Collector command-line entry point."""

import asyncio

import structlog

from .logging import configure_logging
from .redis_client import create_redis_client
from .settings import CollectorSettings


async def check_dependencies() -> int:
    """Check Redis connectivity before the worker loop is implemented."""

    settings = CollectorSettings()
    logger = structlog.get_logger()
    redis = create_redis_client(settings)
    try:
        await redis.ping()
        logger.info(
            "collector_dependency_ready",
            dependency="redis",
            host=settings.redis_host,
            port=settings.redis_port,
            database=settings.redis_database,
        )
        return 0
    except Exception as exception:
        logger.error(
            "collector_dependency_unavailable",
            dependency="redis",
            error_type=type(exception).__name__,
        )
        return 1
    finally:
        await redis.aclose()


def main() -> None:
    """Run the collector dependency check."""

    configure_logging()
    raise SystemExit(asyncio.run(check_dependencies()))


if __name__ == "__main__":
    main()
