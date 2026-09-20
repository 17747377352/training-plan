"""Collector 启动入口。

当前阶段对外提供内网认证接口（Garmin 登录与 MFA），
Redis 连通性只做启动检查并在不可用时告警，不阻塞认证接口启动。
"""

import asyncio

import structlog
import uvicorn

from .api import create_app
from .logging import configure_logging
from .redis_client import create_redis_client
from .settings import CollectorSettings


async def check_redis(settings: CollectorSettings) -> bool:
    """检查 Redis 连通性，供启动日志与后续任务消费使用。"""

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
        return True
    except Exception as exception:
        logger.warning(
            "collector_dependency_unavailable",
            dependency="redis",
            error_type=type(exception).__name__,
        )
        return False
    finally:
        await redis.aclose()


def main() -> None:
    """启动内网认证服务。"""

    configure_logging()
    settings = CollectorSettings()
    asyncio.run(check_redis(settings))
    logger = structlog.get_logger()
    logger.info(
        "collector_starting",
        host=settings.internal_host,
        port=settings.internal_port,
        mfa_session_ttl_seconds=settings.mfa_session_ttl_seconds,
    )
    uvicorn.run(
        create_app(settings),
        host=settings.internal_host,
        port=settings.internal_port,
        log_config=None,
        access_log=False,
    )


if __name__ == "__main__":
    main()
