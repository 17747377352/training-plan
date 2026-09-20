"""Garmin 登录与 MFA 会话测试，全部使用假客户端，不访问真实 Garmin。"""

import pytest
from garminconnect.exceptions import (
    GarminConnectAuthenticationError,
    GarminConnectTooManyRequestsError,
)

from training_plan_collector.garmin_auth import (
    STATUS_CONNECTED,
    STATUS_FAILED,
    STATUS_INVALID_CREDENTIALS,
    STATUS_MFA_INVALID,
    STATUS_MFA_REQUIRED,
    STATUS_RATE_LIMITED,
    STATUS_TOKEN_INVALID,
    GarminAuthService,
    MfaSessionStore,
)

TOKEN_JSON = '{"di_token":"token-value","di_refresh_token":"refresh-value"}'


class FakeInnerClient:
    """模拟 garth 客户端，只暴露 dumps()。"""

    def __init__(self, token_json: str = TOKEN_JSON) -> None:
        self._token_json = token_json

    def dumps(self) -> str:
        return self._token_json


class FakeGarminClient:
    """模拟 python-garminconnect 的 Garmin 对象。"""

    def __init__(
        self,
        login_result: tuple[str | None, object] = (None, None),
        login_error: Exception | None = None,
        mfa_error: Exception | None = None,
        restore_error: Exception | None = None,
    ) -> None:
        self.client = FakeInnerClient()
        self._login_result = login_result
        self._login_error = login_error
        self._mfa_error = mfa_error
        self._restore_error = restore_error
        self.resume_login_calls: list[tuple[object, str]] = []
        self.login_tokenstores: list[str | None] = []

    def login(self, tokenstore: str | None = None) -> tuple[str | None, object]:
        self.login_tokenstores.append(tokenstore)
        if tokenstore is not None and self._restore_error is not None:
            raise self._restore_error
        if self._login_error is not None:
            raise self._login_error
        return self._login_result

    def resume_login(self, client_state: object, mfa_code: str) -> tuple[None, None]:
        self.resume_login_calls.append((client_state, mfa_code))
        if self._mfa_error is not None:
            raise self._mfa_error
        return None, None


def build_service(client: FakeGarminClient, ttl_seconds: int = 600, max_sessions: int = 100):
    store = MfaSessionStore(ttl_seconds=ttl_seconds, max_sessions=max_sessions)
    captured: dict[str, object] = {}

    def factory(email: str, password: str, is_cn: bool, return_on_mfa: bool):
        captured.update(email=email, password=password, is_cn=is_cn, return_on_mfa=return_on_mfa)
        return client

    return GarminAuthService(store, factory), store, captured


def test_connect_returns_token_on_success():
    service, store, captured = build_service(FakeGarminClient())

    outcome = service.connect("rider@example.com", "secret", "CN")

    assert outcome.status == STATUS_CONNECTED
    assert outcome.token_json == TOKEN_JSON
    assert outcome.login_session_id is None
    assert captured["is_cn"] is True
    assert captured["return_on_mfa"] is True
    assert store.size() == 0


def test_connect_uses_global_region_by_default():
    service, _store, captured = build_service(FakeGarminClient())

    service.connect("rider@example.com", "secret", "GLOBAL")

    assert captured["is_cn"] is False


def test_connect_requires_mfa_and_keeps_session():
    client = FakeGarminClient(login_result=("needs_mfa", {"state": "abc"}))
    service, store, _captured = build_service(client)

    outcome = service.connect("rider@example.com", "secret", "CN")

    assert outcome.status == STATUS_MFA_REQUIRED
    assert outcome.login_session_id is not None
    assert outcome.token_json is None
    assert store.size() == 1


def test_connect_maps_invalid_credentials():
    client = FakeGarminClient(login_error=GarminConnectAuthenticationError("bad credentials"))
    service, _store, _captured = build_service(client)

    outcome = service.connect("rider@example.com", "wrong", "CN")

    assert outcome.status == STATUS_INVALID_CREDENTIALS
    assert "bad credentials" not in (outcome.message or "")


def test_connect_maps_rate_limit():
    client = FakeGarminClient(login_error=GarminConnectTooManyRequestsError("429"))
    service, _store, _captured = build_service(client)

    outcome = service.connect("rider@example.com", "secret", "CN")

    assert outcome.status == STATUS_RATE_LIMITED


