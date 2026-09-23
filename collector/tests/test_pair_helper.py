"""桌面助手回归测试；默认不触碰真实 Garmin 或平台。"""

import importlib.util
import json
import os
import time
import urllib.parse
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from garminconnect.client import Client

HELPER = Path(__file__).resolve().parents[2] / "web/public/garmin_pair_helper.py"
SYNTHETIC_TOKEN = {
    "di_token": "synthetic-access",
    "di_refresh_token": "synthetic-refresh",
    "di_client_id": "synthetic-client",
}


@pytest.fixture
def helper():
    spec = importlib.util.spec_from_file_location("pair_helper", HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    yield module
    module.clear_pending()


@pytest.mark.parametrize("region,domain", [("GLOBAL", "garmin.com"), ("CN", "garmin.cn")])
def test_browser_region_and_import_compatibility(helper, region, domain):
    session = helper.BrowserLogin(region)
    assert session.token_url.startswith(f"https://diauth.{domain}/")
    assert session.params["service"] == f"https://connect.{domain}/app"
    session.token = SYNTHETIC_TOKEN.copy()
    restored = Client(domain=domain)
    restored.loads(session.dumps())
    assert restored.di_token == "synthetic-access"
    assert restored.di_refresh_token == "synthetic-refresh"
    assert restored.di_client_id == "synthetic-client"


@pytest.mark.parametrize(
    "result",
    [
        {"status": 429, "body": None},
        {"status": 200, "body": {"error": {"status-code": "429"}}},
    ],
)
def test_rate_limit_stops_even_subsequent_logins(helper, monkeypatch, result):
    session = helper.BrowserLogin("GLOBAL")
    with pytest.raises(helper.BrowserLoginError, match="429"):
        session._accept_result(result)
    browser_start = Mock()
    monkeypatch.setattr(helper, "start_browser_login", browser_start)
    assert helper.start_login("example@example.invalid", "dummy", "GLOBAL")[0] == "error"
    browser_start.assert_not_called()


def test_playwright_errors_never_echo_credentials_or_ticket(helper, monkeypatch):
    def fail(_self, email, password):
        raise RuntimeError(f"fill({password}) https://example.invalid/?ticket=ST-secret {email}")

    close = Mock()
    monkeypatch.setattr(helper.BrowserLogin, "login", fail)
    monkeypatch.setattr(helper.BrowserLogin, "close", close)
    status, message = helper.start_browser_login(
        "private@example.invalid", "private-password", "GLOBAL"
    )
    assert status == "error"
    for secret in ("private-password", "ST-secret", "private@example.invalid"):
        assert secret not in message
    close.assert_called_once()


def test_browser_never_falls_back_to_http_on_error(helper, monkeypatch):
    monkeypatch.setattr(helper, "start_browser_login", lambda *args: ("error", "blocked"))
    http = Mock(side_effect=AssertionError("不应调用 HTTP 登录"))
    monkeypatch.setattr(helper, "enable_cloudscraper", http)
    assert helper.start_login("example@example.invalid", "dummy", "GLOBAL") == ("error", "blocked")
    http.assert_not_called()


def test_reject_cross_origin_fetch_before_sending_secret(helper):
    page = SimpleNamespace(url="https://untrusted.invalid", evaluate=Mock())
    with pytest.raises(helper.BrowserLoginError):
        helper.BrowserLogin._fetch(
            page, "https://sso.garmin.com/portal/api/login", headers={}, body="secret"
        )
    page.evaluate.assert_not_called()


@pytest.mark.parametrize(
    "suffix,blocked",
    [
        ("?ticket=synthetic-ticket", True),
        ("", False),
        ("/home?ticket=synthetic-ticket", False),
    ],
)
def test_cas_ticket_is_held_before_website_can_consume_it(helper, suffix, blocked):
    session = helper.BrowserLogin("GLOBAL")
    route = Mock()
    route.request.url = session.service + suffix
    session._hold_ticket(route)
    if blocked:
        route.abort.assert_called_once()
        route.continue_.assert_not_called()
    else:
        route.abort.assert_not_called()
        route.continue_.assert_called_once()


def test_token_exchange_429_does_not_try_other_client_ids(helper):
    session = helper.BrowserLogin("GLOBAL")
    page = Mock()
    page.goto.return_value.status = 405
    session.context = SimpleNamespace(new_page=lambda: page)
    session._fetch = Mock(return_value={"status": 429, "body": None})
    with pytest.raises(helper.BrowserLoginError, match="429"):
        session._exchange_ticket("synthetic-ticket")
    session._fetch.assert_called_once()
    session.context = None


@pytest.mark.parametrize(
    "body",
    [
        {"access_token": "access-only"},
        {"access_token": "access", "refresh_token": ""},
        {"jwt_web": "cookie"},
    ],
)
def test_incomplete_token_not_exported(helper, body):
    session = helper.BrowserLogin("GLOBAL")
    page = Mock()
    page.goto.return_value.status = 405
    session.context = SimpleNamespace(new_page=lambda: page)
    session._fetch = Mock(return_value={"status": 200, "body": body})
    with pytest.raises(helper.BrowserLoginError, match="DI"):
        session._exchange_ticket("synthetic-ticket")
    with pytest.raises(helper.BrowserLoginError):
        session.dumps()
    session.context = None


def test_upload_only_sends_token_and_metadata(helper, monkeypatch):
    helper.STATE.update(code="PAIRCODE", email="example@example.invalid", region="CN")
    reply = Mock()
    reply.read.return_value = b'{"code": 200}'
    response = Mock()
    response.__enter__ = Mock(return_value=reply)
    response.__exit__ = Mock(return_value=False)
    send = Mock(return_value=response)
    monkeypatch.setattr(helper.urllib.request, "urlopen", send)
    assert helper.upload_token(json.dumps(SYNTHETIC_TOKEN))[0] == "ok"
    payload = json.loads(send.call_args.args[0].data)
    assert set(payload) == {"code", "email", "region", "tokenJson"}


def test_expired_pair_code_reuses_token_and_cleans_browser(helper, monkeypatch):
    session = helper.BrowserLogin("GLOBAL")
    session.token = SYNTHETIC_TOKEN.copy()
    helper.STATE.update(pending_client=session, pending_state=True, code="OLD")
    upload = Mock(side_effect=[("error", "配对码过期"), ("ok", "绑定成功")])
    monkeypatch.setattr(helper, "upload_token", upload)
    handler = object.__new__(helper.Handler)
    handler._send = Mock()
    handler._send_finish(session)
    assert helper.STATE["pending_token"] == json.dumps(SYNTHETIC_TOKEN)
    assert helper.STATE["pending_client"] is None
    assert session.token is None
    assert "重新提交令牌" in handler._send.call_args.args[0].decode()
    handler._handle_upload({"code": ["NEWCODE"]})
    assert helper.STATE["code"] == "NEWCODE"
    assert upload.call_count == 2
    assert helper.STATE["pending_token"] is None


def test_browser_mfa_failure_keeps_session_but_429_closes_it(helper):
    session = helper.BrowserLogin("GLOBAL")
    helper.STATE.update(pending_client=session, pending_state=True)
    session.resume_login = Mock(side_effect=helper.BrowserMfaInvalid("验证码错误"))
    handler = object.__new__(helper.Handler)
    handler._send = Mock()
    handler._handle_mfa({"mfa": ["000000"]})
    assert helper.STATE["pending_client"] is session
    session.resume_login.side_effect = helper.BrowserLoginError("429")
    handler._handle_mfa({"mfa": ["000000"]})
    assert helper.STATE["pending_client"] is None


def test_pending_mfa_cannot_be_rebound_to_different_account(helper):
    helper.STATE.update(pending_state=True, email="original@example.invalid", region="GLOBAL")
    handler = object.__new__(helper.Handler)
    handler._send = Mock()
    handler._handle_bind({"email": ["other@example.invalid"], "region": ["CN"], "code": ["NEW"]})
    assert helper.STATE["email"] == "original@example.invalid"
    assert helper.STATE["region"] == "GLOBAL"


def test_idle_session_expires_and_closes_browser(helper):
    session = helper.BrowserLogin("GLOBAL")
    session.close = Mock()
    helper.STATE.update(
        pending_client=session,
        pending_state=True,
        pending_token="synthetic",
        expires_at=time.monotonic() - 1,
    )
    server = object.__new__(helper.HelperServer)
    server.service_actions()
    session.close.assert_called_once()
    assert helper.STATE["pending_client"] is None
    assert helper.STATE["pending_token"] is None


# 真 Chromium 测试：所有网络由 route 拦截，禁止接触真实账号或服务器。
# PAIR_BROWSER_TESTS=1 uv run --frozen --with playwright pytest tests/test_pair_helper.py
@pytest.mark.skipif(os.environ.get("PAIR_BROWSER_TESTS") != "1", reason="需显式启用本地 Chromium")
@pytest.mark.parametrize("region,mfa", [("GLOBAL", False), ("CN", True)])
def test_real_browser_login_mfa_exchange(helper, monkeypatch, region, mfa):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as runtime:
        browser = runtime.chromium.launch(
            channel="chromium",
            headless=True,
            # 即使拦截规则回归失效，也不能把测试流量发到真实 Garmin。
            proxy={"server": "http://127.0.0.1:9"},
        )
        context = browser.new_context()
        observed = []
        mfa_attempt = 0

        def route_request(route):
            nonlocal mfa_attempt
            request = route.request
            parsed = urllib.parse.urlsplit(request.url)
            observed.append((parsed.netloc, parsed.path, request.method))
            if parsed.path == "/portal/sso/en-US/sign-in":
                # 与官方表单同名字段；提交由页面 fetch 真正发起。
                route.fulfill(
                    content_type="text/html",
                    body="""
                  <form><input name="username"><input type="password">
                  <button type="submit">Sign in</button></form>
                  <script>document.querySelector('form').onsubmit = async event => {
                    event.preventDefault();
                    const result = await fetch('/portal/api/login', {
                      method:'POST', headers:{'Content-Type':'application/json'},
                      body:JSON.stringify({username:document.querySelector('[name=username]').value,
                        password:document.querySelector('[type=password]').value})});
                    const data = await result.json();
                    if(data.serviceTicketId) location.href =
                      new URL(location.href).searchParams.get('service')
                        + '?ticket=' + data.serviceTicketId;
                  };</script>""",
                )
            elif parsed.path == "/portal/api/login":
                assert request.post_data_json == {
                    "username": "synthetic@example.invalid",
                    "password": "synthetic-password",
                }
                data = {
                    "responseStatus": {"type": "MFA_REQUIRED" if mfa else "SUCCESSFUL"},
                    "customerMfaInfo": {"mfaLastMethodUsed": "email"},
                }
                if not mfa:
                    data["serviceTicketId"] = "synthetic-ticket"
                route.fulfill(json=data)
            elif parsed.path == "/portal/api/mfa/verifyCode":
                mfa_attempt += 1
                assert request.post_data_json["mfaVerificationCode"] in {"000000", "123456"}
                route.fulfill(
                    json={
                        "responseStatus": {
                            "type": "INVALID_MFA_CODE" if mfa_attempt == 1 else "SUCCESSFUL"
                        },
                        "serviceTicketId": "synthetic-ticket",
                    }
                )
            elif parsed.path == "/di-oauth2-service/oauth/token":
                if request.method == "GET":
                    route.fulfill(status=405, content_type="text/html", body="Method not allowed")
                else:
                    form = urllib.parse.parse_qs(request.post_data)
                    domain = "garmin.cn" if region == "CN" else "garmin.com"
                    assert parsed.netloc == f"diauth.{domain}"
                    assert form["service_url"] == [f"https://connect.{domain}/app"]
                    assert form["service_ticket"] == ["synthetic-ticket"]
                    route.fulfill(
                        json={
                            "access_token": "synthetic-access",
                            "refresh_token": "synthetic-refresh",
                        }
                    )
            else:
                route.abort()

        context.route("**/*", route_request)
        # 将创建浏览器的入口替换成已安装网络拦截的真实 Chromium，保留页面执行全链路。
        fake_browser = SimpleNamespace(new_context=lambda **kw: context, close=lambda: None)
        fake_runtime = SimpleNamespace(
            chromium=SimpleNamespace(launch=lambda **kw: fake_browser),
            stop=lambda: None,
        )
        monkeypatch.setattr(
            "playwright.sync_api.sync_playwright",
            lambda: SimpleNamespace(start=lambda: fake_runtime),
        )
        session = helper.BrowserLogin(region)
        try:
            result, state = session.login("synthetic@example.invalid", "synthetic-password")
            if mfa:
                assert result == "needs_mfa"
                with pytest.raises(helper.BrowserMfaInvalid):
                    session.resume_login(state, "000000")
                session.resume_login(state, "123456")
            else:
                assert result is None
            restored = Client(domain="garmin.cn" if region == "CN" else "garmin.com")
            restored.loads(session.dumps())
            assert restored.di_refresh_token == "synthetic-refresh"
            # CAS 票据没有先被官网 /app 消耗。
            assert not any(path == "/app" for _, path, _ in observed)
            assert sum(path == "/portal/api/login" for _, path, _ in observed) == 1
        finally:
            session.close()
            browser.close()
