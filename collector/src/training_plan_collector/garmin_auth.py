"""Garmin 登录、MFA 会话与 Token 交付。

Garmin 的 MFA 中间态是一个存活的客户端对象（持有 HTTP 会话与 mfa 参数），
无法序列化，因此只能保存在 Collector 进程内存里。这也意味着 Collector 首期
必须单副本部署，或对 loginSessionId 做粘性路由，否则第二次请求可能落到没有
该会话的副本上。
"""

from __future__ import annotations

import math
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import structlog
from garminconnect import Garmin
from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectConnectionError,
    GarminConnectTooManyRequestsError,
)

from training_plan_collector import garmin_http

logger = structlog.get_logger()

STATUS_CONNECTED = "CONNECTED"
STATUS_MFA_REQUIRED = "MFA_REQUIRED"
STATUS_MFA_INVALID = "MFA_INVALID"
STATUS_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
STATUS_TOKEN_INVALID = "TOKEN_INVALID"
STATUS_RATE_LIMITED = "RATE_LIMITED"
STATUS_UNREACHABLE = "UNREACHABLE"
STATUS_FAILED = "FAILED"

MFA_REQUIRED_FLAG = "needs_mfa"

# 异常信息只截断记录，用于区分超时、限流和被风控，不记录任何凭据。
_ERROR_TEXT_LIMIT = 240

ClientFactory = Callable[..., Any]


@dataclass(frozen=True)
class AuthOutcome:
    """一次认证尝试的结果，不包含任何凭据。"""

    status: str
    token_json: str | None = None
    login_session_id: str | None = None
    message: str | None = None


@dataclass(frozen=True)
class SessionOutcome:
    """用已存令牌恢复会话的结果。

    ``client`` 只在 ``CONNECTED`` 时有值，调用方应通过只读代理使用它。
    """

    status: str
    client: Any | None = None
    message: str | None = None


class MfaSessionStore:
    """进程内存中的 MFA 会话表，带 TTL 与容量上限。"""

    def __init__(self, ttl_seconds: int, max_sessions: int) -> None:
        self._ttl_seconds = ttl_seconds
        self._max_sessions = max_sessions
        self._lock = threading.Lock()
        self._sessions: dict[str, tuple[float, Any, Any]] = {}

    def put(self, client: Any, client_state: Any) -> str:
        """登记一个待完成的 MFA 会话，返回会话 ID。"""

        session_id = uuid.uuid4().hex
        with self._lock:
            self._evict_expired_locked()
            if len(self._sessions) >= self._max_sessions:
                oldest = min(self._sessions.items(), key=lambda item: item[1][0])[0]
                self._sessions.pop(oldest, None)
            self._sessions[session_id] = (
                time.monotonic() + self._ttl_seconds,
                client,
                client_state,
            )
        return session_id

    def get(self, session_id: str) -> tuple[Any, Any] | None:
        """读取会话，过期即视为不存在。验证码错误时保留会话以便重试。"""

        with self._lock:
            entry = self._sessions.get(session_id)
            if entry is None:
                return None
            expires_at, client, client_state = entry
            if expires_at < time.monotonic():
                self._sessions.pop(session_id, None)
                return None
            return client, client_state

    def drop(self, session_id: str) -> None:
        """移除会话。"""

        with self._lock:
            self._sessions.pop(session_id, None)

    def size(self) -> int:
        """返回当前会话数量，供测试与监控使用。"""

        with self._lock:
            return len(self._sessions)

    def _evict_expired_locked(self) -> None:
        now = time.monotonic()
        expired = [key for key, value in self._sessions.items() if value[0] < now]
        for key in expired:
            self._sessions.pop(key, None)


