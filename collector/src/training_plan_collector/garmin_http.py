"""让 Garmin SSO 的 Cloudflare 挑战能够被自动解决。

背景（2026-09-20 实测）：python-garminconnect 的 5 段登录策略里，两段用 curl_cffi
伪装 TLS 指纹、三段用标准 requests。在本机网络下，未打补丁时表现为
4 段被 429、1 段被 Cloudflare 403 人机挑战挡住，全部失败；把标准
requests.Session 替换为能够执行挑战的 cloudscraper 会话后，8 秒内登录成功。

实现要点：库的登录策略在各自的方法内部新建 requests.Session()，因此必须在
调用登录之前替换 requests.Session 类本身，而不是替换某个已存在的会话实例。
替换发生在调用时查找，所以无需关心导入顺序，但必须早于任何 Garmin 客户端构造。

风险：cloudscraper 只能处理经典的 IUAM JS 挑战。Garmin 若改用 Turnstile 或
托管挑战，本绕过会失效。可用环境变量 COLLECTOR_DISABLE_CLOUDFLARE_BYPASS=1
关闭，以便定位问题是否由本模块引入。
"""

from __future__ import annotations

import os
import threading

import cloudscraper
import requests

DISABLE_ENV = "COLLECTOR_DISABLE_CLOUDFLARE_BYPASS"

_original_session = requests.Session
_lock = threading.Lock()
_enabled = False


class CloudscraperSession(cloudscraper.CloudScraper):
    """具备 Cloudflare 挑战求解能力的 requests.Session 替代品。"""


def bypass_disabled() -> bool:
    """是否通过环境变量显式关闭了绕过。"""

    return os.getenv(DISABLE_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def enable_cloudscraper_sessions() -> bool:
    """将 requests.Session 替换为 cloudscraper 会话，重复调用安全。

    Returns:
        本次调用后绕过是否处于启用状态。
    """

    global _enabled
    if bypass_disabled():
        return False
    with _lock:
        if not _enabled:
            requests.Session = CloudscraperSession
            _enabled = True
    return True


def is_patched() -> bool:
    """当前 requests.Session 是否已被替换。"""

    return requests.Session is CloudscraperSession


def restore_original_session() -> None:
    """还原原始 requests.Session，仅供测试与排障使用。"""

    global _enabled
    with _lock:
        requests.Session = _original_session
        _enabled = False
