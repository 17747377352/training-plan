"""Collector 内网接口，仅供 Spring 服务调用。

接口不对外暴露：启动时绑定 127.0.0.1，并要求请求头携带与服务端共享的内部凭据。
"""

from __future__ import annotations

import asyncio
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field

from training_plan_collector.garmin_auth import AuthOutcome, GarminAuthService, MfaSessionStore
from training_plan_collector.redis_client import create_redis_client
from training_plan_collector.settings import CollectorSettings
from training_plan_collector.sync_worker import SyncWorker

logger = structlog.get_logger()

SERVICE_TOKEN_HEADER = "X-Collector-Token"


class ConnectRequest(BaseModel):
    """连接 Garmin 账号请求。密码只在内存中使用，不写日志。"""

    email: str = Field(min_length=3, max_length=128)
    password: str = Field(min_length=1, max_length=128)
    region: str = Field(pattern="^(GLOBAL|CN)$")


class MfaRequest(BaseModel):
    """提交 MFA 验证码请求。"""

    loginSessionId: str = Field(min_length=1, max_length=64)
    mfaCode: str = Field(min_length=1, max_length=16)


class VerifyTokenRequest(BaseModel):
    """用已存令牌恢复会话的请求。令牌来自平台解密，不落盘。"""

    tokenJson: str = Field(min_length=1, max_length=8192)
    region: str = Field(pattern="^(GLOBAL|CN)$")


class AuthResponse(BaseModel):
    """认证结果。tokenJson 只在 CONNECTED 时出现。"""

    status: str
    tokenJson: str | None = None
    loginSessionId: str | None = None
    message: str | None = None


def create_app(
    settings: CollectorSettings | None = None,
    auth_service: GarminAuthService | None = None,
) -> FastAPI:
    """构造 FastAPI 应用，测试可注入假的 settings 与认证服务。"""

    resolved_settings = settings or CollectorSettings()
    resolved_service = auth_service or GarminAuthService(
        MfaSessionStore(
            ttl_seconds=resolved_settings.mfa_session_ttl_seconds,
            max_sessions=resolved_settings.mfa_session_max,
        ),
        rate_limit_cooldown_seconds=resolved_settings.rate_limit_cooldown_seconds,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        """随应用启动同步工人；Redis 不可用时只告警，不阻塞内网接口。"""

        redis = create_redis_client(resolved_settings)
        worker_task: asyncio.Task[None] | None = None
        worker: SyncWorker | None = None
        try:
            await redis.ping()
        except Exception as exception:  # noqa: BLE001
            logger.warning("sync_worker_disabled", reason="redis_unavailable",
                           error_type=type(exception).__name__)
            await redis.aclose()
            redis = None
        if redis is not None and resolved_settings.server_token:
            worker = SyncWorker(resolved_settings, resolved_service, redis)
            worker_task = asyncio.create_task(worker.run())
        elif redis is not None:
            logger.warning("sync_worker_disabled", reason="server_token_missing")

        yield

        if worker is not None and worker_task is not None:
            worker.stop()
            worker_task.cancel()
            try:
                await worker_task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        if redis is not None:
            await redis.aclose()



    app = FastAPI(title="Training Plan Collector", docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)
    def require_service_token(x_collector_token: str | None = Header(default=None)) -> None:
        """校验内部服务凭据，避免内网接口被同主机其他进程调用。"""

        expected = resolved_settings.server_token
        if expected is None or not expected.get_secret_value():
            logger.error("collector_service_token_missing")
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="内部服务凭据未配置",
            )
        if x_collector_token is None or not secrets.compare_digest(
            x_collector_token, expected.get_secret_value()
        ):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部服务凭据无效")

    @app.get("/health")
    def health() -> dict[str, str]:
        """健康检查，不需要凭据。"""

        return {"status": "UP"}

    @app.post(
        "/internal/garmin/connect",
        response_model=AuthResponse,
        dependencies=[Depends(require_service_token)],
    )
    def connect(request: ConnectRequest) -> AuthResponse:
        """使用 Garmin 凭据登录，可能返回需要 MFA。"""

        outcome = resolved_service.connect(request.email, request.password, request.region)
        return _to_response(outcome)

    @app.post(
        "/internal/garmin/connect/mfa",
        response_model=AuthResponse,
        dependencies=[Depends(require_service_token)],
    )
    def submit_mfa(request: MfaRequest) -> AuthResponse:
        """提交 MFA 验证码完成登录。"""

        outcome = resolved_service.submit_mfa(request.loginSessionId, request.mfaCode)
        return _to_response(outcome)

    @app.post(
        "/internal/garmin/verify-token",
        response_model=AuthResponse,
        dependencies=[Depends(require_service_token)],
    )
    def verify_token(request: VerifyTokenRequest) -> AuthResponse:
        """用已存令牌恢复会话，令牌失效时返回 TOKEN_INVALID。"""

        outcome = resolved_service.restore_session(request.tokenJson, request.region)
        return AuthResponse(status=outcome.status, message=outcome.message)

    return app


def _to_response(outcome: AuthOutcome) -> AuthResponse:
    """把认证结果转换为响应模型，绝不记录 tokenJson。"""

    return AuthResponse(
        status=outcome.status,
        tokenJson=outcome.token_json,
        loginSessionId=outcome.login_session_id,
        message=outcome.message,
    )
