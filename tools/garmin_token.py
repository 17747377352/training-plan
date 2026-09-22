#!/usr/bin/env python3
"""在本机换取 Garmin 令牌，供训练计划平台的「导入令牌」使用。

为什么要本机跑
--------------
Garmin 的登录端点按 IP 限流，并可能弹 Cloudflare 人机挑战。平台服务器只有一个
出口 IP、所有用户共用，在服务器上登录容易互相拖累；在你自己的网络上登录走的是
你熟悉的 IP，成功率高得多。而且这个脚本**只把登录产出的令牌交给平台，密码不出本机**。

依赖
----
    pip install garminconnect cloudscraper

cloudscraper 用于自动通过 Cloudflare 挑战（平台的采集器也是这么做的）。
没装也能跑，只是遇到人机挑战时更容易失败。

用法
----
    python garmin_token.py          # 国际站（connect.garmin.com），默认
    python garmin_token.py --cn     # 中国区（connect.garmin.cn）

输出
----
    1. 当前目录下的 garmin_token.json（权限 600）
    2. 终端上打印一份可直接复制粘贴的 JSON

拿到之后回到平台的「Garmin 账号」页 → 导入令牌，填 Garmin 登录邮箱、选对应站点、
把 JSON 粘进「令牌内容」。

安全提醒
--------
令牌就是账号凭据，等同于密码。只粘贴到你自己的平台页面上，不要发到群里、
不要提交进任何仓库；用完可以直接删掉本地的 garmin_token.json。
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
from pathlib import Path

OUTPUT_FILE = "garmin_token.json"

# 0.3.16 的登录链会先跑三组 curl_cffi 指纹策略，每种网络超时 30 秒；Garmin 风控时
# 还没走到已验证有效的 cloudscraper requests 策略，就可能已经等了几分钟。既然
# cloudscraper 会话本身具备挑战求解能力，跳过这三组既不降低成功率又能快很多。
# （与采集器 collector/src/training_plan_collector/garmin_auth.py 保持一致。）
SLOW_CFFI_STRATEGIES = {"mobile+cffi", "widget+cffi", "portal+cffi"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="在本机登录 Garmin 并导出平台可导入的令牌 JSON。",
    )
    parser.add_argument(
        "--cn",
        action="store_true",
        help="中国区账号（connect.garmin.cn）。不传则为国际站（connect.garmin.com）。",
    )
    parser.add_argument(
        "--email",
        help="Garmin 登录邮箱。不传则交互式询问。",
    )
    parser.add_argument(
        "--output",
        default=OUTPUT_FILE,
        help=f"令牌输出文件，默认 {OUTPUT_FILE}。",
    )
    return parser.parse_args()


def enable_cloudscraper() -> bool:
    """把 requests.Session 换成能解 Cloudflare 挑战的会话。

    必须在构造任何 Garmin 客户端**之前**调用：库的登录策略各自在方法内部新建
    requests.Session()，替换的是类本身而不是某个实例。
    """

    try:
        import cloudscraper
        import requests
    except ImportError:
        print("提示：未安装 cloudscraper，遇到 Cloudflare 人机挑战时可能失败。")
        print("      建议执行：pip install cloudscraper")
        return False

    class CloudscraperSession(cloudscraper.CloudScraper):
        """具备 Cloudflare 挑战求解能力的 requests.Session 替代品。"""

    requests.Session = CloudscraperSession
    return True


def main() -> int:
    args = parse_args()

    try:
        from garminconnect import Garmin
        from garminconnect.exceptions import (
            GarminConnectAuthenticationError,
            GarminConnectConnectionError,
            GarminConnectTooManyRequestsError,
        )
    except ImportError:
        print("缺少依赖：请先执行  pip install garminconnect", file=sys.stderr)
        return 2

    email = (args.email or input("Garmin 登录邮箱: ")).strip()
    if not email:
        print("邮箱不能为空。", file=sys.stderr)
        return 2
    password = getpass.getpass("Garmin 密码（不会回显、不会被保存）: ")
    if not password:
        print("密码不能为空。", file=sys.stderr)
        return 2

    patched = enable_cloudscraper()

    def ask_mfa_code() -> str:
        return input("Garmin 发来的验证码（短信/邮箱/验证器）: ").strip()

    client = Garmin(
        email=email,
        password=password,
        is_cn=args.cn,
        prompt_mfa=ask_mfa_code,
    )
    if patched and hasattr(client.client, "skip_strategies"):
        client.client.skip_strategies.update(SLOW_CFFI_STRATEGIES)

    site = "中国区" if args.cn else "国际站"
    print(f"\n正在登录 Garmin（{site}）……若账号开了两步验证，会在这里让你输入验证码。")

    try:
        needs_mfa, _ = client.login()
    except GarminConnectAuthenticationError:
        print("\n登录失败：邮箱或密码不正确（也可能是两步验证码输错了）。", file=sys.stderr)
        return 1
    except GarminConnectTooManyRequestsError:
        print("\n登录失败：Garmin 正在限流，请等 15~30 分钟再试。", file=sys.stderr)
        return 1
    except GarminConnectConnectionError as exception:
        print(
            "\n登录失败：网络被 Garmin 拦住了（多为 Cloudflare 人机挑战或 IP 限流）。\n"
            "可以试试：换一个网络（比如手机热点）、等一会儿再试、或确认已安装 cloudscraper。",
            file=sys.stderr,
        )
        print(f"（原始信息：{str(exception)[:200]}）", file=sys.stderr)
        return 1
    except Exception as exception:  # noqa: BLE001 - 失败原因要原样告诉用户
        print(f"\n登录失败：{type(exception).__name__}: {str(exception)[:300]}", file=sys.stderr)
        return 1

    if needs_mfa:
        print("\n登录未完成：仍需要验证码，请重新运行本脚本。", file=sys.stderr)
        return 1

    # 顺手确认这份令牌真的能读到数据，免得把坏的粘到平台上
    try:
        name = client.get_full_name()
    except Exception:  # noqa: BLE001 - 读不到名字不影响令牌本身可用
        name = "(未能读取昵称)"

    token_json = client.client.dumps()
    try:
        parsed = json.loads(token_json)
    except json.JSONDecodeError:
        print("\n令牌导出异常：不是合法 JSON，请把上面的报错反馈给平台维护者。", file=sys.stderr)
        return 1

    output_path = Path(args.output)
    output_path.write_text(token_json, encoding="utf-8")
    try:
        os.chmod(output_path, 0o600)
    except OSError:
        # Windows 等平台不支持 POSIX 权限，忽略即可
        pass

    print(f"\n登录成功，账号：{name}")
    print(f"令牌已保存到：{output_path.resolve()}")
    print(f"（包含字段：{', '.join(sorted(parsed))}）")
    print("\n下一步：打开平台的「Garmin 账号」页 → 导入令牌")
    print(f"  邮箱：{email}")
    print(f"  站点：{site}")
    print("  令牌内容：复制下面这一整段（连同花括号）粘贴进去\n")
    print(token_json)
    print("\n这份令牌等同账号凭据，请勿分享或提交到任何仓库；用后可删除本地文件。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
