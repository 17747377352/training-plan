"""Garmin 只读方法白名单。

采集器只允许调用经过审核的只读方法。白名单以 ``get_`` 前缀为主，再加上少数
明确批准的读取方法；黑名单优先级最高，用于封住那些"名字像读取、实际会写"
或"能绕过审核"的方法。

黑名单里几个方法值得单独说明：

- ``query_garmin_graphql``：把原始查询直接 POST 到 GraphQL 端点，可执行 mutation，
  等于一条任意读写通道。
- ``request_reload``：名字不含任何写动词，内部却是 POST。
- ``remove_gear_from_activity``：真正的写操作（内部 PUT），但方法名不带任何被禁前缀，
  只靠前缀黑名单会漏掉。

结论：真正起作用的是白名单，黑名单只是补充。不要把它改成"只靠黑名单"。

局限性说明：本模块防止的是采集器自身代码误用写接口，不是进程内沙箱。
同进程的代码若刻意反射调用，仍可绕过；真正的边界是"采集器进程内只有平台代码"。
"""

from __future__ import annotations

import weakref
from typing import Any

ALLOWED_PREFIXES = ("get_",)

ALLOWED_METHODS = frozenset(
    {
        "count_activities",
        "download_activity",
        "download_health_snapshot",
        "download_workout",
        # 认证相关：平台绑定流程必须使用，密码与验证码只在内存中传递。
        "login",
        "resume_login",
        "logout",
        # 官方库的 typed 命名空间只暴露 get_* 包装方法。
        "typed",
    }
)

DENIED_METHODS = frozenset(
    {
        "query_garmin_graphql",
        "request_reload",
        "remove_gear_from_activity",
        "init_menstrual_cycle_setup",
        "confirm_menstrual_period_start",
        "add_gear_to_activity",
    }
)

# 被包装的客户端保存在类外，代理实例上不保留任何指向它的公开属性，
# 避免通过 proxy._wrapped 之类的名字一步绕过检查。
_wrapped_clients: weakref.WeakKeyDictionary[ReadOnlyGarminClient, Any] = weakref.WeakKeyDictionary()


class ReadOnlyViolation(RuntimeError):
    """尝试调用白名单之外的 Garmin 方法时抛出。"""


def is_allowed(method_name: str) -> bool:
    """判断某个 Garmin 方法是否允许被采集器调用。"""

    if method_name in DENIED_METHODS:
        return False
    if method_name in ALLOWED_METHODS:
        return True
    return method_name.startswith(ALLOWED_PREFIXES)


class ReadOnlyGarminClient:
    """只读代理：白名单之外的方法一律拒绝。"""

    # 只保留弱引用槽位，既支持 WeakKeyDictionary，又不提供 __dict__。
    __slots__ = ("__weakref__",)

    def __init__(self, client: Any) -> None:
        _wrapped_clients[self] = client

    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        client = _wrapped_clients[self]
        if not hasattr(client, name):
            # 真正不存在的属性按 Python 惯例抛 AttributeError，
            # 这样 hasattr / getattr(..., default) 等探测行为不会被破坏。
            raise AttributeError(name)
        if not is_allowed(name):
            # 存在但被策略拒绝：明确报错，便于尽早发现误用。
            raise ReadOnlyViolation(f"Garmin 方法 {name} 不在只读白名单内")
        return getattr(client, name)

    def __setattr__(self, name: str, value: Any) -> None:
        raise ReadOnlyViolation("只读代理不允许设置属性")
