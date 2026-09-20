"""Cloudflare 挑战绕过模块的测试。"""

import pytest
import requests

from training_plan_collector import garmin_http


@pytest.fixture(autouse=True)
def restore_session():
    """每个用例前后都还原 requests.Session，避免测试间互相影响。"""

    garmin_http.restore_original_session()
    yield
    garmin_http.restore_original_session()


def test_enable_patches_requests_session():
    assert garmin_http.is_patched() is False

    enabled = garmin_http.enable_cloudscraper_sessions()

    assert enabled is True
    assert garmin_http.is_patched() is True
    session = requests.Session()
    assert isinstance(session, garmin_http.CloudscraperSession)
    assert isinstance(session, requests.Session)


def test_patch_is_idempotent():
    assert garmin_http.enable_cloudscraper_sessions() is True
    assert garmin_http.enable_cloudscraper_sessions() is True

    assert garmin_http.is_patched() is True


def test_restore_returns_original_session():
    garmin_http.enable_cloudscraper_sessions()
    garmin_http.restore_original_session()

    assert garmin_http.is_patched() is False
    assert not isinstance(requests.Session(), garmin_http.CloudscraperSession)


def test_env_variable_disables_bypass(monkeypatch):
    monkeypatch.setenv(garmin_http.DISABLE_ENV, "1")

    assert garmin_http.bypass_disabled() is True
    assert garmin_http.enable_cloudscraper_sessions() is False
    assert garmin_http.is_patched() is False


@pytest.mark.parametrize("value", ["1", "true", "TRUE", "yes", "on"])
def test_env_variable_truthy_values(monkeypatch, value: str):
    monkeypatch.setenv(garmin_http.DISABLE_ENV, value)

    assert garmin_http.bypass_disabled() is True


@pytest.mark.parametrize("value", ["", "0", "false", "no"])
def test_env_variable_falsy_values(monkeypatch, value: str):
    monkeypatch.setenv(garmin_http.DISABLE_ENV, value)

    assert garmin_http.bypass_disabled() is False


def test_auth_service_enables_bypass():
    """认证服务构造时必须启用绕过，否则登录会失败。"""

    from training_plan_collector.garmin_auth import GarminAuthService, MfaSessionStore

    assert garmin_http.is_patched() is False
    GarminAuthService(MfaSessionStore(ttl_seconds=60, max_sessions=1))

    assert garmin_http.is_patched() is True
