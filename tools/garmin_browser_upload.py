#!/usr/bin/env python3
"""把 garmin-givemydata 取回的本地数据推送到训练计划平台。

为什么要这个脚本
----------------
Garmin 从 2026 年 3 月起加强了 Cloudflare 防护，程序化登录基本失效；唯一被验证可用的
模式是「反检测浏览器取数」（garmin-givemydata 就是这么做，并且已经在本机跑通）。
所以数据链路改成：

    本机浏览器取数（givemydata）→ 本地 SQLite → 本脚本 → 平台入库

平台侧不再需要持有 Garmin 令牌，Garmin 密码与浏览器会话完全留在本机。

与平台的两次交互
----------------
1. 配对（一次性）：用平台页面领到的配对码换取「上传凭据」，凭据只绑定一个账号，
   存在本机（权限 600），之后每次上传直接复用，不必再领码。
2. 上传：带 `X-Garmin-Upload-Token` 头把 8 类数据 POST 给平台，平台负责去重与覆盖更新。

用法
----
    # 首次：领一个配对码，配对并同时上传
    python3 garmin_browser_upload.py --pair-code ABCD2345 --email you@example.com --days 3

    # 以后：凭据已在本地，直接上传
    python3 garmin_browser_upload.py --days 3

    # 指定库里已有的数据目录 / 平台地址
    python3 garmin_browser_upload.py --db ~/data/garmin.db --server http://127.0.0.1:8099

设计取舍（已记录在 docs/字段映射核对表.md）
------------------------------------------
- 上游 `training_readiness` / `endurance_score` / `hill_score` / `load_focus` 四张表
  CLI 从来没取过，而平台需要其中 load_focus 的 6 个字段 → 目前按缺失处理（传 null）。
- 上游没有骑行 FTP 表 → ftpHistory 传空；平台侧 FTP 为空时处方只能按心率换算。
- 午睡只有当日合计，没有按次的起止时间 → naps 传空，避免把合计伪装成单次记录。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sqlite3
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone

DEFAULT_STATE_FILE = pathlib.Path.home() / ".training-plan" / "browser-upload.json"
DEFAULT_CHUNK_DAYS = 7
MAX_RANGE_DAYS = 30  # 平台侧校验：单次上传区间不得超过 30 天


# --------------------------------------------------------------------------
# 平台交互
# --------------------------------------------------------------------------

def post_json(url: str, payload, token: str | None = None, timeout: int = 180):
    """向平台发一次 JSON 请求，返回 (http_status, body)。"""

    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("X-Garmin-Upload-Token", token)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        raw = exception.read().decode("utf-8", errors="replace")
        try:
            return exception.code, json.loads(raw)
        except json.JSONDecodeError:
            return exception.code, {"message": raw[:200]}


def load_state() -> dict:
    if DEFAULT_STATE_FILE.exists():
        try:
            return json.loads(DEFAULT_STATE_FILE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return {}
    return {}


def save_state(state: dict) -> None:
    DEFAULT_STATE_FILE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    DEFAULT_STATE_FILE.touch(mode=0o600, exist_ok=True)
    DEFAULT_STATE_FILE.chmod(0o600)
    DEFAULT_STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


# --------------------------------------------------------------------------
# 从上游 SQLite 取值
# --------------------------------------------------------------------------

def parse_raw(value) -> dict:
    """上游每张表都存了原始 Garmin 响应，很多字段只能从这里取。"""

    if not value:
        return {}
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def query(conn: sqlite3.Connection, sql: str, params=()) -> list[dict]:
    conn.row_factory = sqlite3.Row
    return [dict(row) for row in conn.execute(sql, params).fetchall()]


def fetch_daily_health(conn, start: str, end: str) -> list[dict]:
    rows = query(conn, "SELECT * FROM daily_summary WHERE calendar_date BETWEEN ? AND ? "
                       "ORDER BY calendar_date", (start, end))
    return [{
        "calendarDate": r["calendar_date"],
        "steps": r["total_steps"],
        "distanceMeters": r["total_distance_meters"],
        "totalKilocalories": r["total_kilocalories"],
        "activeKilocalories": r["active_kilocalories"],
        "restingHeartRate": r["resting_heart_rate"],
        "minHeartRate": r["min_heart_rate"],
        "maxHeartRate": r["max_heart_rate"],
        "averageStressLevel": r["average_stress_level"],
        "bodyBatteryHighest": r["body_battery_highest"],
        "bodyBatteryLowest": r["body_battery_lowest"],
    } for r in rows]


def gmt_to_iso(millis) -> str | None:
    """Garmin 的毫秒时间戳 → GMT ISO 字符串。

    与采集器 `sync_worker._gmt_to_iso` 保持一致：平台侧统一按 ISO 解析，
    直接把毫秒数塞进去会让时间列为空。
    """

    if not isinstance(millis, (int, float)):
        return None
    return datetime.fromtimestamp(millis / 1000, tz=timezone.utc).replace(tzinfo=None).isoformat()


def fetch_sleep(conn, start: str, end: str) -> list[dict]:
    # 睡眠 HRV 不在 sleep 表里，取 hrv 表的 last_night_avg（与平台原有实现一致）
    hrv_by_date = {r["calendar_date"]: r["last_night_avg"]
                   for r in query(conn, "SELECT calendar_date, last_night_avg FROM hrv "
                                        "WHERE calendar_date BETWEEN ? AND ?", (start, end))}
    rows = query(conn, "SELECT * FROM sleep WHERE calendar_date BETWEEN ? AND ? "
                       "ORDER BY calendar_date", (start, end))
    result = []
    for r in rows:
        raw = parse_raw(r["raw_json"])
        # 起止时间是 dailySleepDTO 里的毫秒时间戳（不是顶层字符串，实测确认）
        dto = raw.get("dailySleepDTO") if isinstance(raw.get("dailySleepDTO"), dict) else {}
        start_ms = dto.get("sleepStartTimestampGMT")
        if start_ms is None:
            # 没有起点的睡眠记录平台会按无起点处理，这里跳过以免产生重复行
            continue
        result.append({
            "calendarDate": dto.get("calendarDate") or r["calendar_date"],
            "sleepStartGmt": gmt_to_iso(start_ms),
            "sleepEndGmt": gmt_to_iso(dto.get("sleepEndTimestampGMT")),
            # 优先用原始响应，缺失时退回上游规范化列
            "sleepTimeSeconds": dto.get("sleepTimeSeconds", r["sleep_time_seconds"]),
            "deepSleepSeconds": dto.get("deepSleepSeconds", r["deep_sleep_seconds"]),
            "lightSleepSeconds": dto.get("lightSleepSeconds", r["light_sleep_seconds"]),
            "remSleepSeconds": dto.get("remSleepSeconds", r["rem_sleep_seconds"]),
            "awakeSleepSeconds": dto.get("awakeSleepSeconds", r["awake_sleep_seconds"]),
            "sleepScore": ((dto.get("sleepScores") or {}).get("overall") or {}).get("value")
                          or r["sleep_score_overall"],
            "avgSleepHrv": raw.get("avgOvernightHrv", hrv_by_date.get(r["calendar_date"])),
            "avgSpo2": dto.get("averageSpO2Value") or r["average_spo2"],
            "avgRespiration": dto.get("averageRespirationValue", r["average_respiration"]),
        })
    return result


def fetch_hrv(conn, start: str, end: str) -> list[dict]:
    rows = query(conn, "SELECT * FROM hrv WHERE calendar_date BETWEEN ? AND ? "
                       "ORDER BY calendar_date", (start, end))
    result = []
    for r in rows:
        raw = parse_raw(r["raw_json"])
        result.append({
            "calendarDate": r["calendar_date"],
            "lastNightAvg": r["last_night_avg"],
            "weeklyAvg": r["weekly_avg"],
            "status": r["status"],
            # 上游列叫 baseline_upper，原始响应里是 baselineLowUpper
            "baselineLowUpper": raw.get("baselineLowUpper", r["baseline_upper"]),
            "baselineBalancedLow": raw.get("baselineBalancedLow"),      # 上游未提供
            "baselineBalancedUpper": raw.get("baselineBalancedUpper"),
        })
    return result


def fetch_training_status(conn, start: str, end: str) -> list[dict]:
    fitness_age = {r["calendar_date"]: r["fitness_age"]
                   for r in query(conn, "SELECT calendar_date, fitness_age FROM fitness_age "
                                        "WHERE calendar_date BETWEEN ? AND ?", (start, end))}
    rows = query(conn, "SELECT * FROM training_status WHERE calendar_date BETWEEN ? AND ? "
                       "ORDER BY calendar_date", (start, end))
    result = []
    for r in rows:
        raw = parse_raw(r["raw_json"])
        # ACWR 与负荷隧道嵌在 latestTrainingStatusData[设备ID] 里，需要再挖一层
        buckets = raw.get("latestTrainingStatusData") or {}
        entry = {}
        if isinstance(buckets, dict):
            # 优先取 primaryTrainingDevice，否则取第一个
            entry = next((v for v in buckets.values()
                          if isinstance(v, dict) and v.get("primaryTrainingDevice")), None) \
                or next((v for v in buckets.values() if isinstance(v, dict)), {})
        acwr = entry.get("acuteTrainingLoadDTO") if isinstance(entry, dict) else None
        acwr = acwr if isinstance(acwr, dict) else {}
        result.append({
            "calendarDate": r["calendar_date"],
            "trainingStatus": entry.get("trainingStatus"),
            "trainingStatusPhrase": entry.get("trainingStatusFeedbackPhrase") or r["status"],
            "acwrPercent": acwr.get("acwrPercent"),
            "acwrStatus": acwr.get("acwrStatus"),
            "acwrRatio": acwr.get("dailyAcuteChronicWorkloadRatio"),
            "acuteLoad": acwr.get("dailyTrainingLoadAcute", r["acute_load"]),
            "chronicLoad": acwr.get("dailyTrainingLoadChronic", r["chronic_load"]),
            "chronicLoadMin": acwr.get("minTrainingLoadChronic"),
            "chronicLoadMax": acwr.get("maxTrainingLoadChronic"),
            # 以下 6 项来自 load focus，上游从未取过该数据，且没有写入代码 → 传 null
            "loadAerobicLow": None,
            "loadAerobicLowTargetMin": None,
            "loadAerobicLowTargetMax": None,
            "loadAerobicHigh": None,
            "loadAerobicHighTargetMin": None,
            "loadAerobicHighTargetMax": None,
            "loadAnaerobic": None,
            "loadAnaerobicTargetMin": None,
            "loadAnaerobicTargetMax": None,
            "balanceFeedbackPhrase": None,
            "vo2maxValue": entry.get("vo2MaxValue"),
            "fitnessAge": fitness_age.get(r["calendar_date"]),
        })
    return result


def fetch_activities(conn, start: str, end: str) -> list[dict]:
    rows = query(conn, "SELECT * FROM activity WHERE DATE(start_time_local) BETWEEN ? AND ? "
                       "ORDER BY start_time_local", (start, end))
    return [{
        "garminActivityId": r["activity_id"],
        "activityTypeKey": r["activity_type"],
        "activityTypeId": r["activity_type_id"],
        "parentTypeId": r["parent_type_id"],
        "activityName": r["activity_name"],
        "startTimeGmt": r["start_time_gmt"],
        "startTimeLocal": r["start_time_local"],
        "durationSeconds": r["duration_seconds"],
        "movingDurationSeconds": r["moving_duration_seconds"],
        "elapsedDurationSeconds": r["elapsed_duration_seconds"],
        "distanceMeters": r["distance_meters"],
        "elevationGain": r["elevation_gain"],
        "elevationLoss": r["elevation_loss"],
        "avgElevation": None,
        "maxElevation": r["max_elevation"],
        "minElevation": r["min_elevation"],
        "averageSpeed": r["average_speed"],
        "maxSpeed": r["max_speed"],
        "averageHr": r["average_hr"],
        "maxHr": r["max_hr"],
        "calories": r["calories"],
        "bmrCalories": r["bmr_calories"],
        "avgPower": r["avg_power"],
        "maxPower": r["max_power"],
        "normPower": r["norm_power"],
        # 上游没有这两项列，也没有功率区间表（只有心率区间）
        "max20minPower": None,
        "intensityFactor": r["intensity_factor"],
        "trainingStressScore": r["training_stress_score"],
        "avgCadence": r["avg_cadence"],
        "maxCadence": r["max_cadence"],
        "avgLeftBalance": None,
        "aerobicTrainingEffect": r["aerobic_training_effect"],
        "anaerobicTrainingEffect": r["anaerobic_training_effect"],
        "trainingEffectLabel": None,
        "activityTrainingLoad": r["training_load"],
        "powerZone1Seconds": None,
        "powerZone2Seconds": None,
        "powerZone3Seconds": None,
        "powerZone4Seconds": None,
        "powerZone5Seconds": None,
        "powerZone6Seconds": None,
        "powerZone7Seconds": None,
        "lapCount": r["lap_count"],
        "strokes": None,
        "avgRespirationRate": r["avg_respiration"],
        "minTemperature": r["min_temperature"],
        "maxTemperature": r["max_temperature"],
        "vo2maxValue": r["vo2max_value"],
        "deviceId": r["device_id"],
    } for r in rows]


def fetch_hr_zones(conn, activity_ids: list[int]) -> dict[str, list[dict]]:
    """上游是宽表（zone1_seconds…zone5_seconds），需要转置成多行。"""

    if not activity_ids:
        return {}
    placeholders = ",".join("?" * len(activity_ids))
    rows = query(conn, f"SELECT * FROM activity_hr_zones WHERE activity_id IN ({placeholders})",
                 activity_ids)
    result: dict[str, list[dict]] = {}
    for r in rows:
        # raw_json 存的是数组，元素带 zoneLowBoundary
        raw = r.get("raw_json")
        boundaries: dict[int, int] = {}
        try:
            parsed = json.loads(raw) if raw else []
            if isinstance(parsed, list):
                for zone in parsed:
                    if isinstance(zone, dict) and zone.get("zoneNumber") is not None:
                        boundaries[int(zone["zoneNumber"])] = zone.get("zoneLowBoundary")
        except (json.JSONDecodeError, TypeError, ValueError):
            pass
        zones = []
        for number in range(1, 6):
            seconds = r.get(f"zone{number}_seconds")
            if seconds is None:
                continue
            zones.append({
                "zoneNumber": number,
                "zoneLowBoundary": boundaries.get(number),
                "secondsInZone": seconds,
            })
        if zones:
            result[str(r["activity_id"])] = zones
    return result


def build_payload(conn, start: str, end: str) -> dict:
    activities = fetch_activities(conn, start, end)
    return {
        "dailyHealth": fetch_daily_health(conn, start, end),
        "sleep": fetch_sleep(conn, start, end),
        "naps": [],                    # 上游只有当日合计，没有按次记录 → 传空
        "hrv": fetch_hrv(conn, start, end),
        "activities": activities,
        "trainingStatus": fetch_training_status(conn, start, end),
        "ftpHistory": [],              # 上游没有骑行 FTP 表 → 传空
        "activityHrZones": fetch_hr_zones(conn, [a["garminActivityId"] for a in activities]),
    }


def count_rows(payload: dict) -> int:
    total = 0
    for key, value in payload.items():
        if key == "activityHrZones":
            total += sum(len(v) for v in value.values())
        else:
            total += len(value or [])
    return total


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

def resolve_db_path(explicit: str | None) -> pathlib.Path:
    if explicit:
        return pathlib.Path(explicit).expanduser()
    import os
    data_dir = os.environ.get("GARMIN_DATA_DIR")
    if data_dir:
        return pathlib.Path(data_dir).expanduser() / "garmin.db"
    return pathlib.Path.home() / ".garmin-givemydata" / "garmin.db"


def main() -> int:
    parser = argparse.ArgumentParser(description="把本机浏览器取到的 Garmin 数据推送到训练计划平台。")
    parser.add_argument("--server", default="https://songtop.xyz/planapi", help="平台地址")
    parser.add_argument("--db", default=None, help="上游 garmin.db 路径（默认取 GARMIN_DATA_DIR）")
    parser.add_argument("--pair-code", default=None, help="首次配对用的一次性码")
    parser.add_argument("--email", default=None, help="Garmin 邮箱（仅配对时需要）")
    parser.add_argument("--region", default="GLOBAL", choices=("GLOBAL", "CN"), help="站点")
    parser.add_argument("--days", type=int, default=3, help="回溯天数，默认 3（覆盖延迟与修正）")
    parser.add_argument("--start", default=None, help="起始日期 YYYY-MM-DD（与 --days 二选一）")
    parser.add_argument("--end", default=None, help="结束日期 YYYY-MM-DD，默认今天")
    parser.add_argument("--dry-run", action="store_true", help="只统计不上传")
    args = parser.parse_args()

    state = load_state()
    token = state.get("uploadToken")
    base = args.server.rstrip("/")

    # 1. 配对：换一份只绑定一个账号的上传凭据，之后复用
    if args.pair_code:
        if not args.email:
            print("配对需要同时提供 --email", file=sys.stderr)
            return 2
        status, body = post_json(f"{base}/api/garmin/browser-upload/pair", {
            "code": args.pair_code.strip().upper(),
            "email": args.email.strip(),
            "region": args.region,
        })
        if body.get("code") != 200:
            print(f"配对失败（HTTP {status}）：{body.get('message')}", file=sys.stderr)
            return 1
        data = body.get("data") or {}
        # 平台字段名是 uploadToken（BrowserUploadPairResult），令牌只在签发时返回一次
        token = data.get("uploadToken") or data.get("token")
        state.update({"uploadToken": token, "accountId": data.get("accountId"),
                      "email": args.email.strip(), "region": args.region, "server": base})
        save_state(state)
        print(f"配对成功：accountId={data.get('accountId')}，凭据已保存到 {DEFAULT_STATE_FILE}")

    # --dry-run 只做映射统计，不需要凭据
    if not token and not args.dry_run:
        print("还没有上传凭据。请先在平台页面领一个配对码，然后用 --pair-code 运行一次。", file=sys.stderr)
        return 2

    # 2. 算出区间
    end = datetime.strptime(args.end, "%Y-%m-%d").date() if args.end else date.today()
    if args.start:
        start = datetime.strptime(args.start, "%Y-%m-%d").date()
    else:
        start = end - timedelta(days=max(1, args.days) - 1)
    if (end - start).days + 1 > MAX_RANGE_DAYS:
        print(f"区间超过 {MAX_RANGE_DAYS} 天，平台会拒绝；请用 --start/--end 分段。", file=sys.stderr)
        return 2

    db_path = resolve_db_path(args.db)
    if not db_path.exists():
        print(f"找不到本地库：{db_path}（先用 givemydata 取一次数）", file=sys.stderr)
        return 2

    # 3. 取数 → 上传（按 7 天分段，落在平台允许的区间内）
    conn = sqlite3.connect(str(db_path))
    total_uploaded = 0
    uploaded_days: list[str] = []
    cursor = start
    try:
        while cursor <= end:
            chunk_end = min(cursor + timedelta(days=DEFAULT_CHUNK_DAYS - 1), end)
            payload = build_payload(conn, cursor.isoformat(), chunk_end.isoformat())
            rows = count_rows(payload)
            if args.dry_run:
                print(f"  [dry-run] {cursor} ~ {chunk_end}：{rows} 行")
            elif rows == 0:
                print(f"  {cursor} ~ {chunk_end}：本地无数据，跳过")
            else:
                status, body = post_json(f"{base}/api/garmin/browser-upload/ingest", {
                    "startDate": cursor.isoformat(),
                    "endDate": chunk_end.isoformat(),
                    "data": payload,
                }, token=token)
                if body.get("code") != 200:
                    print(f"  上传失败（HTTP {status}）{cursor} ~ {chunk_end}：{body.get('message')}",
                          file=sys.stderr)
                    return 1
                job_id = body.get("data")
                total_uploaded += rows
                uploaded_days.append(cursor.isoformat())
                print(f"  已上传 {cursor} ~ {chunk_end}：{rows} 行（平台任务 {job_id}）")
                state["lastUploadedDate"] = chunk_end.isoformat()
                state["lastJobId"] = job_id
                save_state(state)
            cursor = chunk_end + timedelta(days=1)
    finally:
        conn.close()

    print(f"完成：共上传 {total_uploaded} 行")
    return 0


if __name__ == "__main__":
    sys.exit(main())
