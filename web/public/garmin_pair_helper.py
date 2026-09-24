#!/usr/bin/env python3
"""训练计划 · Garmin 绑定助手（本机 Playwright 浏览器登录）。

密码通过本机 Chromium 发给 Garmin，不发送给平台，也不写入文件。
登录、MFA 和 DI 令牌兑换使用浏览器网络栈；平台只收到邮箱、站点和令牌。

macOS / Linux（Python 3.12+）：
    python3 -m venv .venv
    .venv/bin/python -m pip install garminconnect==0.3.16 playwright
    .venv/bin/python -m playwright install chromium
    .venv/bin/python garmin_pair_helper.py

Windows：把 .venv/bin/python 换成 .venv\\Scripts\\python。
uv：
    uv run --python 3.12 --with playwright python -m playwright install chromium
    uv run --python 3.12 --with garminconnect==0.3.16 --with playwright python garmin_pair_helper.py

默认无头浏览器；遇到需要人工操作的挑战，用 --headed 显示 Garmin 窗口。
--browser-channel chrome 可使用本机已安装的 Chrome。
--no-browser 只关闭本机助手页面的自动打开，不会禁用登录用的 Chromium。
--login-method http 保留原 HTTP 策略，需另装 cloudscraper；不会自动回退。
--token-file PATH 可直接上传已有令牌，不需要安装登录依赖。
浏览器不保证解除 Garmin IP 限流，遇到 429 应停止尝试。
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import html
import json
import logging
import pathlib
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from logging.handlers import RotatingFileHandler

DEFAULT_SERVER = "https://songtop.xyz/planapi"
DIAGNOSTICS = logging.getLogger("garmin-pair-helper")


def configure_diagnostics(log_file: pathlib.Path):
    """只接收本文件显式输出的阶段摘要，不接入第三方库的原始日志。"""
    log_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    log_file.touch(mode=0o600, exist_ok=True)
    log_file.chmod(0o600)
    handler = RotatingFileHandler(log_file, maxBytes=256_000, backupCount=1, encoding="utf-8")
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    DIAGNOSTICS.setLevel(logging.INFO)
    DIAGNOSTICS.propagate = False
    for output in (handler, logging.StreamHandler()):
        output.setFormatter(formatter)
        DIAGNOSTICS.addHandler(output)

DEPENDENCY_HINT = """
缺少登录依赖。请使用 Python 3.12+，在独立环境安装：

  python3 -m venv .venv
  .venv/bin/python -m pip install garminconnect==0.3.16 playwright
  .venv/bin/python -m playwright install chromium
  .venv/bin/python garmin_pair_helper.py

Windows：把 .venv/bin/python 换成 .venv\\Scripts\\python。
macOS 的 externally-managed-environment 是系统 Python 保护，请使用上述 venv。
人工验证：最后一行加 --headed。旧 HTTP 模式：另装 cloudscraper，加 --login-method http。
""".rstrip()

MANUAL_LOGIN_INSTRUCTIONS = """
请在刚打开的浏览器窗口里手动完成 Garmin 登录（助手不会代填、也不会代提交）：
  1. 自己输入 Garmin 邮箱与密码，然后点「登录」
  2. 如果出现人机验证，请在窗口里完成
  3. 如果要求验证码，也在窗口里输入
完成后本终端会自动继续（最长等 10 分钟），请不要关闭窗口。
""".rstrip()

# 仅旧 HTTP 模式使用，避免前置 cffi 策略长时间阻塞；浏览器模式不运行该策略链。
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
    "login_method": "browser",
    "headed": False,
    "manual": False,
    "browser_channel": "chromium",
    "pending_token": None,
    "expires_at": 0.0,
    "cooldown_until": 0.0,
}

PAGE_HEAD = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>训练计划 · Garmin 绑定助手</title>
<style>
  body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
         background: #f5f7f7; color: #303133; margin: 0; padding: 32px 16px; }
  .card { max-width: 560px; margin: 0 auto; background: #fff; border-radius: 10px;
          padding: 28px 32px; box-shadow: 0 2px 12px rgba(0,0,0,.06); }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #909399; font-size: 13px; margin-bottom: 20px; }
  label { display: block; font-size: 13px; margin: 14px 0 6px; }
  input, select { width: 100%; box-sizing: border-box; padding: 9px 11px; font-size: 14px;
                  border: 1px solid #dcdfe6; border-radius: 6px; }
  input:focus, select:focus { outline: none; border-color: #2ca58d; }
  button { margin-top: 22px; width: 100%; padding: 11px; font-size: 15px; color: #fff;
           background: #2ca58d; border: none; border-radius: 6px; cursor: pointer; }
  button:hover { background: #24907a; }
  .note { margin-top: 18px; padding: 12px 14px; background: #eef5f3; border-left: 4px solid #2ca58d;
          border-radius: 4px; font-size: 13px; line-height: 1.7; }
  .warn { background: #fdf6ec; border-left-color: #e6a23c; }
  .err { background: #fef0f0; border-left-color: #f56c6c; }
  .ok { font-size: 15px; color: #147662; font-weight: 600; }
  code { background: #f4f4f5; padding: 1px 5px; border-radius: 3px; font-size: 13px; }
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
    elif STATE["manual"]:
        # 手动模式下密码完全不需要经过助手：你自己在 Garmin 官方页面输入即可。
        credential_fields = ""
        hint = (
            "手动登录模式：助手会打开 Garmin 官方页面，"
            "<b>由你自己在窗口里输入邮箱和密码</b>，助手不代填、不代提交，"
            "所以本页面不需要密码，密码也不会经过助手。"
            "请在 Garmin 窗口里完成登录（含人机验证、验证码），然后保持窗口打开。"
        )
    else:
        credential_fields = """
      <label>Garmin 密码</label>
      <input name="password" type="password" autocomplete="off" required>"""
        hint = (
            "助手会在本机浏览器中登录 Garmin，请保持本窗口打开。"
            "遇到人机验证时，用 <code>--headed</code> 启动助手后在 Garmin 窗口操作。"
            "浏览器仍可能被限流，请不要连续点击。"
        )
    return page(f"""
    <h1>绑定 Garmin 账号</h1>
    <div class="sub">登录在你自己的电脑上完成，密码不会发送给平台。</div>
    {alert}
    <form method="post" action="/{STATE["path_token"]}/bind">
      <label>配对码（平台页面上领取）</label>
      <input name="code" value="{code}" placeholder="例如 K7M2PQ9R" autocomplete="off" required>
      <label>Garmin 登录邮箱</label>
      <input name="email" value="{email}" placeholder="name@example.com"
             autocomplete="off" required>
      {credential_fields}
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