def test_connect_hides_unexpected_error_details():
    client = FakeGarminClient(login_error=RuntimeError("internal stack detail"))
    service, _store, _captured = build_service(client)

    outcome = service.connect("rider@example.com", "secret", "CN")

    assert outcome.status == STATUS_FAILED
    assert "internal stack detail" not in (outcome.message or "")


def test_submit_mfa_completes_and_drops_session():
    client = FakeGarminClient(login_result=("needs_mfa", {"state": "abc"}))
    service, store, _captured = build_service(client)
    session_id = service.connect("rider@example.com", "secret", "CN").login_session_id

    outcome = service.submit_mfa(session_id, "123456")

    assert outcome.status == STATUS_CONNECTED
    assert outcome.token_json == TOKEN_JSON
    assert store.size() == 0


def test_submit_mfa_keeps_session_when_code_is_wrong():
    client = FakeGarminClient(
        login_result=("needs_mfa", {"state": "abc"}),
        mfa_error=GarminConnectAuthenticationError("401 invalid code"),
    )
    service, store, _captured = build_service(client)
    session_id = service.connect("rider@example.com", "secret", "CN").login_session_id

    outcome = service.submit_mfa(session_id, "000000")

    assert outcome.status == STATUS_MFA_INVALID
    assert store.size() == 1
    assert "invalid code" not in (outcome.message or "")


def test_submit_mfa_rejects_unknown_session():
    service, _store, _captured = build_service(FakeGarminClient())

    outcome = service.submit_mfa("missing-session", "123456")

    assert outcome.status == STATUS_FAILED
    assert outcome.token_json is None


def test_mfa_session_expires():
    client = FakeGarminClient(login_result=("needs_mfa", {"state": "abc"}))
    service, store, _captured = build_service(client, ttl_seconds=0)
    session_id = service.connect("rider@example.com", "secret", "CN").login_session_id

    assert store.get(session_id) is None


def test_mfa_store_evicts_oldest_when_full():
    client = FakeGarminClient(login_result=("needs_mfa", {"state": "abc"}))
    service, store, _captured = build_service(client, max_sessions=2)

    first = service.connect("rider@example.com", "secret", "CN").login_session_id
    second = service.connect("rider@example.com", "secret", "CN").login_session_id
    third = service.connect("rider@example.com", "secret", "CN").login_session_id

    assert store.size() == 2
    assert store.get(first) is None
    assert store.get(second) is not None
    assert store.get(third) is not None


@pytest.mark.parametrize("region", ["cn", "CN", " Cn "])
def test_region_is_case_insensitive(region: str):
    service, _store, captured = build_service(FakeGarminClient())

    service.connect("rider@example.com", "secret", region)

    assert captured["is_cn"] is True


def test_restore_session_uses_token_without_credentials():
    client = FakeGarminClient()
    service, _store, captured = build_service(client)

    outcome = service.restore_session(TOKEN_JSON, "CN")

    assert outcome.status == STATUS_CONNECTED
    assert outcome.client is client
    assert client.login_tokenstores == [TOKEN_JSON]
    assert captured["email"] is None
    assert captured["password"] is None
    assert captured["is_cn"] is True
    assert captured["return_on_mfa"] is False


def test_restore_session_reports_invalid_token():
    client = FakeGarminClient(restore_error=GarminConnectAuthenticationError("stale token"))
    service, _store, _captured = build_service(client)

    outcome = service.restore_session(TOKEN_JSON, "GLOBAL")

    assert outcome.status == STATUS_TOKEN_INVALID
    assert outcome.client is None
    assert "stale token" not in (outcome.message or "")


def test_restore_session_maps_rate_limit():
    client = FakeGarminClient(restore_error=GarminConnectTooManyRequestsError("429"))
    service, _store, _captured = build_service(client)

    assert service.restore_session(TOKEN_JSON, "CN").status == STATUS_RATE_LIMITED


def test_restore_session_hides_unexpected_errors():
    client = FakeGarminClient(restore_error=RuntimeError("internal detail"))
    service, _store, _captured = build_service(client)

    outcome = service.restore_session(TOKEN_JSON, "CN")

    assert outcome.status == STATUS_FAILED
    assert "internal detail" not in (outcome.message or "")
