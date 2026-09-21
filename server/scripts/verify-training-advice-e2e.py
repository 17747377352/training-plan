"""在本地开发库建立临时测试账号，验证真实 HTTP/SQL 链路，finally 清理所有测试记录。

运行：uv run --with pymysql --with pyyaml python server/scripts/verify-training-advice-e2e.py
需要已启动后端、MySQL、Redis；不会连接 Garmin 或改动已有账号。
"""
import json
import pathlib
import secrets
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pymysql
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = "http://localhost:8080"


def request(path, method="GET", body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    return response.status, json.load(response)


def main():
    config = yaml.safe_load((ROOT / "server/src/main/resources/application-dev.yml").read_text())
    ds = config["spring"]["datasource"]
    from urllib.parse import urlsplit
    url = urlsplit(ds["url"].removeprefix("jdbc:"))
    conn = pymysql.connect(host=url.hostname, port=url.port or 3306, user=ds["username"],
                           password=ds["password"], database=url.path.lstrip("/"), autocommit=True)
    cur = conn.cursor()
    username = "advice_qa_" + secrets.token_hex(5)
    password = secrets.token_urlsafe(24)
    uid = aid = None
    tokens = None
    passed = 0

    def check(condition, message):
        nonlocal passed
        assert condition, message
        passed += 1
        print(f"PASS {message}")

    try:
        status, _ = request("/api/training-advice")
        check(status == 401, "匿名访问拒绝")
        _, registration = request("/api/auth/register", "POST", {
            "username": username, "email": username + "@example.com", "password": password})
        assert registration["code"] == 200
        cur.execute("SELECT id FROM sys_user WHERE username=%s", (username,))
        uid = cur.fetchone()[0]
        _, login = request("/api/auth/login", "POST", {"account": username, "password": password})
        tokens = login["data"]
        token = tokens["accessToken"]
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
        date = today.isoformat()

        def advice(query=""):
            _, result = request("/api/training-advice" + query, token=token)
            assert result["code"] == 200, result.get("message")
            return result["data"]

        empty = advice()
        check(empty["light"] == "YELLOW" and empty["prescription"]["durationMinutes"] == 0, "无数据黄灯且不安排骑行")
        # 只写入本脚本新建的账号，禁用同步，不存 Garmin 凭据。
        cur.execute("INSERT INTO garmin_account (user_id, region, garmin_email_hash, garmin_email_masked, auth_status, sync_enabled) VALUES (%s,'CN',%s,'qa@example.com','REAUTH_REQUIRED',0)",
                    (uid, secrets.token_hex(32)))
        aid = cur.lastrowid
        cur.execute("INSERT INTO training_status (garmin_account_id,calendar_date,training_status_phrase,acwr_status,acwr_ratio,balance_feedback_phrase) VALUES (%s,%s,'PRODUCTIVE_6','OPTIMAL',1.1,'AEROBIC_LOW_SHORTAGE')", (aid, date))
        cur.execute("INSERT INTO hrv_record (garmin_account_id,calendar_date,last_night_avg,weekly_avg,hrv_status,baseline_balanced_low,baseline_balanced_upper) VALUES (%s,%s,60,60,'BALANCED',50,80)", (aid, date))
        cur.execute("INSERT INTO sleep_record (garmin_account_id,calendar_date,sleep_start_gmt,sleep_time_seconds,sleep_score) VALUES (%s,%s,%s,28800,80)", (aid, date, str(today - timedelta(days=1)) + " 15:00:00"))
        cur.execute("INSERT INTO ftp_history (garmin_account_id,effective_date,ftp_watts) VALUES (%s,%s,213)", (aid, date))
        for rpe, light, kind in [(3, "GREEN", "ENDURANCE"), (7, "YELLOW", "RECOVERY"), (9, "RED", "REST")]:
            _, result = request("/api/checkins/" + date, "PUT", {"rpe": rpe, "weightKg": 71}, token)
            check(result["code"] == 200, f"保存 RPE {rpe}")
            result = advice()
            check(result["light"] == light and result["prescription"]["type"] == kind, f"RPE {rpe} 对应 {light} / {kind}")
            if rpe == 3:
                check(result["wattsPerKg"] == 3.0 and result["prescription"]["steps"][1]["powerMinWatts"] == 128,
                      "FTP 与体重正确换算 W/kg 和瓦数")
        check(advice("?userId=1&garminAccountId=1")["garminAccountId"] == aid, "请求参数不能切换到别人的数据")
        check(advice("?date=" + str(today - timedelta(days=1)))["availableRecoverySignals"] == 0, "历史建议不读取未来数据")
        cur.execute("UPDATE ftp_history SET effective_date=%s WHERE garmin_account_id=%s", (today - timedelta(days=91), aid))
        request("/api/checkins/" + date, "PUT", {"rpe": 3, "weightKg": 71}, token)
        result = advice()
        check(result["light"] == "GREEN" and "powerMinWatts" not in result["prescription"]["steps"][0], "过期 FTP 省略瓦数，不改变恢复灯色")
        request("/api/checkins/" + date, "DELETE", token=token)
        check(advice()["light"] == "YELLOW", "删除疲劳打卡后自动降级为黄灯")
        _, invalid = request("/api/training-advice?date=2026-02-30", token=token)
        check(invalid["code"] == 40000, "无效日期返回参数错误")
        _, future = request("/api/training-advice?date=" + str(today + timedelta(days=1)), token=token)
        check(future["code"] == 40000, "未来日期返回参数错误")
        print(f"全部通过：{passed} 项真实链路断言")
    finally:
        if tokens:
            request("/api/auth/logout", "POST", {"refreshToken": tokens["refreshToken"]}, tokens["accessToken"])
        if aid:
            for table in ("training_status", "hrv_record", "sleep_record", "ftp_history"):
                cur.execute(f"DELETE FROM {table} WHERE garmin_account_id=%s", (aid,))
            cur.execute("DELETE FROM garmin_account WHERE id=%s AND user_id=%s", (aid, uid))
        if uid:
            cur.execute("DELETE FROM daily_checkin WHERE user_id=%s", (uid,))
            cur.execute("DELETE FROM sys_user_role WHERE user_id=%s", (uid,))
            cur.execute("DELETE FROM sys_user WHERE id=%s AND username=%s", (uid, username))
        conn.close()


if __name__ == "__main__":
    main()
