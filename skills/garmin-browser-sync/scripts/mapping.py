"""将上游 SQLite 转换为平台白名单 DTO；只读数据库，不输出健康明细。"""

import json
import sqlite3
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


DAILY_FIELDS = {
    "steps": "total_steps", "distanceMeters": "total_distance_meters",
    "totalKilocalories": "total_kilocalories", "activeKilocalories": "active_kilocalories",
    "restingHeartRate": "resting_heart_rate", "minHeartRate": "min_heart_rate",
    "maxHeartRate": "max_heart_rate", "averageStressLevel": "average_stress_level",
    "bodyBatteryHighest": "body_battery_highest", "bodyBatteryLowest": "body_battery_lowest",
}
SLEEP_FIELDS = {
    "sleepTimeSeconds": "sleep_time_seconds", "deepSleepSeconds": "deep_sleep_seconds",
    "lightSleepSeconds": "light_sleep_seconds", "remSleepSeconds": "rem_sleep_seconds",
    "awakeSleepSeconds": "awake_sleep_seconds", "sleepScore": "sleep_score_overall",
    "avgSpo2": "average_spo2", "avgRespiration": "average_respiration",
}
ACTIVITY_FIELDS = {
    "garminActivityId": "activity_id", "activityTypeKey": "activity_type",
    "activityTypeId": "activity_type_id", "parentTypeId": "parent_type_id",
    "durationSeconds": "duration_seconds", "movingDurationSeconds": "moving_duration_seconds",
    "elapsedDurationSeconds": "elapsed_duration_seconds", "distanceMeters": "distance_meters",
    "elevationGain": "elevation_gain", "elevationLoss": "elevation_loss",
    "maxElevation": "max_elevation", "minElevation": "min_elevation",
    "averageSpeed": "average_speed", "maxSpeed": "max_speed", "averageHr": "average_hr",
    "maxHr": "max_hr", "calories": "calories", "bmrCalories": "bmr_calories",
    "avgPower": "avg_power", "maxPower": "max_power", "normPower": "norm_power",
    "intensityFactor": "intensity_factor", "trainingStressScore": "training_stress_score",
    "avgCadence": "avg_cadence", "maxCadence": "max_cadence",
    "aerobicTrainingEffect": "aerobic_training_effect",
    "anaerobicTrainingEffect": "anaerobic_training_effect", "activityTrainingLoad": "training_load",
    "lapCount": "lap_count", "avgRespirationRate": "avg_respiration",
    "minTemperature": "min_temperature", "maxTemperature": "max_temperature",
    "vo2maxValue": "vo2max_value", "deviceId": "device_id",
}


def iso_gmt(value):
    """毫秒时间戳转 GMT；数据库时间文本保留秒精度，不做本地时区猜测。"""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000, timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds")
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo:
        parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return parsed.isoformat(timespec="seconds")


def raw(row):
    """只在内存解析原始响应；对外载荷始终从字段白名单构造。"""
    value = json.loads(row.get("raw_json") or "{}")
    return value


def fields(row, mapping):
    return {key: row.get(column) for key, column in mapping.items() if row.get(column) is not None}


def rows(conn, table, start, end, column="calendar_date"):
    """表名与列名仅由本模块常量提供，日期使用参数绑定。"""
    if not conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
        return []
    return [dict(r) for r in conn.execute(
        f'SELECT * FROM "{table}" WHERE substr("{column}",1,10) BETWEEN ? AND ? ORDER BY "{column}"',
        (start, end))]


# 反解出来的 FTP 必须落在这个区间才算合理。Garmin 的 IF 就是按设备里的 FTP 设置算的，
# 所以 NP/IF 本该等于 FTP；一旦越界就说明这条数据本身有问题（缺功率、IF 异常），
# 与其上报一个会把处方强度带偏的数，不如不给。
FTP_MIN_WATTS, FTP_MAX_WATTS = 80, 700
FTP_SOURCE_DERIVED = "DERIVED"


def implied_ftp(row):
    """按 Garmin 的 IF 定义反解 FTP：`IF = NP / FTP` ⇒ `FTP = NP / IF`。

    上游没有 FTP 接口，但它把 NP 与 IF 都写进了活动汇总，所以当前 FTP 是可以反解的。
    实测三次骑行得到 213.6 / 213.0 / 212.8 W，与令牌路径取到的真实 FTP（213 W）
    相差不到 1 W。缺字段或越界返回 None。
    """
    norm, intensity = row.get("norm_power"), row.get("intensity_factor")
    if not norm or not intensity or intensity <= 0:
        return None
    watts = round(norm / intensity)
    return watts if FTP_MIN_WATTS <= watts <= FTP_MAX_WATTS else None


