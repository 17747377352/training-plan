#!/usr/bin/env python3
"""本机 Garmin 同步入口：准备环境、配对、浏览器采集、校验并补传到平台。"""

import argparse
import contextlib
import getpass
import json
import os
import plistlib
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

from mapping import build_payload, counts


SKILL_DIR = Path(__file__).resolve().parents[1]
UPSTREAM = "garmin-givemydata==0.1.13"
DEFAULT_SERVER = "https://songtop.xyz/planapi"
DEFAULT_SCHEDULE_LABEL = "com.training-plan.garmin-browser-sync"


class SyncError(Exception):
    """可直接呈现给 agent 的脱敏错误。"""


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def write_private(path, value):
    """原子保存配置或检查点，目录 700、文件 600，绝不打印配置内容。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_suffix(path.suffix + ".tmp")
    with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
    temp.replace(path)
    path.chmod(0o600)


def read_json(path, default=None):
    return json.loads(path.read_text()) if path.exists() else ({} if default is None else default)


@contextlib.contextmanager
def exclusive_lock(state_dir):
    """一个状态目录只运行一个同步进程，保护 Chrome 会话和上传检查点。"""
    import fcntl
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state_dir / "sync.lock").open("a") as stream:
        try:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise SyncError("已有同步进程运行，请等待它结束") from error
        try:
            yield
        finally:
            fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def validate_server(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1"})):
        raise SyncError("平台地址必须使用 HTTPS，本机测试可使用 localhost HTTP")
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SyncError("平台地址格式无效")
    return url.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止上传请求被重定向，避免专用令牌泄漏到其他站点。"""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(server, path, payload=None, token=None, attempts=1):
    """同时检查 HTTP 与 Result.code；只对上传的临时网络错误有限重试。"""
    body = None if payload is None else json.dumps(payload, allow_nan=False).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Garmin-Upload-Token"] = token
    request = urllib.request.Request(validate_server(server) + path, body, headers)
    for attempt in range(attempts):
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=45) as response:
                result = json.load(response)
            if not isinstance(result, dict) or result.get("code") != 200:
                code = result.get("code") if isinstance(result, dict) else "invalid_response"
                raise SyncError("平台拒绝请求，业务码=" + str(code))
            return result.get("data")
        except urllib.error.HTTPError as error:
            retryable = error.code >= 500 or error.code == 429
            if not retryable or attempt + 1 == attempts:
                raise SyncError("平台 HTTP 错误=" + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
            if attempt + 1 == attempts:
                raise SyncError("平台连接失败；本地数据已保留，可运行 upload 补传") from None
        except (json.JSONDecodeError, ValueError):
            raise SyncError("平台返回了无法解析的回执，未标记上传成功") from None
        time.sleep(2 ** attempt)


def python_path(state_dir):
    return state_dir / "venv" / "bin" / "python"


def prepare(state_dir):
    """在私有目录安装固定版本上游，不改系统 Python 或其他项目环境。"""
    executable = python_path(state_dir)
    if executable.exists():
        check = subprocess.run([str(executable), "-c",
            "import importlib.metadata; assert importlib.metadata.version('garmin-givemydata') == '0.1.13'"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if check.returncode == 0:
            return executable
    uv = shutil.which("uv")
    if uv:
        commands = [[uv, "venv", "--python", "3.12", str(state_dir / "venv")],
                    [uv, "pip", "install", "--python", str(executable), UPSTREAM]]
    elif sys.version_info >= (3, 10):
        commands = [[sys.executable, "-m", "venv", str(state_dir / "venv")],
                    [str(executable), "-m", "pip", "install", UPSTREAM]]
    else:
        raise SyncError("需要 uv 或 Python 3.10+；请先安装 uv 后重新执行 prepare")
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, timeout=600)
        if result.returncode:
            raise SyncError("依赖安装失败，请检查网络后重试 prepare（上游版本 0.1.13）")
    return executable


def prompt_secret(label, env_name):
    """终端无回显输入；非交互任务缺配置时立即退出，不悬挂等输入。"""
    value = os.environ.get(env_name)
    if value:
        return value
    if not sys.stdin.isatty():
        raise SyncError("首次配置需交互输入：" + label)
    value = getpass.getpass(label + ": ").strip()
    if not value:
        raise SyncError(label + "不能为空")
    return value


def setup(args, state_dir):
    """首次配对一步完成；凭据只写进被忽略的 storage/config.json。"""
    prepare(state_dir)
    config_file = state_dir / "config.json"
    config = read_json(config_file)
    email = args.email or config.get("email") or os.environ.get("GARMIN_EMAIL")
    if not email and sys.stdin.isatty():
        email = input("Garmin 国际站邮箱: ").strip()
    if not email or "@" not in email:
        raise SyncError("请通过 --email 指定 Garmin 国际站邮箱")
    server = validate_server(args.server)
    # 先完成依赖和密码配置，再领取短期配对码，减少输入期间过期的概率。
    password = config.get("garminPassword")
    if not args.without_garmin_password:
        password = prompt_secret("Garmin 密码（仅存本机 storage 配置）", "GARMIN_PASSWORD")
    code = prompt_secret("平台 Garmin 账号页的一次性配对码", "GARMIN_PAIR_CODE")
    result = api(server, "/api/garmin/browser-upload/pair", {"code": code, "email": email, "region": "GLOBAL"})
    if not isinstance(result, dict) or not result.get("uploadToken") or not result.get("accountId"):
        raise SyncError("配对回执缺少凭据或账号 ID")
    data_dir = state_dir / "data"
    data_dir.mkdir(mode=0o700, exist_ok=True)
    config.update({"server": server, "email": email, "region": "GLOBAL", "dataDir": str(data_dir),
                   "accountId": result["accountId"], "uploadToken": result["uploadToken"]})
    if password:
        config["garminPassword"] = password
    # 先保存新凭据，后续会话复制失败也不会丢掉刚签发的令牌。
    write_private(config_file, config)
    if args.session_from:
        source = Path(args.session_from).expanduser().resolve()
        for name in ("browser_profile", "garmin_session.json"):
            old, new = source / name, data_dir / name
            if old.exists() and not new.exists():
                if old.is_dir():
                    shutil.copytree(old, new, ignore=shutil.ignore_patterns("Singleton*"), symlinks=True)
                else:
                    shutil.copy2(old, new)
    emit({"ok": True, "action": "setup", "accountId": result["accountId"],
          "readyForUnattended": bool(password), "stateDir": str(state_dir)})


def configured(state_dir, require_password=False):
    config = read_json(state_dir / "config.json")
    if not config.get("uploadToken") or not config.get("server"):
        raise SyncError("尚未配对，请先运行 setup")
    if require_password and not (os.environ.get("GARMIN_PASSWORD") or config.get("garminPassword")):
        raise SyncError("尚未配置本机 Garmin 密码，请运行 credentials；upload 补传不需要密码")
    return config


def upload_range(state_dir, config, db, start, end, dry_run=False):
    """按 7 天分批上传；每批确认入库后才推进检查点，网络失败保留待传范围。"""
    progress_file = state_dir / "progress.json"
    progress = read_json(progress_file)
    begin, finish = date.fromisoformat(start), date.fromisoformat(end)
    if begin > finish:
        raise SyncError("开始日期晚于结束日期")
    summary = {"ok": True, "action": "upload", "dryRun": dry_run, "startDate": start, "endDate": end,
               "counts": {}, "jobIds": [], "warnings": ["上游 0.1.13 不提供 FTP 历史，ftpHistory 留空"]}
    while begin <= finish:
        batch_end = min(finish, begin + timedelta(days=6))
        payload = build_payload(db, begin.isoformat(), batch_end.isoformat())
        if not dry_run:
            progress["pending"] = {"db": str(Path(db).resolve()), "start": begin.isoformat(), "end": end}
            write_private(progress_file, progress)
            job_id = api(config["server"], "/api/garmin/browser-upload/ingest", payload,
                         config["uploadToken"], attempts=3)
            if not isinstance(job_id, int) or isinstance(job_id, bool) or job_id <= 0:
                raise SyncError("平台未返回有效任务 ID，保留批次等待补传")
            summary["jobIds"].append(job_id)
            progress["lastUploadedDate"] = max(progress.get("lastUploadedDate", "0001-01-01"), batch_end.isoformat())
            progress["pending"] = None if batch_end == finish else {
                "db": str(Path(db).resolve()), "start": (batch_end + timedelta(days=1)).isoformat(), "end": end}
            write_private(progress_file, progress)
        for key, value in counts(payload).items():
            summary["counts"][key] = summary["counts"].get(key, 0) + value
        begin = batch_end + timedelta(days=1)
    if not dry_run:
        write_private(state_dir / "last-run.json", summary)
    return summary


def capture(state_dir, config, start, visible):
    """运行上游原 CLI，输出仅保留结构化回执，密码通过子进程环境传入。"""
    executable = prepare(state_dir)
    manifest_file = state_dir / "fetch-manifest.json"
    manifest_file.unlink(missing_ok=True)
    environment = dict(os.environ, GARMIN_EMAIL=config["email"],
                       GARMIN_PASSWORD=os.environ.get("GARMIN_PASSWORD") or config["garminPassword"],
                       GARMIN_DATA_DIR=config["dataDir"], GARMIN_RUN_MANIFEST=str(manifest_file))
    command = [str(executable), str(SKILL_DIR / "scripts" / "upstream_runner.py"),
               "--since", start, "--no-files", "--no-trackpoints"]
    if visible:
        command.append("--visible")
    process = subprocess.Popen(command, env=environment, cwd=state_dir, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        process.communicate(timeout=1800)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate()
        raise SyncError("浏览器取数超时；请运行 sync --visible 人工检查登录或验证页面") from None
    manifest = read_json(manifest_file)
    if process.returncode or not manifest.get("success"):
        raise SyncError("浏览器取数未完成：" + str(manifest.get("error") or "检查 Chrome 环境或登录状态"))
    return manifest


def schedule_plist(python, skill_dir, state_dir, hour, minute, label):
    """生成 launchd 任务定义。纯函数：不碰文件系统，便于离线校验。"""
    if not 0 <= hour <= 23 or not 0 <= minute <= 59:
        raise SyncError("定时时间无效：小时 0-23、分钟 0-59")
    log_file = Path(state_dir) / "logs" / "schedule.log"
    # 解释器走 env + PATH 而不是写死绝对路径：Homebrew 升级后 Cellar 里的 <版本> 目录会消失，
    # 写死会让定时任务在无人察觉的情况下再也跑不起来。PATH 里保留解释器所在目录（升级后失效
    # 会被跳过），再兜底到 /opt/homebrew/bin 与 uv 的位置（prepare 需要 uv）。
    search_path = ":".join([str(Path(python).parent), "/opt/homebrew/bin", "/usr/local/bin",
                            str(Path.home() / ".local" / "bin"), "/usr/bin", "/bin"])
    return {
        "Label": label,
        "ProgramArguments": ["/usr/bin/env", "python3",
                             str(Path(skill_dir) / "scripts" / "garmin_sync.py"),
                             "--state-dir", str(state_dir), "sync"],
        "StartCalendarInterval": {"Hour": hour, "Minute": minute},
        # 装载时不立刻跑：否则每次改配置都会意外触发一次真实取数
        "RunAtLoad": False,
        "WorkingDirectory": str(state_dir),
        "StandardOutPath": str(log_file),
        "StandardErrorPath": str(log_file),
        "EnvironmentVariables": {"PATH": search_path, "HOME": str(Path.home())},
    }


def plist_path(label):
    return Path.home() / "Library" / "LaunchAgents" / (label + ".plist")


def launchctl(action, label, path=None):
    """调用 launchctl；返回 (是否成功, 输出)。"""
    if action == "bootstrap":
        command = ["launchctl", "bootstrap", f"gui/{os.getuid()}", str(path)]
    elif action == "bootout":
        command = ["launchctl", "bootout", f"gui/{os.getuid()}/{label}"]
    else:
        command = ["launchctl", "print", f"gui/{os.getuid()}/{label}"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    return result.returncode == 0, (result.stdout or result.stderr).strip()


def log_tail(state_dir, lines=3, width=200):
    """定时任务的日志尾巴：只看最后几行，按行长截断，避免把整份日志塞进摘要。"""
    log_file = Path(state_dir) / "logs" / "schedule.log"
    if not log_file.exists():
        return []
    content = log_file.read_text(errors="replace").splitlines()
    return [line[:width] for line in content if line.strip()][-lines:]


def schedule(args, state_dir):
    label = args.label or DEFAULT_SCHEDULE_LABEL
    target = plist_path(label)
    if args.uninstall:
        launchctl("bootout", label)
        target.unlink(missing_ok=True)
        emit({"ok": True, "action": "schedule-uninstall", "label": label})
        return
    spec = schedule_plist(sys.executable, SKILL_DIR, state_dir,
                          args.hour, args.minute, label)
    encoded = plistlib.dumps(spec)
    if args.print_only:
        sys.stdout.write(encoded.decode())
        return
    if sys.platform != "darwin":
        raise SyncError("定时任务目前只支持 macOS launchd")
    # 未配对或没配密码就装载，只会装出一个每天静默失败的任务：宁可在装载这一步就报错。
    configured(state_dir, require_password=True)
    (state_dir / "logs").mkdir(parents=True, exist_ok=True)
    if target.exists():
        launchctl("bootout", label)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(encoded)
    ok, output = launchctl("bootstrap", label, target)
    if not ok:
        raise SyncError("定时任务装载失败：" + output)
    loaded, status = launchctl("print", label)
    emit({"ok": loaded, "action": "schedule-install", "label": label, "plist": str(target),
          "hour": args.hour, "minute": args.minute, "stateDir": str(state_dir),
          "logFile": spec["StandardOutPath"], "loaded": loaded, "status": status.splitlines()[0] if status else ""})


def sync(args, state_dir):
    config = configured(state_dir)
    progress = read_json(state_dir / "progress.json")
    pending = progress.get("pending")
    if pending:
        emit(upload_range(state_dir, config, pending["db"], pending["start"], pending["end"]))
        progress = read_json(state_dir / "progress.json")
    config = configured(state_dir, require_password=True)
    end = date.today() - timedelta(days=1)
    start = date.today() - timedelta(days=3)
    if progress.get("lastUploadedDate"):
        start = min(start, date.fromisoformat(progress["lastUploadedDate"]) - timedelta(days=2))
    if args.since:
        start = date.fromisoformat(args.since)
    if start > end:
        raise SyncError("常规同步只上传昨日及之前的完整日期")
    manifest = capture(state_dir, config, start.isoformat(), args.visible)
    expected = {(start + timedelta(days=i)).isoformat() for i in range((end - start).days + 1)}
    missing = expected - set(manifest.get("freshDailyDates") or [])
    if missing:
        raise SyncError("本次未取得新鲜每日数据，未推进检查点：" + ",".join(sorted(missing)))
    if manifest.get("endpoints", {}).get("activities", {}).get("status") != 200:
        raise SyncError("活动列表本次取数失败，不能将空列表视为没有活动")
    progress["lastFetchedDate"] = end.isoformat()
    write_private(state_dir / "progress.json", progress)
    summary = upload_range(state_dir, config, Path(config["dataDir"]) / "garmin.db", start.isoformat(), end.isoformat())
    summary["action"] = "sync"
    failed = sum(1 for value in manifest["endpoints"].values() if value["status"] not in (200, 204, 404))
    if failed:
        summary["warnings"].append(f"{failed} 个可选端点未返回成功，请检查 fetch-manifest.json")
    write_private(state_dir / "last-run.json", summary)
    emit(summary)


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=SKILL_DIR / "storage")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare", help="安装隔离运行环境")
    doctor = commands.add_parser("doctor", help="检查环境、配对与定时任务状态，不显示凭据")
    doctor.add_argument("--label", help="要检查的定时任务标签，默认用内置标签")
    commands.add_parser("credentials", help="更新本机保存的 Garmin 密码")
    init = commands.add_parser("setup", help="首次配对并配置本机运行环境")
    init.add_argument("--server", default=DEFAULT_SERVER)
    init.add_argument("--email")
    init.add_argument("--session-from", help="可选：复用已成功登录的原 CLI 浏览器会话目录")
    init.add_argument("--without-garmin-password", action="store_true", help="仅配对上传，暂不配置无人值守抓取")
    upload = commands.add_parser("upload", help="从 SQLite 补传；不登录 Garmin")
    upload.add_argument("--db", type=Path)
    upload.add_argument("--since")
    upload.add_argument("--until", default=(date.today() - timedelta(days=1)).isoformat())
    upload.add_argument("--dry-run", action="store_true")
    run = commands.add_parser("sync", help="补传后取最近三天及缺口数据，再上传")
    run.add_argument("--since")
    run.add_argument("--visible", action="store_true", help="显示 Chrome 供人工完成验证")
    cron = commands.add_parser("schedule", help="生成/装载每日定时任务（macOS launchd）")
    cron.add_argument("--hour", type=int, default=10, help="每天几点运行，默认 10")
    cron.add_argument("--minute", type=int, default=30, help="第几分钟运行，默认 30")
    cron.add_argument("--label", help="launchd 标签，多套配置可各用一个")
    cron.add_argument("--print", dest="print_only", action="store_true", help="只打印 plist，不写入系统")
    cron.add_argument("--uninstall", action="store_true", help="卸载定时任务")
    args = parser.parse_args(argv)
    state_dir = args.state_dir.expanduser().resolve()
    try:
        with exclusive_lock(state_dir):
            if args.command == "prepare":
                prepare(state_dir)
                emit({"ok": True, "upstream": UPSTREAM, "stateDir": str(state_dir)})
            elif args.command == "setup":
                setup(args, state_dir)
            elif args.command == "credentials":
                config = configured(state_dir)
                config["garminPassword"] = prompt_secret("Garmin 密码（仅存本机 storage 配置）", "GARMIN_PASSWORD")
                write_private(state_dir / "config.json", config)
                emit({"ok": True, "action": "credentials"})
            elif args.command == "doctor":
                config = read_json(state_dir / "config.json")
                health = api(config.get("server", DEFAULT_SERVER), "/api/system/health")
                label = args.label or DEFAULT_SCHEDULE_LABEL
                plist = plist_path(label)
                installed = plist.exists()
                loaded = launchctl("print", label)[0] if installed and sys.platform == "darwin" else False
                emit({"ok": True, "serverStatus": health.get("status"),
                      "runtimeReady": python_path(state_dir).exists(), "paired": bool(config.get("uploadToken")),
                      "garminCredentialReady": bool(config.get("garminPassword") or os.environ.get("GARMIN_PASSWORD")),
                      "stateDir": str(state_dir), "progress": read_json(state_dir / "progress.json"),
                      # 无人值守是否真的在跑，看这三项：任务装了没、上次跑成什么样、日志尾巴
                      "schedule": {"label": label, "plist": str(plist), "installed": installed, "loaded": loaded},
                      "lastRun": read_json(state_dir / "last-run.json", {}),
                      "logTail": log_tail(state_dir)})
            elif args.command == "schedule":
                schedule(args, state_dir)
            elif args.command == "upload":
                config = read_json(state_dir / "config.json") if args.dry_run else configured(state_dir)
                pending = read_json(state_dir / "progress.json").get("pending")
                if not args.since and pending and not args.db:
                    db, start, end = pending["db"], pending["start"], pending["end"]
                else:
                    db = args.db or Path(config.get("dataDir", str(state_dir / "data"))) / "garmin.db"
                    start = args.since or (date.today() - timedelta(days=3)).isoformat()
                    end = args.until
                emit(upload_range(state_dir, config, db, start, end, args.dry_run))
            else:
                sync(args, state_dir)
        return 0
    except (SyncError, ValueError) as error:
        emit({"ok": False, "error": str(error)})
        return 1
    except Exception as error:
        emit({"ok": False, "error": type(error).__name__, "hint": "运行 doctor 检查环境；不要打印 config.json"})
        return 1


if __name__ == "__main__":
    sys.exit(main())
