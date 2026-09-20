"""Redis connectivity shared by the task consumer."""

from redis.asyncio import Redis

from .settings import CollectorSettings


def create_redis_client(settings: CollectorSettings) -> Redis:
    """Create a decoded Redis client without logging its password."""

    password = settings.redis_password.get_secret_value() if settings.redis_password else None
    return Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        db=settings.redis_database,
        password=password or None,
        socket_timeout=7.2,
        decode_responses=True,
    )
