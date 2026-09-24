"""`tools/garmin_browser_upload.py` 的映射测试。

这个脚本把上游（garmin-givemydata）的本地 SQLite 映射成平台的 8 类 DTO。
映射错一个字段不会报错，只会让平台静默缺数据 —— 所以逐条钉住。

已捕获的真实缺陷：
- 睡眠起止时间在 `dailySleepDTO` 里、且是**毫秒时间戳**，最初写在顶层当字符串取，
  结果 `sleepStartGmt` 全是 null（平台会因此丢掉整段睡眠时间）。
- 训练状态的 ACWR 与负荷隧道嵌在 `latestTrainingStatusData[设备ID]` 里，
  只挖一层会全部漏掉 —— 而判灯引擎正是靠它。
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sqlite3

import pytest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "tools" / "garmin_browser_upload.py"


@pytest.fixture
def uploader():
    spec = importlib.util.spec_from_file_location("browser_upload", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def conn():
    connection = sqlite3.connect(":memory:")
    connection.executescript(
        """
        CREATE TABLE daily_summary (
            calendar_date TEXT, total_steps INTEGER, total_distance_meters REAL,
            total_kilocalories REAL, active_kilocalories REAL, resting_heart_rate INTEGER,
            min_heart_rate INTEGER, max_heart_rate INTEGER, average_stress_level INTEGER,
            body_battery_highest INTEGER, body_battery_lowest INTEGER);
        CREATE TABLE sleep (
            calendar_date TEXT, sleep_time_seconds INTEGER, deep_sleep_seconds INTEGER,
            light_sleep_seconds INTEGER, rem_sleep_seconds INTEGER, awake_sleep_seconds INTEGER,
            average_spo2 REAL, average_respiration REAL, sleep_score_overall INTEGER,
            raw_json TEXT);
        CREATE TABLE hrv (
            calendar_date TEXT, last_night_avg REAL, weekly_avg REAL, status TEXT,
            baseline_low INTEGER, baseline_upper INTEGER, raw_json TEXT);
        CREATE TABLE training_status (
            calendar_date TEXT, status TEXT, acute_load REAL, chronic_load REAL, raw_json TEXT);
        CREATE TABLE fitness_age (calendar_date TEXT, fitness_age REAL);
        CREATE TABLE activity (
            activity_id INTEGER, activity_name TEXT, activity_type TEXT, activity_type_id INTEGER,
            parent_type_id INTEGER, start_time_local TEXT, start_time_gmt TEXT,
            duration_seconds REAL, moving_duration_seconds REAL, elapsed_duration_seconds REAL,
            distance_meters REAL, elevation_gain REAL, elevation_loss REAL, min_elevation REAL,
            max_elevation REAL, average_speed REAL, max_speed REAL, average_hr REAL, max_hr REAL,
            calories REAL, bmr_calories REAL, avg_power REAL, max_power REAL, norm_power REAL,
            training_stress_score REAL, intensity_factor REAL, aerobic_training_effect REAL,
            anaerobic_training_effect REAL, vo2max_value REAL, avg_cadence REAL, max_cadence REAL,
            lap_count INTEGER, avg_respiration REAL, min_temperature REAL, max_temperature REAL,
            device_id INTEGER, training_load REAL);
        CREATE TABLE activity_hr_zones (
            activity_id INTEGER, zone1_seconds REAL, zone2_seconds REAL, zone3_seconds REAL,
            zone4_seconds REAL, zone5_seconds REAL, raw_json TEXT);
        """
    )
    yield connection
    connection.close()


def test_gmt_millis_becomes_utc_iso(uploader):
    """毫秒时间戳必须转成平台能解析的 ISO；直接塞数字会让时间列全空。"""

    assert uploader.gmt_to_iso(1789230431000) == "2026-09-12T16:27:11"
    assert uploader.gmt_to_iso(None) is None
    assert uploader.gmt_to_iso("1789230431000") is None


def test_sleep_reads_start_and_end_from_daily_sleep_dto(uploader, conn):
    """起止时间在 dailySleepDTO 里、是毫秒 —— 写在顶层取会得到 null（曾踩）。"""

    raw = {
        "avgOvernightHrv": 85,
        "dailySleepDTO": {
            "calendarDate": "2026-09-12",
            "sleepStartTimestampGMT": 1789230431000,
            "sleepEndTimestampGMT": 1789260071000,
            "sleepTimeSeconds": 13495,
            "averageSpO2Value": 93,
            "averageRespirationValue": 14.5,
            "sleepScores": {"overall": {"value": 54}},
        },
    }
    conn.execute("INSERT INTO sleep VALUES (?,?,?,?,?,?,?,?,?,?)",
                 ("2026-09-12", 13495, 1, 2, 3, 4, 93, 14.5, 54, json.dumps(raw)))
    conn.commit()

    rows = uploader.fetch_sleep(conn, "2026-09-12", "2026-09-12")

    assert len(rows) == 1
    assert rows[0]["sleepStartGmt"] == "2026-09-12T16:27:11"
    assert rows[0]["sleepEndGmt"] == "2026-09-13T00:41:11"
    assert rows[0]["sleepScore"] == 54
    assert rows[0]["avgSleepHrv"] == 85      # 顶层 avgOvernightHrv
    assert rows[0]["avgSpo2"] == 93


def test_sleep_without_start_is_skipped(uploader, conn):
    """没有起点的睡眠行要跳过：平台靠起点去重，补一条空起点会累积重复行。"""

    conn.execute("INSERT INTO sleep VALUES (?,?,?,?,?,?,?,?,?,?)",
                 ("2026-09-12", 100, None, None, None, None, None, None, None,
                  json.dumps({"dailySleepDTO": {"calendarDate": "2026-09-12"}})))
    conn.commit()

    assert uploader.fetch_sleep(conn, "2026-09-12", "2026-09-12") == []


def test_training_status_digs_two_levels_for_acwr(uploader, conn):
    """ACWR 与隧道在 latestTrainingStatusData[设备ID] 里，只挖一层会全漏。"""

    raw = {
        "latestTrainingStatusData": {
            "3610031482": {
                "primaryTrainingDevice": True,
                "trainingStatus": 4,
                "trainingStatusFeedbackPhrase": "MAINTAINING_2",
                "acuteTrainingLoadDTO": {
                    "acwrPercent": 33, "acwrStatus": "OPTIMAL",
                    "dailyAcuteChronicWorkloadRatio": 0.8,
                    "dailyTrainingLoadAcute": 520, "dailyTrainingLoadChronic": 635,
                    "minTrainingLoadChronic": 508, "maxTrainingLoadChronic": 952.5,
                },
            }
        }
    }
    conn.execute("INSERT INTO training_status VALUES (?,?,?,?,?)",
                 ("2026-09-23", "MAINTAINING_2", 520, 635, json.dumps(raw)))
    conn.execute("INSERT INTO fitness_age VALUES (?,?)", ("2026-09-23", 18.0))
    conn.commit()

    row = uploader.fetch_training_status(conn, "2026-09-23", "2026-09-23")[0]

    assert row["acwrPercent"] == 33
    assert row["acwrStatus"] == "OPTIMAL"
    assert row["acwrRatio"] == 0.8
    assert row["chronicLoadMin"] == 508 and row["chronicLoadMax"] == 952.5
    assert row["trainingStatusPhrase"] == "MAINTAINING_2"
    assert row["fitnessAge"] == 18.0
    # load focus 一族上游无源，必须显式为 null 而不是漏键
    assert row["loadAerobicLow"] is None and row["loadAerobicLowTargetMin"] is None


def test_hr_zones_transpose_wide_table_and_keep_boundaries(uploader, conn):
    """上游是宽表（zone1…zone5 秒数），要转置成多行，边界从 raw_json 数组取。"""

    raw = [{"zoneNumber": n, "zoneLowBoundary": 100 + 20 * (n - 1)} for n in range(1, 6)]
    conn.execute("INSERT INTO activity_hr_zones VALUES (?,?,?,?,?,?,?)",
                 (555, 4.6, 2018.8, 5113.5, 9897.7, 1197.9, json.dumps(raw)))
    conn.commit()

    zones = uploader.fetch_hr_zones(conn, [555])["555"]

    assert [z["zoneNumber"] for z in zones] == [1, 2, 3, 4, 5]
    assert zones[0]["zoneLowBoundary"] == 100
    assert zones[4]["secondsInZone"] == 1197.9


def test_payload_shape_matches_platform_contract(uploader, conn):
    """键名必须与平台的 SyncIngestRequest 完全一致，缺一个平台就收不到这类数据。"""

    payload = uploader.build_payload(conn, "2026-09-12", "2026-09-12")

    assert set(payload) == {
        "dailyHealth", "sleep", "naps", "hrv", "activities",
        "trainingStatus", "ftpHistory", "activityHrZones",
    }
    # 上游无源的两类必须显式为空列表，而不是 None（平台按 List 解析）
    assert payload["naps"] == [] and payload["ftpHistory"] == []