class BrowserLoginError(Exception):
    """只包含可展示文案；不携带响应正文、URL、密码或票据。"""


class BrowserMfaInvalid(BrowserLoginError):
    """验证码错误时可继续使用同一浏览器会话。"""


class BrowserLogin:
    """以真实 Chromium 页面完成 SSO、MFA 和 DI 令牌兑换。

    不使用 Playwright 的 APIRequestContext：它不是浏览器的网络栈。
    协议常量沿用固定版本 garminconnect；页面不保存到磁盘，也不开 trace/HAR。
    所有方法必须由创建 Playwright 的同一线程调用。
    """

    def __init__(
        self,
        region: str,
        *,
        headed: bool = False,
        channel: str = "chromium",
        manual: bool = False,
    ):
        from garminconnect.client import DI_CLIENT_IDS, DI_GRANT_TYPE, PORTAL_SSO_CLIENT_ID

        if region not in {"GLOBAL", "CN"}:
            raise BrowserLoginError("站点无效，请选择国际站或中国区。")
        domain = "garmin.cn" if region == "CN" else "garmin.com"
        self.sso = f"https://sso.{domain}"
        self.service = f"https://connect.{domain}/app"
        self.token_url = f"https://diauth.{domain}/di-oauth2-service/oauth/token"
        self.params = {"clientId": PORTAL_SSO_CLIENT_ID, "locale": "en-US", "service": self.service}
        self.client_ids = DI_CLIENT_IDS
        self.grant_type = DI_GRANT_TYPE
        self.headed = headed
        # 手动模式：只打开官方页面，由用户自己输入账号密码，助手不代填不代提交。
        # 程序化 fill()+click() 本身就是机器人特征，会直接换来一个人机验证。
        self.manual = manual
        self.channel = channel
        self.runtime = self.browser = self.context = self.page = None
        self.result = None
        self.mfa_method = "email"
        self.token = None
        self.stage = "init"
        self._secrets = []
        # 与现有 finish_login(client.client.dumps()) 的接口兼容。
        self.client = self

    def close(self):
        # 即使用户提前关掉窗口，也继续释放 Playwright 驱动。
        for resource in (self.context, self.browser):
            if resource is not None:
                with contextlib.suppress(Exception):
                    resource.close()
        if self.runtime is not None:
            with contextlib.suppress(Exception):
                self.runtime.stop()
        self.page = self.context = self.browser = self.runtime = None
        self.result = self.token = None
        self._secrets.clear()

    def _stage(self, name):
        self.stage = name
        DIAGNOSTICS.info("stage=%s", name)

    def _response_summary(self, result):
        # 只读取协议状态与票据是否存在，不打印正文、URL、表单或 Cookie。
        body = result.get("body")
        body = body if isinstance(body, dict) else {}
        status = body.get("responseStatus")
        status = status.get("type") if isinstance(status, dict) else None
        if status is None:
            status = "MISSING"
        elif not isinstance(status, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", status):
            status = "UNRECOGNIZED"
        elif any(
            isinstance(secret, str) and secret and secret in status
            for secret in [*self._secrets, body.get("serviceTicketId")]
        ):
            status = "REDACTED"
        http_status = result.get("status")
        http_status = http_status if type(http_status) is int else "unknown"
        # content-type 能区分「登录结果（JSON）」与「Cloudflare 人机验证插页（HTML）」，
        # 它本身不含任何凭据，可以安全记录。
        content_type = str(result.get("content_type") or "").strip().lower()
        if not content_type:
            body_kind = "unknown"
        elif "json" in content_type:
            body_kind = "json"
        elif "html" in content_type:
            body_kind = "html"
        else:
            body_kind = "other"
        return (
            f"HTTP={http_status} responseStatus={status} "
            f"json={'yes' if isinstance(result.get('body'), dict) else 'no'} "
            f"ticket={'yes' if bool(body.get('serviceTicketId')) else 'no'} "
            f"contentType={content_type or 'missing'} body={body_kind}"
        )

    def _should_submit_credentials(self):
        """是否由助手代填账号密码并提交。

        手动模式返回 False：程序化填表（fill + click）本身就是机器人特征，会直接把
        用户推到人机验证前面；让用户自己在官方页面输入，Cloudflare 看到的是真人操作。
        助手只负责在后面等登录结果、拿票据换令牌 —— 那部分才是用户手动做不到的。
        """
        return not self.manual

    def _login_deadline_seconds(self):
        """等待登录结果的时长：手动输入（可能还有验证码）需要明显更久。"""
        if self.manual:
            return 600
        return 120 if self.headed else 45

    def _should_keep_waiting(self, body, result=None):
        """有窗口（含手动）模式下，这一条响应是不是「还没结果，得继续等」。

        三种情况要继续等：
        - 响应根本不是登录结果（非 JSON —— 实测是 Cloudflare 的验证插页）；
        - Garmin 明确要求人机验证（CAPTCHA_REQUIRED）；
        - **手动模式下收到 403**（HTML，多为 Cloudflare 拦截）：必须把窗口留给用户，
          让他完成验证或重试。曾在这里判失败，结果把正在使用的 Garmin 窗口直接关掉。
        """
        if not self.headed:
            return False
        # 限流必须先交给 _accept_result 冷却，不能当成人机验证插页继续等待。
        if isinstance(result, dict) and result.get("status") == 429:
            return False
        error = body.get("error") if isinstance(body, dict) else None
        if isinstance(error, dict) and str(error.get("status-code")) == "429":
            return False
        if not isinstance(body, dict):
            return True
        status = body.get("responseStatus")
        if isinstance(status, dict) and status.get("type") == "CAPTCHA_REQUIRED":
            return True
        if self.manual and isinstance(result, dict) and result.get("status") == 403:
            return True
        return False

    def _capture_response(self, response):
        parsed = urllib.parse.urlsplit(response.url)
        if f"{parsed.scheme}://{parsed.netloc}" != self.sso or parsed.path not in {
            "/portal/api/login",
            "/portal/api/mfa/verifyCode",
        }:
            return
        if response.request.method != "POST":
            return
        try:
            body = response.json()
        except Exception:
            body = None
        # Playwright 的响应对象有 headers；测试替身可能没有，故用 getattr 兜底。
        headers = getattr(response, "headers", None) or {}
        content_type = str(headers.get("content-type") or "").split(";")[0].strip()
        self.result = {"status": response.status, "body": body, "content_type": content_type}
        DIAGNOSTICS.info("stage=sso_response %s", self._response_summary(self.result))

    def _hold_ticket(self, route):
        # CAS 票据只能兑换一次，阻止官网先用它换成无法导入的 JWT_WEB Cookie。
        parsed = urllib.parse.urlsplit(route.request.url)
        if (
            f"{parsed.scheme}://{parsed.netloc}{parsed.path}" == self.service
            and "ticket" in urllib.parse.parse_qs(parsed.query)
        ):
            route.abort()
        else:
            route.continue_()

    def login(self, email: str, password: str):
        from playwright.sync_api import TimeoutError as PlaywrightTimeout
        from playwright.sync_api import sync_playwright

        self._secrets = [email, password]
        self._stage("browser_launch")
        self.runtime = sync_playwright().start()
        try:
            self.browser = self.runtime.chromium.launch(
                headless=not self.headed,
                channel=self.channel,
                # Playwright 默认会暴露 navigator.webdriver=true 之类的自动化特征，
                # Cloudflare 据此就能判定为机器人 —— 即使表单是你手动填的。这个开关
                # 让手动登录在内核层面更像普通 Chrome。
                args=["--disable-blink-features=AutomationControlled"],
            )
        except Exception:
            raise BrowserLoginError(
                "浏览器启动失败。请在同一 Python 环境执行 python -m playwright install chromium；"
                "使用 --browser-channel chrome 时需先安装 Chrome。"
            ) from None
        self.context = self.browser.new_context(locale="en-US")
        self.page = self.context.new_page()
        self.page.set_default_timeout(30_000)
        self.page.on("response", self._capture_response)
        self.page.route(self.service + "**", self._hold_ticket)
        signin = self.sso + "/portal/sso/en-US/sign-in?" + urllib.parse.urlencode(self.params)
        self._stage("signin_navigation")
        response = self.page.goto(signin, wait_until="domcontentloaded", timeout=45_000)
        DIAGNOSTICS.info("stage=signin_loaded HTTP=%s", response.status if response else "none")
        if response and response.status == 429:
            self._rate_limited()
        try:
            self._stage("form_wait")
            password_field = self.page.locator('input[type="password"]').first
            password_field.wait_for(state="visible", timeout=120_000 if self.headed else 30_000)
            if not self._should_submit_credentials():
                # 手动模式：绝不代填、代提交。程序化 fill()+click() 本身就是机器人
                # 特征，会直接换来一个人机验证；让用户自己敲，Cloudflare 看到的是真人。
                self._stage("manual_login_wait")
                DIAGNOSTICS.info("stage=manual_login_wait result=awaiting_user")
                print(MANUAL_LOGIN_INSTRUCTIONS, flush=True)
            else:
                # 使用官网表单，使其 JavaScript 和 CAPTCHA token 参与提交。
                if (
                    urllib.parse.urlsplit(self.page.url).netloc
                    != urllib.parse.urlsplit(self.sso).netloc
                ):
                    raise BrowserLoginError("登录页离开了 Garmin SSO，已停止填写凭据。")
                self._stage("form_submit")
                self.page.locator(
                    'input[name="username"], input[type="email"], input#username'
                ).first.fill(email)
                password_field.fill(password)
                self.page.locator('button[type="submit"], input[type="submit"]').first.click(
                    no_wait_after=True,
                )
        except PlaywrightTimeout:
            raise BrowserLoginError(
                "Garmin 登录表单未就绪，可能遇到人机挑战或页面已改版。"
                "请使用 --headed 打开可见窗口后再试；不要连续重试。"
            ) from None
        deadline = time.monotonic() + self._login_deadline_seconds()
        self._stage("login_result")
        last_summary = ""
        while time.monotonic() < deadline:
            if self.result is not None:
                result, self.result = self.result, None
                # 保留 None / 空数组等原始类型，供等待逻辑辨别无法解析的响应。
                # 提前用空字典兜底会让「非 JSON 继续等待」分支永远无法接到 None。
                body = result.get("body")
                if self._should_keep_waiting(body, result):
                    # 有窗口（含手动）模式：等用户解题、或这条根本不是登录结果（Cloudflare
                    # 插页/403），都不能就此判失败 —— 更不能把用户正在用的窗口关掉。
                    last_summary = self._response_summary(result)
                    DIAGNOSTICS.info(
                        "stage=login_result result=interactive_page %s", last_summary
                    )
                    continue
                return self._accept_result(result)
            if self.page.is_closed():
                # 用户自己关了窗口：不必再空等到超时
                raise BrowserLoginError(
                    "Garmin 窗口已被关闭，本次登录中止。请重新提交配对码再试一次。"
                )
            self.page.wait_for_timeout(100)
        if last_summary:
            raise BrowserLoginError(
                "等待超时：Garmin 始终没有给出登录结果（多是还在等你完成人机验证，"
                "或请求被 Cloudflare 拦截）。请查看浏览器窗口并完成验证后重试。"
                f"诊断：{last_summary}"
            )
        raise BrowserLoginError("等待 Garmin 登录结果超时，请检查可见窗口或使用 --headed 重试。")

    @staticmethod
    def _rate_limited():
        STATE["cooldown_until"] = time.monotonic() + 900
        raise BrowserLoginError(
            "Garmin 返回 429，已停止请求并冷却 15 分钟。浏览器不能解除 IP 限流。"
        )

    def _accept_result(self, result, *, mfa=False):
        body = result.get("body")
        body = body if isinstance(body, dict) else {}
        summary = self._response_summary(result)
        DIAGNOSTICS.info("stage=%s %s", "mfa_result" if mfa else "login_result", summary)
        error = body.get("error")
        error = error if isinstance(error, dict) else {}
        if result["status"] == 429 or str(error.get("status-code")) == "429":
            self._rate_limited()
        if result["status"] == 403:
            raise BrowserLoginError(
                "Garmin 拒绝了这次浏览器请求（403，多为 Cloudflare 拦截）。"
                "请用 --manual 在可见窗口里自己登录；若窗口里有人机验证，先完成它。"
                f"诊断：{summary}"
            )
        status = body.get("responseStatus")
        status = status.get("type") if isinstance(status, dict) else None
        if status == "SUCCESSFUL" and isinstance(body.get("serviceTicketId"), str):
            self._exchange_ticket(body["serviceTicketId"])
            return None, None
        if status == "MFA_REQUIRED":
            self.mfa_method = body.get("customerMfaInfo", {}).get("mfaLastMethodUsed") or "email"
            return "needs_mfa", True
        if status == "INVALID_USERNAME_PASSWORD":
            raise BrowserLoginError("Garmin 邮箱或密码不正确。")
        if status == "CAPTCHA_REQUIRED":
            raise BrowserLoginError("Garmin 要求人机验证，请使用 --headed 在官方页面中完成。")
        if mfa and result["status"] in {200, 400, 401, 422}:
            raise BrowserMfaInvalid("验证码不正确或已过期，请重新输入；会话仍保留在本机。")
        raise BrowserLoginError(
            "Garmin 返回了助手尚未识别的登录结果。"
            f"诊断：{summary}。请提供这段诊断文字，以便确定下一步。"
        )

    @staticmethod
    def _fetch(page, url, *, headers, body):
        parsed = urllib.parse.urlsplit(url)
        current = urllib.parse.urlsplit(page.url)
        if (parsed.scheme, parsed.netloc) != (current.scheme, current.netloc):
            raise BrowserLoginError("浏览器所在域名与登录接口不一致，已停止提交。")
        # 同源 fetch 由 Chromium 发出；禁止重定向，避免凭据被转发至其他地址。
        return page.evaluate(
            """async ({url, headers, body}) => {
          const controller = new AbortController();
          const timer = setTimeout(() => controller.abort(), 30000);
          try {
            const response = await fetch(url, {method: 'POST', headers, body,
              credentials: 'include', mode: 'same-origin', redirect: 'error',
              signal: controller.signal});
            let data = null;
            try { data = await response.json(); } catch (_) {}
            return {status: response.status, body: data};
          } finally { clearTimeout(timer); }
        }""",
            {"url": url, "headers": headers, "body": body},
        )

    def resume_login(self, _state, code):
        self._secrets.append(code)
        self._stage("mfa_submit")
        result = self._fetch(
            self.page,
            self.sso + "/portal/api/mfa/verifyCode?" + urllib.parse.urlencode(self.params),
            headers={"Content-Type": "application/json"},
            body=json.dumps(
                {
                    "mfaMethod": self.mfa_method,
                    "mfaVerificationCode": code,
                    "rememberMyBrowser": False,
                    "reconsentList": [],
                    "mfaSetup": False,
                }
            ),
        )
        needs_mfa, _ = self._accept_result(result, mfa=True)
        if needs_mfa:
            raise BrowserMfaInvalid("仍需要验证码，请重新输入。")

    def _exchange_ticket(self, ticket):
        # 在 DI 域名内进行同源兑换，避免 SSO -> DI 跨域 CORS 限制；不回退到 requests。
        self._secrets.append(ticket)
        self._stage("di_navigation")
        token_page = self.context.new_page()
        response = token_page.goto(self.token_url, wait_until="domcontentloaded", timeout=30_000)
        if response and response.status == 429:
            self._rate_limited()
        for client_id in self.client_ids:
            self._stage("di_exchange")
            result = self._fetch(
                token_page,
                self.token_url,
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Authorization": "Basic " + base64.b64encode(f"{client_id}:".encode()).decode(),
                },
                body=urllib.parse.urlencode(
                    {
                        "client_id": client_id,
                        "service_ticket": ticket,
                        "grant_type": self.grant_type,
                        "service_url": self.service,
                    }
                ),
            )
            if result["status"] == 429:
                self._rate_limited()
            DIAGNOSTICS.info("stage=di_result HTTP=%s", result["status"])
            body = result.get("body")
            body = body if isinstance(body, dict) else {}
            if result["status"] == 200 and all(
                isinstance(body.get(key), str) and body[key]
                for key in ("access_token", "refresh_token")
            ):
                # 刷新时必须使用签发令牌所对应的 client_id，与上游客户端保持一致。
                actual_client_id = client_id
                with contextlib.suppress(Exception):
                    part = body["access_token"].split(".")[1]
                    claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
                    if isinstance(claims.get("client_id"), str) and claims["client_id"]:
                        actual_client_id = claims["client_id"]
                self.token = {
                    "di_token": body["access_token"],
                    "di_refresh_token": body["refresh_token"],
                    "di_client_id": actual_client_id,
                }
                self._stage("token_ready")
                return
            # 只有明确的 client_id 不兼容才尝试下一候选；403/网络错误不重放票据。
            if body.get("error") != "invalid_client":
                break
        raise BrowserLoginError("已完成登录，但未取得可续期的 DI 令牌，未向平台提交 Cookie。")

    def dumps(self):
        if not self.token:
            raise BrowserLoginError("没有可导出的 DI 令牌。")
        return json.dumps(self.token)


