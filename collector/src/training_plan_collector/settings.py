"""Collector configuration loaded from local environment variables."""

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class CollectorSettings(BaseSettings):
    """Runtime settings. Secret values are never included in the repository."""

    model_config = SettingsConfigDict(
        env_file=".env.dev",
        env_prefix="COLLECTOR_",
        case_sensitive=False,
        extra="ignore",
    )

    env: str = "dev"
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_database: int = 5
    redis_password: SecretStr | None = None
    task_queue: str = "training-plan:sync:jobs"
    server_base_url: str = "http://127.0.0.1:8080"
    server_token: SecretStr | None = None
    internal_host: str = "127.0.0.1"
    internal_port: int = 8090
    mfa_session_ttl_seconds: int = 600
    mfa_session_max: int = 100
