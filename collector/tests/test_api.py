"""Collector 内网接口测试，验证凭据校验与响应体不泄露令牌。"""

from fastapi.testclient import TestClient

from training_plan_collector.api import create_app
from training_plan_collector.garmin_auth import (
    STATUS_CONNECTED,
    STATUS_MFA_REQUIRED,
    STATUS_TOKEN_INVALID,
    AuthOutcome,
    GarminAuthService,
    MfaSessionStore,
    SessionOutcome,
)
from training_plan_collector.settings import CollectorSettings

TOKEN = "internal-service-token"
HEADERS = {"X-Collector-Token": TOKEN}


class StubAuthService(GarminAuthService):
    """返回预设结果的假认证服务。"""

    def __init__(
        self,
        connect_outcome: AuthOutcome,
        mfa_outcome: AuthOutcome | None = None,
        verify_outcome: SessionOutcome | None = None,
    ) -> None:
        self._connect_outcome = connect_outcome
        self._mfa_outcome = mfa_outcome or connect_outcome
        self._verify_outcome = verify_outcome or SessionOutcome(STATUS_CONNECTED)
        self.connect_calls: list[tuple[str, str, str]] = []
        self.mfa_calls: list[tuple[str, str]] = []
        self.verify_calls: list[tuple[str, str]] = []

    def connect(self, email: str, password: str, region: str) -> AuthOutcome:
        self.connect_calls.append((email, password, region))
        return self._connect_outcome

    def submit_mfa(self, login_session_id: str, mfa_code: str) -> AuthOutcome:
        self.mfa_calls.append((login_session_id, mfa_code))
        return self._mfa_outcome

    def restore_session(self, token_json: str, region: str) -> SessionOutcome:
        self.verify_calls.append((token_json, region))
        return self._verify_outcome


def build_client(auth_service: StubAuthService, server_token: str | None = TOKEN) -> TestClient:
    settings = CollectorSettings(_env_file=None, server_token=server_token)
    return TestClient(create_app(settings, auth_service))


def test_health_does_not_require_token():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_connect_requires_service_token():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)))

    response = client.post(
        "/internal/garmin/connect",
        json={"email": "rider@example.com", "password": "secret", "region": "CN"},
    )

    assert response.status_code == 401


def test_connect_rejects_wrong_service_token():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)))

    response = client.post(
        "/internal/garmin/connect",
        headers={"X-Collector-Token": "wrong-token"},
        json={"email": "rider@example.com", "password": "secret", "region": "CN"},
    )

    assert response.status_code == 401


def test_connect_fails_closed_when_token_not_configured():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)), server_token=None)

    response = client.post(
        "/internal/garmin/connect",
        headers=HEADERS,
        json={"email": "rider@example.com", "password": "secret", "region": "CN"},
    )

    assert response.status_code == 503


def test_connect_returns_token_json_for_service_to_encrypt():
    service = StubAuthService(AuthOutcome(STATUS_CONNECTED, token_json='{"di_token":"t"}'))
    client = build_client(service)

    response = client.post(
        "/internal/garmin/connect",
        headers=HEADERS,
        json={"email": "rider@example.com", "password": "secret", "region": "CN"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == STATUS_CONNECTED
    assert body["tokenJson"] == '{"di_token":"t"}'
    assert body["loginSessionId"] is None
    assert service.connect_calls == [("rider@example.com", "secret", "CN")]


def test_connect_returns_session_id_when_mfa_required():
    service = StubAuthService(AuthOutcome(STATUS_MFA_REQUIRED, login_session_id="session-1"))
    client = build_client(service)

    response = client.post(
        "/internal/garmin/connect",
        headers=HEADERS,
        json={"email": "rider@example.com", "password": "secret", "region": "GLOBAL"},
    )

    body = response.json()
    assert body["status"] == STATUS_MFA_REQUIRED
    assert body["loginSessionId"] == "session-1"
    assert body["tokenJson"] is None


def test_submit_mfa_passes_code_through():
    service = StubAuthService(
        AuthOutcome(STATUS_MFA_REQUIRED, login_session_id="session-1"),
        AuthOutcome(STATUS_CONNECTED, token_json='{"di_token":"t"}'),
    )
    client = build_client(service)

    response = client.post(
        "/internal/garmin/connect/mfa",
        headers=HEADERS,
        json={"loginSessionId": "session-1", "mfaCode": "123456"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == STATUS_CONNECTED
    assert service.mfa_calls == [("session-1", "123456")]


def test_connect_rejects_invalid_region():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)))

    response = client.post(
        "/internal/garmin/connect",
        headers=HEADERS,
        json={"email": "rider@example.com", "password": "secret", "region": "MARS"},
    )

    assert response.status_code == 422


def test_real_service_wires_session_store():
    """使用真实认证服务确认应用可构造，且会话表初始为空。"""

    settings = CollectorSettings(_env_file=None, server_token=TOKEN)
    store = MfaSessionStore(ttl_seconds=600, max_sessions=10)
    client = TestClient(create_app(settings, GarminAuthService(store)))

    assert client.get("/health").status_code == 200
    assert store.size() == 0


def test_verify_token_requires_service_token():
    client = build_client(StubAuthService(AuthOutcome(STATUS_CONNECTED)))

    response = client.post(
        "/internal/garmin/verify-token",
        json={"tokenJson": '{"di_token":"t"}', "region": "CN"},
    )

    assert response.status_code == 401


def test_verify_token_returns_connected_status():
    service = StubAuthService(AuthOutcome(STATUS_CONNECTED))
    client = build_client(service)

    response = client.post(
        "/internal/garmin/verify-token",
        headers=HEADERS,
        json={"tokenJson": '{"di_token":"t"}', "region": "CN"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == STATUS_CONNECTED
    assert service.verify_calls == [('{"di_token":"t"}', "CN")]


def test_verify_token_reports_invalid_token():
    service = StubAuthService(
        AuthOutcome(STATUS_CONNECTED),
        verify_outcome=SessionOutcome(
            STATUS_TOKEN_INVALID, message="Garmin 令牌已失效，需要重新认证"
        ),
    )
    client = build_client(service)

    response = client.post(
        "/internal/garmin/verify-token",
        headers=HEADERS,
        json={"tokenJson": '{"di_token":"t"}', "region": "GLOBAL"},
    )

    body = response.json()
    assert body["status"] == STATUS_TOKEN_INVALID
    assert body["tokenJson"] is None
    assert "重新认证" in body["message"]