def clear_pending():
    client = STATE["pending_client"]
    if isinstance(client, BrowserLogin):
        client.close()
    STATE.update(pending_client=None, pending_state=None, pending_token=None, expires_at=0.0)


def expire_pending():
    expires_at = float(STATE["expires_at"])
    if expires_at and time.monotonic() >= expires_at:
        clear_pending()


def start_browser_login(email, password, region):
    client = None
    try:
        client = BrowserLogin(
            region,
            headed=bool(STATE["headed"]),
            channel=str(STATE["browser_channel"]),
            manual=bool(STATE["manual"]),
        )
        needs_mfa, client_state = client.login(email, password)
        STATE.update(
            pending_client=client, pending_state=client_state, expires_at=time.monotonic() + 300
        )
        return ("mfa" if needs_mfa else "ok"), None
    except BrowserLoginError as exception:
        message = str(exception)
        DIAGNOSTICS.warning("stage=%s result=login_rejected %s", client.stage if client else "init", message)
    except ImportError:
        message = "缺少浏览器依赖，请安装 garminconnect==0.3.16 和 playwright。"
        DIAGNOSTICS.warning("stage=init result=missing_dependency")
    except KeyboardInterrupt:
        if client is not None:
            client.close()
        raise
    except Exception:
        # Playwright 异常含 fill 参数与带票据的 URL，绝不能原样展示或记录。
        message = "浏览器登录中断或网络超时，请检查浏览器窗口；可使用 --headed 重试。"
        DIAGNOSTICS.warning("stage=%s result=browser_interrupted", client.stage if client else "init")
    if client is not None:
        client.close()
    return "error", message


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