def derived_ftp_history(candidates):
    """同一天只留一条（平台按「账号 + 生效日期」去重）：取当天时长最长的那次骑行。

    较长骑行里的 IF 通常更稳；同一天两次短骑反而容易给出偏差更大的值。
    """
    best = {}
    for started, duration, watts in candidates:
        day = str(started)[:10]
        if day not in best or (duration or 0) > best[day][0]:
            best[day] = (duration or 0, watts)
    return [{"effectiveDate": day, "ftpWatts": watts, "source": FTP_SOURCE_DERIVED}
            for day, (_, watts) in sorted(best.items())]


def build_payload(db_path, start, end):
    """读取指定日期范围；缺失每日数据时拒绝上报，避免把旧数据当作完整同步。"""
    start_day, end_day = date.fromisoformat(start), date.fromisoformat(end)
    if start_day > end_day or (end_day - start_day).days > 30:
        raise ValueError("每批日期范围必须为 1 至 31 天")
    conn = sqlite3.connect(Path(db_path).resolve().as_uri() + "?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    try:
        if conn.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise ValueError("SQLite 完整性检查失败")
        data = {k: [] for k in ("dailyHealth", "sleep", "naps", "hrv", "activities", "trainingStatus", "ftpHistory")}
        data["activityHrZones"] = {}
        for row in rows(conn, "daily_summary", start, end):
            mapped = fields(row, DAILY_FIELDS)
            if not mapped:
                continue
            data["dailyHealth"].append({"calendarDate": row["calendar_date"], **mapped})
        expected = {(start_day + timedelta(days=i)).isoformat() for i in range((end_day - start_day).days + 1)}
        present = {r["calendarDate"] for r in data["dailyHealth"]}
        if expected - present:
            raise ValueError("缺少每日健康数据：" + ",".join(sorted(expected - present)))

        for row in rows(conn, "sleep", start, end):
            response = raw(row)
            dto = response.get("dailySleepDTO") or {}
            start_time = dto.get("sleepStartTimestampGMT")
            if start_time is not None:
                data["sleep"].append({
                    "calendarDate": row["calendar_date"], **fields(row, SLEEP_FIELDS),
                    "sleepStartGmt": iso_gmt(start_time),
                    "sleepEndGmt": iso_gmt(dto.get("sleepEndTimestampGMT")),
                    "avgSleepHrv": response.get("avgOvernightHrv"),
                })
            elif row.get("sleep_time_seconds"):
                raise ValueError("睡眠记录缺少开始时间：" + row["calendar_date"])
            for nap in dto.get("dailyNapDTOS") or []:
                if not nap.get("napStartTimestampGMT"):
                    raise ValueError("午睡记录缺少开始时间：" + row["calendar_date"])
                data["naps"].append({
                    "calendarDate": nap.get("calendarDate") or row["calendar_date"],
                    "napStartGmt": iso_gmt(nap["napStartTimestampGMT"]),
                    "napEndGmt": iso_gmt(nap.get("napEndTimestampGMT")),
                    "napSeconds": nap.get("napTimeSec"), "napFeedback": nap.get("napFeedback"),
                    "napSource": nap.get("napSource"),
                })
        for row in rows(conn, "hrv", start, end):
            baseline = (raw(row).get("baseline") or {})
            data["hrv"].append({
                "calendarDate": row["calendar_date"], "lastNightAvg": row.get("last_night_avg"),
                "weeklyAvg": row.get("weekly_avg"), "status": row.get("status"),
                "baselineLowUpper": row.get("baseline_low"),
                "baselineBalancedLow": baseline.get("balancedLow"),
                "baselineBalancedUpper": row.get("baseline_upper"),
            })
        # 最大摄氧量与体能年龄是独立表，按日期对齐到训练状态；同一天骑行优先于跑步。
        vo2max = {}
        for row in rows(conn, "vo2max", start, end):
            if row.get("value") is None:
                continue
            if row.get("sport") == "CYCLING" or row["calendar_date"] not in vo2max:
                vo2max[row["calendar_date"]] = row["value"]
        fitness_age = {row["calendar_date"]: int(row["fitness_age"])
                       for row in rows(conn, "fitness_age", start, end) if row.get("fitness_age") is not None}
        for row in rows(conn, "training_status", start, end):
            response = raw(row)
            status = response.get("mostRecentTrainingStatus") or response
            entries = list((status.get("latestTrainingStatusData") or {}).values())
            entry = next((e for e in entries if e.get("primaryTrainingDevice")), entries[0] if entries else {})
            acute = entry.get("acuteTrainingLoadDTO") or {}
            day = row["calendar_date"]
            data["trainingStatus"].append({
                "calendarDate": day, "trainingStatus": entry.get("trainingStatus", row.get("status")),
                # 原始响应缺失时退回上游列，避免整列为空
                "trainingStatusPhrase": entry.get("trainingStatusFeedbackPhrase") or row.get("status"),
                "acuteLoad": acute.get("dailyTrainingLoadAcute", row.get("acute_load")),
                "chronicLoad": acute.get("dailyTrainingLoadChronic", row.get("chronic_load")),
                "acwrPercent": acute.get("acwrPercent"), "acwrStatus": acute.get("acwrStatus"),
                "acwrRatio": acute.get("dailyAcuteChronicWorkloadRatio"),
                "chronicLoadMin": acute.get("minTrainingLoadChronic"),
                "chronicLoadMax": acute.get("maxTrainingLoadChronic"),
                # 这两项在独立表里，与训练状态同按日期对齐
                "vo2maxValue": vo2max.get(day), "fitnessAge": fitness_age.get(day),
            })
        # 上游没有 FTP 接口，但 Garmin 的 IF 定义就是 IF = NP / FTP，所以能用活动里的
        # NP 与 IF 反解出当前 FTP。必须带 source=DERIVED：这是反解值，不是 Garmin 报出的值。
        ftp_candidates = []
        for row in rows(conn, "activity", start, end, "start_time_local"):
            kind = str(row.get("activity_type") or "")
            # 平台以骑行训练为主；不把跑步或游泳混入骑行处方。
            if "cycling" not in kind and "biking" not in kind and row.get("parent_type_id") != 2:
                continue
            if not row.get("activity_id") or not row.get("start_time_gmt"):
                raise ValueError("骑行活动缺少 ID 或 GMT 开始时间")
            # 顺便攒反解 FTP 的候选：上游没有 FTP 接口，但 NP 与 IF 都在活动汇总里
            watts = implied_ftp(row)
            if watts is not None:
                ftp_candidates.append((row["start_time_local"], row.get("duration_seconds"), watts))
            response = raw(row)
            mapped = fields(row, ACTIVITY_FIELDS)
            mapped.update({"activityName": "骑行", "startTimeGmt": iso_gmt(row["start_time_gmt"]),
                           "startTimeLocal": iso_gmt(row["start_time_local"])})
            # 功率区间只在「活动详情」响应里，而上游 0.1.13 不把详情写库
            # （只落 activity 汇总行，raw_json 仅 13 个键）。这段因此当前必然为空，
            # 留作前向兼容：等上游持久化详情后自动生效。校验方式见
            # references/字段映射核对表.md 的「功率区间」一节。
            for number in range(1, 8):
                value = response.get(f"timeInPowerZone{number}")
                if value is not None:
                    mapped[f"powerZone{number}Seconds"] = value
            data["activities"].append(mapped)
        ids = {r["garminActivityId"] for r in data["activities"]}
        if ids and conn.execute("SELECT 1 FROM sqlite_master WHERE name='activity_hr_zones'").fetchone():
            for row in conn.execute("SELECT * FROM activity_hr_zones"):
                if row["activity_id"] not in ids:
                    continue
                response = raw(dict(row))
                zones = response if isinstance(response, list) else response.get("heartRateZones") or []
                data["activityHrZones"][str(row["activity_id"])] = [
                    {"zoneNumber": z["zoneNumber"], "zoneLowBoundary": z.get("zoneLowBoundary"),
                     "secondsInZone": round(z.get("secsInZone") or 0)} for z in zones if z.get("zoneNumber")]
        data["ftpHistory"] = derived_ftp_history(ftp_candidates)
        return {"startDate": start, "endDate": end, "data": data}
    finally:
        conn.close()


def counts(payload):
    """只返回类型与数量，用于日志和 agent 摘要。"""
    return {key: sum(len(rows) for rows in value.values()) if isinstance(value, dict) else len(value)
            for key, value in payload["data"].items()}
