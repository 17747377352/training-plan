"""Garmin 登录、MFA 会话与 Token 交付。

Garmin 的 MFA 中间态是一个存活的客户端对象（持有 HTTP 会话与 mfa 参数），
无法序列化，因此只能保存在 Collector 进程内存里。这也意味着 Collector 首期
必须单副本部署，或对 loginSessionId 做粘性路由，否则第二次请求可能落到没有
该会话的副本上。
"""

from __future__ import annotations

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
    GarminConnectTooManyRequestsError,
)

logger = structlog.get_logger()

STATUS_CONNECTED = "CONNECTED"
STATUS_MFA_REQUIRED = "MFA_REQUIRED"
STATUS_MFA_INVALID = "MFA_INVALID"
STATUS_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
STATUS_TOKEN_INVALID = "TOKEN_INVALID"
STATUS_RATE_LIMITED = "RATE_LIMITED"
STATUS_FAILED = "FAILED"

MFA_REQUIRED_FLAG = "needs_mfa"

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
    """封装 Garmin 登录与 MFA，只向外交付 Token JSON。"""

    def __init__(
        self,
        session_store: MfaSessionStore,
        client_factory: ClientFactory | None = None,
    ) -> None:
        self._sessions = session_store
        self._client_factory = client_factory or _default_client_factory

    def connect(self, email: str, password: str, region: str) -> AuthOutcome:
        """使用凭据登录 Garmin，必要时转入 MFA 流程。"""

        is_cn = region.strip().upper() == "CN"
        try:
            client = self._client_factory(email, password, is_cn, True)
            first, second = client.login()
        except GarminConnectTooManyRequestsError:
            return AuthOutcome(STATUS_RATE_LIMITED, message="Garmin 请求过于频繁，请稍后再试")
        except GarminConnectAuthenticationError:
            return AuthOutcome(STATUS_INVALID_CREDENTIALS, message="Garmin 账号或密码错误")
        except Exception as exception:  # noqa: BLE001 - 任何异常都不应把细节回传
            logger.warning("garmin_connect_failed", error_type=type(exception).__name__)
            return AuthOutcome(STATUS_FAILED, message="连接 Garmin 失败，请稍后重试")

        if first == MFA_REQUIRED_FLAG:
            session_id = self._sessions.put(client, second)
            logger.info("garmin_mfa_required", session_count=self._sessions.size())
            return AuthOutcome(STATUS_MFA_REQUIRED, login_session_id=session_id)

        return self._connected(client)

    def submit_mfa(self, login_session_id: str, mfa_code: str) -> AuthOutcome:
        """提交验证码完成登录；验证码错误时保留会话允许重试。"""

        session = self._sessions.get(login_session_id)
        if session is None:
            return AuthOutcome(STATUS_FAILED, message="MFA 会话不存在或已过期，请重新连接")
        client, client_state = session
        try:
            client.resume_login(client_state, mfa_code)
        except GarminConnectTooManyRequestsError:
            return AuthOutcome(STATUS_RATE_LIMITED, message="验证码尝试过于频繁，请稍后再试")
        except GarminConnectAuthenticationError:
            return AuthOutcome(STATUS_MFA_INVALID, message="验证码不正确，请重新输入")
        except Exception as exception:  # noqa: BLE001
            logger.warning("garmin_mfa_failed", error_type=type(exception).__name__)
            self._sessions.drop(login_session_id)
            return AuthOutcome(STATUS_FAILED, message="MFA 验证失败，请重新连接")

        self._sessions.drop(login_session_id)
        return self._connected(client)

    def restore_session(self, token_json: str, region: str) -> SessionOutcome:
        """用已存令牌恢复会话，不需要重新输入密码。

        令牌失效时返回 ``TOKEN_INVALID``，平台据此把账号标记为需要重新认证。
        """

        is_cn = region.strip().upper() == "CN"
        try:
            client = self._client_factory(None, None, is_cn, False)
            client.login(tokenstore=token_json)
        except GarminConnectTooManyRequestsError:
            return SessionOutcome(STATUS_RATE_LIMITED, message="Garmin 请求过于频繁，请稍后再试")
        except GarminConnectAuthenticationError:
            logger.info("garmin_token_rejected")
            return SessionOutcome(STATUS_TOKEN_INVALID, message="Garmin 令牌已失效，需要重新认证")
        except Exception as exception:  # noqa: BLE001
            logger.warning("garmin_token_restore_failed", error_type=type(exception).__name__)
            return SessionOutcome(STATUS_FAILED, message="Garmin 令牌校验失败")
        logger.info("garmin_token_restored")
        return SessionOutcome(STATUS_CONNECTED, client=client)

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