def _redact(text: str, secret: str) -> str:
    """万一库的日志里出现密码，替换掉再展示。"""

    return text.replace(secret, "***") if secret else text


class StrategyLogCollector(logging.Handler):
    """收集 garminconnect 的分段失败原因。

    库的 login() 会依次尝试多段策略，但最终只抛出**最后一段**的错误。只报「Portal login:
    HTTP 403」会让人以为是单纯的 Cloudflare 拦截，而真正的原因可能是前面那段被 429 限流 ——
    这两种情况的处理方式完全不同（前者换网络，后者等一会儿）。所以这里把每段的原因都留下。
    """

    def __init__(self) -> None:
        super().__init__(level=logging.DEBUG)
        self.lines: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        try:
            message = record.getMessage()
        except Exception:  # noqa: BLE001 - 日志格式化失败不应该影响登录流程
            return
        if record.levelno >= logging.WARNING or "strategy" in message.lower():
            self.lines.append(f"{record.levelname}: {message}")

    def drain(self) -> str:
        text = "\n".join(self.lines)
        self.lines.clear()
        return text


def start_login(email: str, password: str, region: str):
    """发起一次 Garmin 登录。

    Returns:
        ("ok", None) 已登录；("mfa", None) 需要验证码；("error", 文案) 失败。
    """

    remaining = int(float(STATE["cooldown_until"]) - time.monotonic())
    if remaining > 0:
        return "error", f"Garmin 登录仍在冷却中，请约 {remaining // 60 + 1} 分钟后再试。"
    clear_pending()
    if STATE["login_method"] == "browser":
        return start_browser_login(email, password, region)

    from garminconnect import Garmin
    from garminconnect.exceptions import (
        GarminConnectAuthenticationError,
        GarminConnectConnectionError,
        GarminConnectTooManyRequestsError,
    )

    enable_cloudscraper()
    client = Garmin(email=email, password=password, is_cn=(region == "CN"), return_on_mfa=True)
    if hasattr(client.client, "skip_strategies"):
        client.client.skip_strategies.update(SLOW_CFFI_STRATEGIES)

    collector = StrategyLogCollector()
    library_logger = logging.getLogger("garminconnect")
    previous_level = library_logger.level
    library_logger.setLevel(logging.DEBUG)
    library_logger.addHandler(collector)

    def describe(details: str) -> str:
        detail_text = _redact(details.strip(), password)
        return f"\n\n各段策略的实际结果：\n{detail_text}" if detail_text else ""

    try:
        needs_mfa, client_state = client.login()
    except GarminConnectAuthenticationError:
        return "error", "Garmin 邮箱或密码不正确。" + describe(collector.drain())
    except GarminConnectTooManyRequestsError:
        return "error", (
            "Garmin 正在限流这个网络：等 15~30 分钟再试，"
            "或换一个网络（手机热点常常有效）。" + describe(collector.drain())
        )
    except GarminConnectConnectionError as exception:
        return "error", (
            "被 Garmin 拦住了。两种情况最常见：这个网络最近登录次数过多（等一会儿再试），"
            "或者网络出口被 Cloudflare 挑战（换手机热点试试）。"
            + describe(collector.drain() or str(exception))
        )
    except Exception as exception:  # noqa: BLE001 - 失败原因要原样告诉用户
        return "error", (
            f"登录失败：{type(exception).__name__}: {str(exception)[:200]}"
            + describe(collector.drain())
        )
    finally:
        library_logger.removeHandler(collector)
        library_logger.setLevel(previous_level)

    if needs_mfa:
        STATE["pending_client"] = client
        STATE["pending_state"] = client_state
        STATE["expires_at"] = time.monotonic() + 300
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
    # 登录耗时可能超过配对码有效期。失败时短暂保留令牌供换码重传，避免再登录。
    if isinstance(client, BrowserLogin):
        client.close()
    STATE.update(
        pending_client=None,
        pending_state=None,
        pending_token=token_json,
        expires_at=time.monotonic() + 300,
    )
    return upload_token(token_json)