class GarminAuthService:
    """封装 Garmin 登录与 MFA，只向外交付 Token JSON。

    Garmin 会对登录端点做 IP 级限流，并可能返回 Cloudflare 人机挑战。一旦出现
    这两类失败，继续重试只会加深限流，因此服务进入冷却期，在冷却期内直接拒绝
    请求而不触碰 Garmin。
    """

    def __init__(
        self,
        session_store: MfaSessionStore,
        client_factory: ClientFactory | None = None,
        rate_limit_cooldown_seconds: int = 900,
    ) -> None:
        self._sessions = session_store
        self._client_factory = client_factory or _default_client_factory
        self._cooldown_seconds = rate_limit_cooldown_seconds
        self._cooldown_until = 0.0
        self._cooldown_lock = threading.Lock()
        # 必须在构造任何 Garmin 客户端之前替换会话，否则 SSO 的 Cloudflare
        # 挑战会让全部登录策略失败（详见 garmin_http 模块说明）。
        if garmin_http.enable_cloudscraper_sessions():
            logger.info("garmin_cloudflare_bypass_enabled")
        else:
            logger.warning(
                "garmin_cloudflare_bypass_disabled",
                reason=garmin_http.DISABLE_ENV,
            )

    def connect(self, email: str, password: str, region: str) -> AuthOutcome:
        """使用凭据登录 Garmin，必要时转入 MFA 流程。"""

        cooling = self._cooldown_message()
        if cooling is not None:
            return AuthOutcome(STATUS_RATE_LIMITED, message=cooling)

        is_cn = region.strip().upper() == "CN"
        try:
            client = self._client_factory(email, password, is_cn, True)
            first, second = client.login()
        except GarminConnectTooManyRequestsError as exception:
            logger.warning(
                "garmin_connect_rate_limited",
                error_text=_truncate(exception),
            )
            self._enter_cooldown()
            return AuthOutcome(STATUS_RATE_LIMITED, message=self._cooldown_message())
        except GarminConnectAuthenticationError:
            return AuthOutcome(STATUS_INVALID_CREDENTIALS, message="Garmin 账号或密码错误")
        except GarminConnectConnectionError as exception:
            # 官方库的 5 段式登录链全部失败时会走到这里。实测多为 IP 被限流
            # 或 Cloudflare 人机挑战，并非密码错误，也不是本地网络不通。
            logger.warning(
                "garmin_connect_unreachable",
                error_text=_truncate(exception),
            )
            self._enter_cooldown()
            return AuthOutcome(STATUS_UNREACHABLE, message=self._cooldown_message())
        except Exception as exception:  # noqa: BLE001 - 任何异常都不应把细节回传
            logger.warning(
                "garmin_connect_failed",
                error_type=type(exception).__name__,
                error_text=_truncate(exception),
            )
            return AuthOutcome(STATUS_FAILED, message="连接 Garmin 失败，请稍后重试")

        if first == MFA_REQUIRED_FLAG:
            session_id = self._sessions.put(client, second)
            self._clear_cooldown()
            logger.info("garmin_mfa_required", session_count=self._sessions.size())
            return AuthOutcome(STATUS_MFA_REQUIRED, login_session_id=session_id)

        self._clear_cooldown()
        return self._connected(client)

    def submit_mfa(self, login_session_id: str, mfa_code: str) -> AuthOutcome:
        """提交验证码完成登录；验证码错误时保留会话允许重试。"""

        cooling = self._cooldown_message()
        if cooling is not None:
            return AuthOutcome(STATUS_RATE_LIMITED, message=cooling)

        session = self._sessions.get(login_session_id)
        if session is None:
            return AuthOutcome(STATUS_FAILED, message="MFA 会话不存在或已过期，请重新连接")
        client, client_state = session
        try:
            client.resume_login(client_state, mfa_code)
        except GarminConnectTooManyRequestsError as exception:
            logger.warning("garmin_mfa_rate_limited", error_text=_truncate(exception))
            self._enter_cooldown()
            return AuthOutcome(STATUS_RATE_LIMITED, message=self._cooldown_message())
        except GarminConnectAuthenticationError:
            return AuthOutcome(STATUS_MFA_INVALID, message="验证码不正确，请重新输入")
        except Exception as exception:  # noqa: BLE001
            logger.warning(
                "garmin_mfa_failed",
                error_type=type(exception).__name__,
                error_text=_truncate(exception),
            )
            self._sessions.drop(login_session_id)
            return AuthOutcome(STATUS_FAILED, message="MFA 验证失败，请重新连接")

        self._sessions.drop(login_session_id)
        self._clear_cooldown()
        return self._connected(client)

    def restore_session(self, token_json: str, region: str) -> SessionOutcome:
        """用已存令牌恢复会话，不需要重新输入密码。

        令牌失效时返回 ``TOKEN_INVALID``，平台据此把账号标记为需要重新认证。
        """

        is_cn = region.strip().upper() == "CN"
        # 注意：这里刻意不做冷却期预检。冷却期是为保护 SSO 登录端点而设的，
        # 而令牌恢复只访问 API 层（实测 1.6 秒），不受 SSO 限流影响。
        # 若 API 层自身被限流，下面的分支会进入冷却。
        try:
            client = self._client_factory(None, None, is_cn, False)
            client.login(tokenstore=token_json)
        except GarminConnectTooManyRequestsError as exception:
            logger.warning("garmin_token_restore_rate_limited", error_text=_truncate(exception))
            self._enter_cooldown()
            return SessionOutcome(STATUS_RATE_LIMITED, message=self._cooldown_message())
        except GarminConnectAuthenticationError:
            logger.info("garmin_token_rejected")
            return SessionOutcome(STATUS_TOKEN_INVALID, message="Garmin 令牌已失效，需要重新认证")
        except GarminConnectConnectionError as exception:
            logger.warning("garmin_token_restore_unreachable", error_text=_truncate(exception))
            self._enter_cooldown()
            return SessionOutcome(STATUS_UNREACHABLE, message=self._cooldown_message())
        except Exception as exception:  # noqa: BLE001
            logger.warning(
                "garmin_token_restore_failed",
                error_type=type(exception).__name__,
                error_text=_truncate(exception),
            )
            return SessionOutcome(STATUS_FAILED, message="Garmin 令牌校验失败")
        self._clear_cooldown()
        logger.info("garmin_token_restored")
        return SessionOutcome(STATUS_CONNECTED, client=client)

    def _cooldown_message(self) -> str | None:
        """处于冷却期时返回带剩余时间的提示，否则返回 None。"""

        with self._cooldown_lock:
            remaining = self._cooldown_until - time.monotonic()
        if remaining <= 0:
            return None
        minutes = max(1, math.ceil(remaining / 60))
        return f"Garmin 已限流，约 {minutes} 分钟后可重试"

    def _enter_cooldown(self) -> None:
        """进入冷却期，期间不再触碰 Garmin。"""

        with self._cooldown_lock:
            self._cooldown_until = time.monotonic() + self._cooldown_seconds
        logger.warning("garmin_cooldown_started", cooldown_seconds=self._cooldown_seconds)

    def _clear_cooldown(self) -> None:
        """调用成功后清除冷却期。"""

        with self._cooldown_lock:
            self._cooldown_until = 0.0

    def _connected(self, client: Any) -> AuthOutcome:
        """导出 Token JSON。调用方负责加密存储，日志中不得出现该值。"""

        try:
            token_json = client.client.dumps()
        except Exception as exception:  # noqa: BLE001
            logger.warning("garmin_token_dump_failed", error_type=type(exception).__name__)
            return AuthOutcome(STATUS_FAILED, message="Garmin 登录成功但令牌导出失败")
        logger.info("garmin_connect_succeeded")
        return AuthOutcome(STATUS_CONNECTED, token_json=token_json)


def _default_client_factory(email: str, password: str, is_cn: bool, return_on_mfa: bool) -> Garmin:
    """构造真实的 Garmin 客户端，测试中会被替换。"""

    return Garmin(email=email, password=password, is_cn=is_cn, return_on_mfa=return_on_mfa)


def _truncate(exception: BaseException) -> str:
    """截断异常文本用于日志，避免超长或意外的内容进入日志。"""

    return str(exception)[:_ERROR_TEXT_LIMIT]
