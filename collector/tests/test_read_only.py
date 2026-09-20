"""只读白名单测试。

除运行期行为外，还用 AST 校验固定版本的官方库里不存在"名字是 get_ 却发写请求"
的方法——这是前缀白名单成立的前提。
"""

import ast
import pathlib

import garminconnect
import pytest

from training_plan_collector.garmin_adapter import GarminReadAdapter
from training_plan_collector.read_only import (
    DENIED_METHODS,
    ReadOnlyGarminClient,
    ReadOnlyViolation,
    is_allowed,
)


class FakeGarmin:
    """假客户端：同时提供读方法与写方法。"""

    def __init__(self) -> None:
        self.typed = object()
        self.called: list[str] = []

    def get_sleep_data(self, date: str) -> dict:
        self.called.append("get_sleep_data")
        return {"dailySleepDTO": {"sleepTimeSeconds": 3600}}

    def get_user_profile(self) -> dict:
        self.called.append("get_user_profile")
        return {"displayName": "rider"}

    def download_activity(self, activity_id: str, dl_fmt: object = None) -> bytes:
        self.called.append("download_activity")
        return b""

    def login(self) -> tuple[None, None]:
        self.called.append("login")
        return None, None

    def delete_activity(self, activity_id: str) -> None:
        self.called.append("delete_activity")

    def set_activity_name(self, activity_id: str, name: str) -> None:
        self.called.append("set_activity_name")

    def query_garmin_graphql(self, query: dict) -> dict:
        self.called.append("query_garmin_graphql")
        return {}

    def request_reload(self, cdate: str) -> dict:
        self.called.append("request_reload")
        return {}

    def remove_gear_from_activity(self, activity_id: str, gear_uuid: str) -> dict:
        self.called.append("remove_gear_from_activity")
        return {}


@pytest.mark.parametrize(
    "method_name",
    [
        "get_sleep_data",
        "get_hrv_data",
        "get_activities_by_date",
        "get_training_readiness",
        "download_activity",
        "download_health_snapshot",
        "download_workout",
        "count_activities",
        "login",
        "resume_login",
        "logout",
        "typed",
    ],
)
def test_allowed_methods(method_name: str):
    assert is_allowed(method_name) is True


@pytest.mark.parametrize(
    "method_name",
    [
        "delete_activity",
        "set_activity_name",
        "add_weigh_in",
        "upload_workout",
        "update_workout",
        "create_gear",
        "schedule_workout",
        "unschedule_workout",
        "push_workout_to_device",
        "import_activity",
        "query_garmin_graphql",
        "request_reload",
        "remove_gear_from_activity",
        "confirm_menstrual_period_start",
    ],
)
def test_denied_methods(method_name: str):
    assert is_allowed(method_name) is False


def test_guard_blocks_write_calls():
    guarded = ReadOnlyGarminClient(FakeGarmin())

    with pytest.raises(ReadOnlyViolation):
        guarded.delete_activity("1")


def test_guard_blocks_graphql_escape_hatch():
    guarded = ReadOnlyGarminClient(FakeGarmin())

    with pytest.raises(ReadOnlyViolation):
        guarded.query_garmin_graphql({"query": "mutation{...}"})


def test_guard_allows_read_calls():
    guarded = ReadOnlyGarminClient(FakeGarmin())

    assert guarded.get_user_profile() == {"displayName": "rider"}
    assert guarded.download_activity("1") == b""


def test_guard_has_no_attribute_pointing_to_wrapped_client():
    """代理实例上不应存在能一步取到原始客户端的属性。"""

    client = FakeGarmin()
    guarded = ReadOnlyGarminClient(client)

    assert hasattr(guarded, "_wrapped") is False
    assert hasattr(guarded, "wrapped") is False
    with pytest.raises(AttributeError):
        _ = guarded._wrapped


def test_guard_raises_for_existing_but_denied_method():
    """存在但被策略拒绝的方法要显式报错，而不是伪装成不存在。"""

    guarded = ReadOnlyGarminClient(FakeGarmin())

    with pytest.raises(ReadOnlyViolation):
        _ = guarded.delete_activity
    # hasattr 只吞 AttributeError，因此对被拒方法同样会抛出，
    # 这是刻意行为：策略拒绝不应被静默当成"没有这个方法"。
    with pytest.raises(ReadOnlyViolation):
        hasattr(guarded, "delete_activity")


def test_guard_rejects_attribute_assignment():
    guarded = ReadOnlyGarminClient(FakeGarmin())

    with pytest.raises(ReadOnlyViolation):
        guarded.get_user_profile = lambda: None


def test_adapter_wraps_client_with_guard():
    client = FakeGarmin()
    adapter = GarminReadAdapter(client)

    with pytest.raises(ReadOnlyViolation):
        adapter.client.delete_activity("1")
    assert client.called == []


def test_denied_and_allowed_sets_do_not_overlap():
    assert not (DENIED_METHODS & {"get_sleep_data"})


def test_installed_library_has_no_writing_get_methods():
    """校验前缀白名单的前提：官方库中不存在发写请求的 get_ 方法。"""

    source_path = pathlib.Path(garminconnect.__file__).parent / "__init__.py"
    source = source_path.read_text(encoding="utf-8")
    tree = ast.parse(source)
    garmin_class = next(
        node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Garmin"
    )
    write_markers = ('"POST"', '"PUT"', '"DELETE"', '"PATCH"', ".post(", ".put(", ".delete(")
    offenders = []
    for node in garmin_class.body:
        if not isinstance(node, ast.FunctionDef) or not node.name.startswith("get_"):
            continue
        segment = ast.get_source_segment(source, node) or ""
        if any(marker in segment for marker in write_markers):
            offenders.append(node.name)
    assert offenders == [], f"以下 get_ 方法内部会发写请求，白名单不成立: {offenders}"