def upload_token(token_json: str) -> tuple[str, str]:
    """把令牌交给平台，由平台校验后加密入库。"""

    DIAGNOSTICS.info("stage=platform_upload")
    payload = json.dumps(
        {
            "code": STATE["code"],
            "email": STATE["email"],
            "tokenJson": token_json,
            "region": STATE["region"],
        }
    ).encode("utf-8")
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
        DIAGNOSTICS.warning("stage=platform_upload HTTP=%s", exception.code)
        return "error", f"平台返回 HTTP {exception.code}，请确认平台地址是否正确。"
    except Exception as exception:  # noqa: BLE001
        DIAGNOSTICS.warning("stage=platform_upload result=connection_failed")
        return "error", f"无法连接平台：{type(exception).__name__}: {str(exception)[:160]}"

    if body.get("code") == 200:
        DIAGNOSTICS.info("stage=platform_upload result=bound")
        return "ok", "平台已接收令牌并完成绑定。"
    DIAGNOSTICS.warning("stage=platform_upload result=rejected")
    return "error", str(body.get("message") or "平台拒绝了这次绑定。")


def retry_upload_page(message: str = "") -> bytes:
    return page(f"""
    <h1>Garmin 已登录，等待平台绑定</h1>
    <div class="note">{html.escape(message)}</div>
    <div class="note">令牌仅在本机内存保留 5 分钟。配对码若过期，请回平台领取新码后提交，
    无需再次输入 Garmin 密码。</div>
    <form method="post" action="/{STATE["path_token"]}/upload">
      <label>配对码</label>
      <input name="code" autocomplete="off" required>
      <button type="submit">重新提交令牌</button>
    </form>
    """)


