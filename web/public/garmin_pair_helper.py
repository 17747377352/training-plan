#!/usr/bin/env python3
"""训练计划 · Garmin 绑定助手。

在**你自己电脑上**完成 Garmin 登录，然后把登录得到的令牌交回平台。

为什么要这样做
--------------
Garmin 的登录接口按 IP 限流，而且配额很小（实测一次成功登录之后立刻返回 429），
平台服务器上所有用户共用一个出口 IP，所以在服务器上登录别人的账号基本不可能成功。
在你自己的网络里登录，用的是你自己那份配额。

安全性
------
- 只监听 127.0.0.1，外部网络访问不到；
- 每次运行生成一个随机路径前缀，其他网页/程序即使扫到端口也用不了这个表单；
- **密码只在本机内存里用于登录 Garmin，不会发给平台**，也不写入任何文件；
- 交给平台的只有 Garmin 返回的令牌。

用法
----
macOS / Linux（任意 Python 3.10+，不会污染系统环境）：

    python3 -m venv .venv && .venv/bin/pip install -q garminconnect cloudscraper
    .venv/bin/python garmin_pair_helper.py            # 绑定到线上平台
    .venv/bin/python garmin_pair_helper.py --server http://127.0.0.1:8099   # 本地后端

Windows（PowerShell / cmd）：

    python -m venv .venv
    .venv\\Scripts\\pip install garminconnect cloudscraper
    .venv\\Scripts\\python garmin_pair_helper.py

装了 uv 的话一条命令就够：

    uv run --python 3.12 --with garminconnect --with cloudscraper python garmin_pair_helper.py

注意：macOS 上 Homebrew / 系统自带的 Python 直接 `pip install` 会被 PEP 668 拦下并报
`externally-managed-environment` —— 那不是缺东西，是系统不允许往全局环境装包，
用上面的 venv 方式即可。cloudscraper 用于自动通过 Cloudflare 挑战（平台采集器也是这么做的），
没装也能跑，只是遇到人机挑战时更容易失败。
"""

from __future__ import annotations

import argparse
import html
import json
import pathlib
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_SERVER = "https://songtop.xyz/planapi"

DEPENDENCY_HINT = """
缺少依赖 garminconnect / cloudscraper。

  macOS / Linux（任意 Python 3.10+，不需要动系统环境）：
      python3 -m venv .venv && .venv/bin/pip install -q garminconnect cloudscraper
      .venv/bin/python garmin_pair_helper.py

  Windows（PowerShell / cmd）：
      python -m venv .venv
      .venv\\Scripts\\pip install garminconnect cloudscraper
      .venv\\Scripts\\python garmin_pair_helper.py

  装了 uv 的话一条命令就够：
      uv run --python 3.12 --with garminconnect --with cloudscraper python garmin_pair_helper.py

提示：macOS 上直接 pip install 会报 externally-managed-environment（PEP 668），
      这是系统在保护全局环境，用上面的 venv 方式即可。
""".rstrip()

# 0.3.16 的登录链会先跑三组 curl_cffi 指纹策略，每种网络超时 30 秒。启用
# cloudscraper 会话后这两组 requests 策略已经能解 Cloudflare 挑战，跳过 cffi
# 既不降低成功率又能避免白等几分钟（与平台采集器保持一致）。
SLOW_CFFI_STRATEGIES = {"mobile+cffi", "widget+cffi", "portal+cffi"}

# 单进程、单用户的本地工具，用模块级状态保存「待输入验证码」的登录会话即可
STATE: dict[str, object] = {
    "server": DEFAULT_SERVER,
    "path_token": "",
    "token_file": "",
    "pending_client": None,
    "pending_state": None,
    "code": "",
    "email": "",
    "region": "GLOBAL",
}

