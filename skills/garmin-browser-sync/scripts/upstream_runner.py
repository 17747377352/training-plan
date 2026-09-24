"""运行原项目 CLI；在调用边界限制采集范围、清除隐私字段并记录本次取数回执。"""

import json
import logging
import os
import re
import sys
from pathlib import Path


ALLOWED = {
    "daily_summary", "daily_summaries", "sleep", "sleep_detail", "sleep_summaries", "sleep_stats",
    "hrv", "hrv_daily", "training_status_daily", "training_status_weekly", "training_status",
    "activities", "activities_range", "activity_hr_zones",
    # 训练状态映射依赖这两张旁表；网络请求与落盘必须同时放行，才能刷新每日数据。
    "vo2max_trend", "vo2max_running", "vo2max_cycling", "fitness_age",
}
PRIVATE_KEYS = {
    "activityname", "firstname", "lastname", "fullname", "displayname", "username", "email",
    "userprofilepk", "userid", "profileid", "devicename", "lat", "lon", "lng", "position",
}


def scrub(value):
    """清洗后才交给上游落盘；账号定位信息仅在登录客户端内存中使用。"""
    if isinstance(value, list):
        return [scrub(item) for item in value]
    if isinstance(value, dict):
        return {key: scrub(item) for key, item in value.items()
                if key.lower() not in PRIVATE_KEYS
                and not any(word in key.lower() for word in ("latitude", "longitude", "location", "owner", "gps"))}
    return value


def allowed(name):
    name = name.removeprefix("gql_")
    name = re.sub(r"_\d{4}-\d{2}-\d{2}$", "", name)
    return name in ALLOWED or name.startswith("activities_page_")


def main():
    """固定版本依赖仍执行原 CLI main；不复制或修改上游登录实现。"""
    import garmin_givemydata as cli
    from garmin_client import GarminClient

    os.umask(0o077)
    # 上游 DEBUG 会记录 displayName 和页面内容，默认禁用；agent 只读下面的结构化回执。
    logging.disable(logging.CRITICAL)
    manifest = {"success": False, "freshDailyDates": [], "endpoints": {}, "error": None}
    save_original, fetch_original = cli.save_to_db, GarminClient._fetch_batch

    def save(conn, endpoint_name, data, cal_date=None):
        if not allowed(endpoint_name):
            return 0
        count = save_original(conn, endpoint_name, scrub(data), cal_date=cal_date)
        if endpoint_name == "daily_summary" and count > 0 and cal_date:
            manifest["freshDailyDates"].append(cal_date)
        return count

    def fetch(client, rest, gql):
        rest = {key: value for key, value in rest.items() if allowed(key)}
        gql = {key: value for key, value in gql.items() if allowed(key)}
        if not rest and not gql:
            return {}
        result = fetch_original(client, rest, gql)
        expected = list(rest) + ["gql_" + key for key in gql]
        for key in expected:
            response = result.get(key) or {}
            status = response.get("status", "missing")
            manifest["endpoints"][key] = {"status": status, "hasData": bool(response.get("data"))}
            if status in (401, 403, 429):
                # 风控或会话失效立即结束，不由 agent 连续重复登录。
                raise RuntimeError("GARMIN_AUTH_OR_RATE_LIMIT_" + str(status))
        return scrub(result)

    cli.save_to_db = save
    GarminClient._fetch_batch = fetch
    code = 0
    try:
        cli.main()
        manifest["success"] = True
    except SystemExit as error:
        code = error.code or 0
        manifest["success"] = code == 0
        if code:
            manifest["error"] = "GARMIN_LOGIN_OR_FETCH_FAILED"
    except Exception as error:
        code = 1
        # 不输出任意异常正文，异常可能包含页面、账号或请求内容。
        manifest["error"] = str(error) if str(error).startswith("GARMIN_AUTH_OR_RATE_LIMIT_") else type(error).__name__
    finally:
        manifest["freshDailyDates"] = sorted(set(manifest["freshDailyDates"]))
        path = Path(os.environ["GARMIN_RUN_MANIFEST"])
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
        path.chmod(0o600)
    return code


if __name__ == "__main__":
    sys.exit(main())