class HelperServer(ThreadingHTTPServer):
    """HTTP 并发接收；状态与 Playwright 生命周期由一个专用线程串行操作。

    浏览器会预先建立不发送请求的 TCP 连接，单线程 HTTPServer 会被其永久阻塞。
    不能把 Playwright 直接移到各 HTTP 线程，否则跨请求提交 MFA 会跨线程访问。
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.auth_worker = ThreadPoolExecutor(max_workers=1, thread_name_prefix="garmin-auth")
        self.expiry_check = None

    def run_auth(self, action, *args):
        return self.auth_worker.submit(action, *args).result()

    def service_actions(self):
        # 登录耗时较长时最多排队一次过期检查，不阻塞 HTTP accept 循环。
        if self.expiry_check is None or self.expiry_check.done():
            self.expiry_check = self.auth_worker.submit(expire_pending)

    def server_close(self):
        super().server_close()
        # 初始化绑定端口失败时，父类也可能调用 server_close。
        worker = getattr(self, "auth_worker", None)
        if worker is not None:
            try:
                self.run_auth(clear_pending)
            finally:
                worker.shutdown(wait=True)
                self.auth_worker = None


class Handler(BaseHTTPRequestHandler):
    server_version = "TrainingPlanPairHelper"
    # 空连接或只发送了一半请求的连接到时关闭，不长期占用 HTTP 线程。
    timeout = 10

    # 默认实现会把整个请求行写进控制台，其中含本次运行的随机路径，没必要
    def log_message(self, fmt, *args):  # noqa: A003 - 覆写基类方法
        return

    def _authorized(self) -> bool:
        return (
            self.path.startswith(f"/{STATE['path_token']}/")
            or self.path == f"/{STATE['path_token']}"
        )

    def _send(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - 覆写基类方法
        if not self._authorized():
            self._send(
                page("<h1>链接无效</h1><div class='note err'>请使用启动时打开的地址。</div>"),
                status=403,
            )
            return
        self.server.run_auth(self._handle_get)

    def _handle_get(self):
        expire_pending()
        if STATE["pending_token"]:
            self._send(retry_upload_page())
        elif STATE["pending_state"] is not None:
            self._send(mfa_page())
        else:
            self._send(form_page())

    def do_POST(self):  # noqa: N802 - 覆写基类方法
        if not self._authorized():
            self._send(page("<h1>链接无效</h1>"), status=403)
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            self._send(page("<h1>请求无效</h1>"), status=400)
            return
        if not 0 < length <= 16_384:
            self._send(page("<h1>请求过大或为空</h1>"), status=400)
            return
        try:
            fields = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))
        except UnicodeDecodeError:
            self._send(page("<h1>请求编码无效</h1>"), status=400)
            return
        # 先在 HTTP 线程读完请求，再交给串行工作线程；半包不能阻塞登录会话。
        self.server.run_auth(self._dispatch_post, fields)

    def _dispatch_post(self, fields):
        expire_pending()
        if self.path == f"/{STATE['path_token']}/mfa":
            self._handle_mfa(fields)
        elif self.path == f"/{STATE['path_token']}/upload":
            self._handle_upload(fields)
        elif self.path == f"/{STATE['path_token']}/bind":
            self._handle_bind(fields)
        else:
            self._send(page("<h1>链接无效</h1>"), status=404)

    def _handle_bind(self, fields: dict) -> None:
        if STATE["pending_token"]:
            self._send(retry_upload_page())
            return
        if STATE["pending_state"] is not None:
            self._send(mfa_page("请先完成当前账号的验证，或等待会话过期。"))
            return
        code = (fields.get("code") or [""])[0].strip().upper()
        email = (fields.get("email") or [""])[0].strip()
        password = (fields.get("password") or [""])[0]
        region = (fields.get("region") or ["GLOBAL"])[0]

        if region not in {"GLOBAL", "CN"}:
            self._send(form_page("站点无效。", error=True))
            return
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

        # 手动模式不需要密码：用户会在 Garmin 官方页面自己输入。
        if not password and not STATE["manual"]:
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
        if not mfa_code:
            self._send(mfa_page("请输入验证码。"))
            return
        try:
            client.resume_login(client_state, mfa_code)
        except BrowserMfaInvalid as exception:
            self._send(mfa_page(str(exception)))
            return
        except BrowserLoginError as exception:
            clear_pending()
            self._send(fail_page(str(exception)))
            return
        except Exception as exception:  # noqa: BLE001
            if isinstance(client, BrowserLogin):
                DIAGNOSTICS.warning("stage=%s result=browser_interrupted", client.stage)
                clear_pending()
                self._send(fail_page("浏览器会话中断，请重新开始登录。"))
                return
            # 验证码错误时保留会话，允许重试
            self._send(mfa_page(f"验证码不正确或已过期，请重试。（{type(exception).__name__}）"))
            return

        STATE["pending_state"] = None
        self._send_finish(client)

    def _send_finish(self, client) -> None:
        outcome, message = finish_login(client)
        if outcome == "ok":
            clear_pending()
            self._send(done_page(None, message))
        elif STATE["pending_token"]:
            self._send(retry_upload_page(message))
        else:
            clear_pending()
            self._send(fail_page(message))

    def _handle_upload(self, fields: dict) -> None:
        if not STATE["pending_token"]:
            self._send(fail_page("本机令牌已过期，请重新登录。"))
            return
        code = (fields.get("code") or [""])[0].strip().upper()
        if not code:
            self._send(retry_upload_page("请输入新的配对码。"))
            return
        STATE["code"] = code
        outcome, message = upload_token(str(STATE["pending_token"]))
        if outcome == "ok":
            clear_pending()
            self._send(done_page(None, message))
        else:
            self._send(retry_upload_page(message))


def main() -> int:
    parser = argparse.ArgumentParser(description="在本地换取 Garmin 令牌并交给平台完成绑定。")
    parser.add_argument("--server", default=DEFAULT_SERVER, help=f"平台地址，默认 {DEFAULT_SERVER}")
    parser.add_argument("--port", type=int, default=0, help="本地监听端口，默认自动选择空闲端口")
    parser.add_argument("--no-browser", action="store_true", help="不要自动打开浏览器")
    parser.add_argument(
        "--log-file", type=pathlib.Path,
        default=pathlib.Path.home() / ".training-plan" / "garmin-pair-helper.log",
        help="本机诊断日志路径，仅记录阶段和状态，不记录凭据",
    )
    parser.add_argument(
        "--login-method",
        choices=("browser", "http"),
        default="browser",
        help="登录方式：默认 Playwright 浏览器；http 保留原登录策略供排障",
    )
    parser.add_argument(
        "--headed", action="store_true", help="显示 Garmin 浏览器窗口以完成人机验证"
    )
    parser.add_argument(
        "--manual",
        action="store_true",
        help=(
            "手动登录（推荐）：只打开 Garmin 官方页面，由你自己输入账号密码，"
            "助手不代填不代提交，避免程序化填表触发人机验证。隐含 --headed"
        ),
    )
    parser.add_argument(
        "--browser-channel",
        choices=("chromium", "chrome", "msedge"),
        default="chromium",
        help="使用的 Chromium 浏览器，默认 chromium",
    )
    parser.add_argument(
        "--token-file", default="", help="已有一个 Garmin 令牌 JSON 时直接交回平台，不再登录 Garmin"
    )
    args = parser.parse_args()
    try:
        configure_diagnostics(args.log_file)
    except OSError:
        print("无法创建诊断日志，请用 --log-file 指定可写路径。", file=sys.stderr)
        return 2

    try:
        if not args.token_file:
            from importlib.metadata import version

            import garminconnect  # noqa: F401 - 只做依赖检查

            if version("garminconnect") != "0.3.16":
                print(
                    "请在助手环境安装 garminconnect==0.3.16，以匹配平台令牌格式。", file=sys.stderr
                )
                return 2
            if args.login_method == "browser":
                import playwright.sync_api  # noqa: F401 - 仅浏览器登录需要
    except ImportError:
        if not args.token_file:
            print(DEPENDENCY_HINT, file=sys.stderr)
            return 2

    STATE["server"] = args.server.rstrip("/")
    STATE["path_token"] = secrets.token_urlsafe(12)
    STATE["token_file"] = args.token_file
    STATE["login_method"] = args.login_method
    # 手动登录必须有可见窗口，所以 --manual 隐含 --headed
    STATE["manual"] = args.manual
    STATE["headed"] = args.headed or args.manual
    STATE["browser_channel"] = args.browser_channel

    httpd = HelperServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{httpd.server_address[1]}/{STATE['path_token']}/"
    print("Garmin 绑定助手已启动")
    print(f"  平台地址：{STATE['server']}")
    print(f"  本机页面：{url}")
    print(f"  诊断日志：{args.log_file}")
    print("  按 Ctrl+C 退出")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已退出")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