PAGE_HEAD = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>训练计划 · Garmin 绑定助手</title>
<style>
  body {{ font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
         background: #f5f7f7; color: #303133; margin: 0; padding: 32px 16px; }}
  .card {{ max-width: 560px; margin: 0 auto; background: #fff; border-radius: 10px;
          padding: 28px 32px; box-shadow: 0 2px 12px rgba(0,0,0,.06); }}
  h1 {{ font-size: 20px; margin: 0 0 4px; }}
  .sub {{ color: #909399; font-size: 13px; margin-bottom: 20px; }}
  label {{ display: block; font-size: 13px; margin: 14px 0 6px; }}
  input, select {{ width: 100%; box-sizing: border-box; padding: 9px 11px; font-size: 14px;
                  border: 1px solid #dcdfe6; border-radius: 6px; }}
  input:focus, select:focus {{ outline: none; border-color: #2ca58d; }}
  button {{ margin-top: 22px; width: 100%; padding: 11px; font-size: 15px; color: #fff;
           background: #2ca58d; border: none; border-radius: 6px; cursor: pointer; }}
  button:hover {{ background: #24907a; }}
  .note {{ margin-top: 18px; padding: 12px 14px; background: #eef5f3; border-left: 4px solid #2ca58d;
          border-radius: 4px; font-size: 13px; line-height: 1.7; }}
  .warn {{ background: #fdf6ec; border-left-color: #e6a23c; }}
  .err {{ background: #fef0f0; border-left-color: #f56c6c; }}
  .ok {{ font-size: 15px; color: #147662; font-weight: 600; }}
  code {{ background: #f4f4f5; padding: 1px 5px; border-radius: 3px; font-size: 13px; }}
</style></head><body><div class="card">"""

PAGE_TAIL = "</div></body></html>"


def page(body: str) -> bytes:
    return (PAGE_HEAD + body + PAGE_TAIL).encode("utf-8")


def form_page(message: str = "", error: bool = False) -> bytes:
    """登录表单：配对码 + Garmin 凭据（已提供令牌文件时不需要密码）。"""

    region = html.escape(str(STATE["region"]))
    code = html.escape(str(STATE["code"]))
    email = html.escape(str(STATE["email"]))
    token_file = STATE["token_file"]
    alert = ""
    if message:
        alert = f'<div class="note {"err" if error else ""}">{html.escape(message)}</div>'
    global_selected = "selected" if region == "GLOBAL" else ""
    cn_selected = "selected" if region == "CN" else ""
    if token_file:
        credential_fields = f"""
      <label>令牌文件</label>
      <input value="{html.escape(str(token_file))}" disabled>"""
        hint = "已指定令牌文件，将直接把它交给平台，不再登录 Garmin。"
    else:
        credential_fields = """
      <label>Garmin 密码</label>
      <input name="password" type="password" autocomplete="off" required>"""
        hint = ("Garmin 对同一网络的登录次数限制很严：<b>密码输错一次可能就要等几分钟再试</b>，"
                "请不要连续点击。绑定时请保持本窗口打开。")
    return page(f"""
    <h1>绑定 Garmin 账号</h1>
    <div class="sub">登录在你自己的电脑上完成，密码不会发送给平台。</div>
    {alert}
    <form method="post" action="/{STATE["path_token"]}/bind">
      <label>配对码（平台页面上领取）</label>
      <input name="code" value="{code}" placeholder="例如 K7M2PQ9R" autocomplete="off" required>
      <label>Garmin 登录邮箱</label>
      <input name="email" value="{email}" placeholder="name@example.com" autocomplete="off" required>{credential_fields}
      <label>站点</label>
      <select name="region">
        <option value="GLOBAL" {global_selected}>国际站（connect.garmin.com）</option>
        <option value="CN" {cn_selected}>中国区（connect.garmin.cn）</option>
      </select>
      <button type="submit">开始绑定</button>
    </form>
    <div class="note warn">{hint}</div>
    """)


def mfa_page(message: str = "") -> bytes:
    alert = f'<div class="note">{html.escape(message)}</div>' if message else ""
    return page(f"""
    <h1>输入 Garmin 验证码</h1>
    <div class="sub">Garmin 要求两步验证，验证码已发送到你的手机或邮箱。</div>
    {alert}
    <form method="post" action="/{STATE["path_token"]}/mfa">
      <label>验证码</label>
      <input name="mfa" placeholder="6 位数字" autocomplete="off" required autofocus>
      <button type="submit">提交验证码</button>
    </form>
    """)


def done_page(account: dict | None, message: str) -> bytes:
    name = ""
    if account:
        name = html.escape(str(account.get("garminEmailMasked") or account.get("email") or ""))
    return page(f"""
    <h1>绑定成功</h1>
    <div class="note"><span class="ok">✓ {html.escape(message)}</span>
    {f"<div style='margin-top:6px'>账号：{name}</div>" if name else ""}</div>
    <div class="note">现在可以关闭这个窗口，回到平台页面刷新即可看到已绑定的账号。</div>
    """)


def fail_page(message: str) -> bytes:
    return page(f"""
    <h1>绑定失败</h1>
    <div class="note err">{html.escape(message)}</div>
    <div class="note">
      可以回到平台页面重新领取配对码，再点浏览器的后退键重试。<br>
      如果是被 Garmin 限流，请等 15~30 分钟，或换一个网络（手机热点常常有效）。
    </div>
    """)


def enable_cloudscraper() -> bool:
    """把 requests.Session 换成能解 Cloudflare 挑战的会话。

    必须在构造 Garmin 客户端之前调用：库的登录策略各自在方法内部新建
    requests.Session()，替换的是类本身。
    """

    try:
        import cloudscraper
        import requests
    except ImportError:
        return False

    class CloudscraperSession(cloudscraper.CloudScraper):
        """具备 Cloudflare 挑战求解能力的 requests.Session 替代品。"""

    requests.Session = CloudscraperSession
    return True


def start_login(email: str, password: str, region: str):
    """发起一次 Garmin 登录。

    Returns:
        ("ok", None) 已登录；("mfa", None) 需要验证码；("error", 文案) 失败。
    """

    from garminconnect import Garmin
    from garminconnect.exceptions import (
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
        GarminConnectTooManyRequestsError,
    )

    enable_cloudscraper()
    client = Garmin(email=email, password=password, is_cn=(region == "CN"),
                    return_on_mfa=True)
    if hasattr(client.client, "skip_strategies"):
        client.client.skip_strategies.update(SLOW_CFFI_STRATEGIES)

    try:
        needs_mfa, client_state = client.login()
    except GarminConnectAuthenticationError:
        return "error", "Garmin 邮箱或密码不正确。"
    except GarminConnectTooManyRequestsError:
        return "error", ("Garmin 正在限流这个网络，请等 15~30 分钟再试，"
                         "或换一个网络（手机热点）后重试。")
    except GarminConnectConnectionError as exception:
        return "error", (f"被 Garmin 拦住了（多为人机验证或 IP 限流）：{str(exception)[:160]}")
    except Exception as exception:  # noqa: BLE001 - 失败原因要原样告诉用户
        return "error", f"登录失败：{type(exception).__name__}: {str(exception)[:200]}"

    if needs_mfa:
        STATE["pending_client"] = client
        STATE["pending_state"] = client_state
        return "mfa", None

    STATE["pending_client"] = client
    STATE["pending_state"] = None
    return "ok", None


def finish_login(client) -> tuple[str, str]:
    """导出令牌并交回平台。"""

    try:
        token_json = client.client.dumps()
    except Exception as exception:  # noqa: BLE001
        return "error", f"登录成功但令牌导出失败：{type(exception).__name__}"
    return upload_token(token_json)


def upload_token(token_json: str) -> tuple[str, str]:
    """把令牌交给平台，由平台校验后加密入库。"""

    payload = json.dumps({
        "code": STATE["code"],
        "email": STATE["email"],
        "tokenJson": token_json,
        "region": STATE["region"],
    }).encode("utf-8")
    request = urllib.request.Request(
        f"{STATE['server']}/api/garmin/accounts/pair",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:  # noqa: S310 - 地址由用户指定
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        return "error", f"平台返回 HTTP {exception.code}，请确认平台地址是否正确。"
    except Exception as exception:  # noqa: BLE001
        return "error", f"无法连接平台：{type(exception).__name__}: {str(exception)[:160]}"

    if body.get("code") == 200:
        return "ok", "平台已接收令牌并完成绑定。"
    return "error", str(body.get("message") or "平台拒绝了这次绑定。")


class Handler(BaseHTTPRequestHandler):
    server_version = "TrainingPlanPairHelper"

    # 默认实现会把整个请求行写进控制台，其中含本次运行的随机路径，没必要
    def log_message(self, fmt, *args):  # noqa: A003 - 覆写基类方法
        return

    def _authorized(self) -> bool:
        return self.path.startswith(f"/{STATE['path_token']}/") or \
            self.path == f"/{STATE['path_token']}"

    def _send(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - 覆写基类方法
        if not self._authorized():
            self._send(page("<h1>链接无效</h1><div class='note err'>"
                            "请使用启动时打开的地址。</div>"), status=403)
            return
        self._send(form_page())

    def do_POST(self):  # noqa: N802 - 覆写基类方法
        if not self._authorized():
            self._send(page("<h1>链接无效</h1>"), status=403)
            return

        length = int(self.headers.get("Content-Length") or 0)
        fields = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))

        if self.path.endswith("/mfa"):
            self._handle_mfa(fields)
        else:
            self._handle_bind(fields)

    def _handle_bind(self, fields: dict) -> None:
        code = (fields.get("code") or [""])[0].strip().upper()
        email = (fields.get("email") or [""])[0].strip()
        password = (fields.get("password") or [""])[0]
        region = (fields.get("region") or ["GLOBAL"])[0]

        STATE.update({"code": code, "email": email, "region": region})
        if not code or not email:
            self._send(form_page("配对码和邮箱都要填。", error=True))
            return

        if STATE["token_file"]:
            # 已有令牌：不登录 Garmin，直接交回平台
            try:
                token_json = pathlib.Path(str(STATE["token_file"])).read_text(encoding="utf-8")
            except OSError as exception:
                self._send(fail_page(f"读取令牌文件失败：{exception}"))
                return
            outcome, message = upload_token(token_json)
            self._send(done_page(None, message) if outcome == "ok" else fail_page(message))
            return

        if not password:
            self._send(form_page("配对码、邮箱、密码都要填。", error=True))
            return

        result, message = start_login(email, password, region)
        if result == "error":
            self._send(form_page(message, error=True))
            return
        if result == "mfa":
            self._send(mfa_page())
            return
        self._send_finish(STATE["pending_client"])

    def _handle_mfa(self, fields: dict) -> None:
        client = STATE["pending_client"]
        client_state = STATE["pending_state"]
        if client is None or client_state is None:
            self._send(fail_page("登录会话已失效，请重新提交邮箱与密码。"))
            return

        mfa_code = (fields.get("mfa") or [""])[0].strip()
        try:
            client.resume_login(client_state, mfa_code)
        except Exception as exception:  # noqa: BLE001
            # 验证码错误时保留会话，允许重试
            self._send(mfa_page(f"验证码不正确或已过期，请重试。（{type(exception).__name__}）"))
            return

        STATE["pending_state"] = None
        self._send_finish(client)

    def _send_finish(self, client) -> None:
        outcome, message = finish_login(client)
        # 绑定结束后立刻丢掉客户端与登录会话，不在内存里留存
        STATE["pending_client"] = None
        STATE["pending_state"] = None
        if outcome == "ok":
            self._send(done_page(None, message))
        else:
            self._send(fail_page(message))


def main() -> int:
    parser = argparse.ArgumentParser(description="在本地换取 Garmin 令牌并交给平台完成绑定。")
    parser.add_argument("--server", default=DEFAULT_SERVER,
                        help=f"平台地址，默认 {DEFAULT_SERVER}")
    parser.add_argument("--port", type=int, default=0,
                        help="本地监听端口，默认自动选择空闲端口")
    parser.add_argument("--no-browser", action="store_true", help="不要自动打开浏览器")
    parser.add_argument("--token-file", default="",
                        help="已有一个 Garmin 令牌 JSON 时直接交回平台，不再登录 Garmin")
    args = parser.parse_args()

    try:
        import garminconnect  # noqa: F401 - 只做依赖检查
    except ImportError:
        if not args.token_file:
            print(DEPENDENCY_HINT, file=sys.stderr)
            return 2

    STATE["server"] = args.server.rstrip("/")
    STATE["path_token"] = secrets.token_urlsafe(12)
    STATE["token_file"] = args.token_file

    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{httpd.server_address[1]}/{STATE['path_token']}/"
    print("Garmin 绑定助手已启动")
    print(f"  平台地址：{STATE['server']}")
    print(f"  本机页面：{url}")
    print("  按 Ctrl+C 退出")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
