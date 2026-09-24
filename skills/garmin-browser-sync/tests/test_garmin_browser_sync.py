"""验证时间单位、隐私白名单、业务回执和断点补传这些真实失败边界。"""

import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))
import garmin_sync as runner
from mapping import build_payload, iso_gmt
from upstream_runner import allowed, scrub


class MappingTest(unittest.TestCase):
    def test_real_sleep_shape_and_private_activity_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE sleep(calendar_date TEXT, sleep_time_seconds INTEGER, raw_json TEXT);
                CREATE TABLE activity(activity_id INTEGER, activity_type TEXT, start_time_local TEXT,
                    start_time_gmt TEXT, activity_name TEXT, start_latitude REAL, raw_json TEXT);
            """)
            conn.execute("INSERT INTO daily_summary VALUES (?,?)", ("2026-09-23", 1000))
            conn.execute("INSERT INTO sleep VALUES (?,?,?)", ("2026-09-23", 28800, json.dumps({
                "avgOvernightHrv": 50, "dailySleepDTO": {"sleepStartTimestampGMT": 1790114400000,
                "sleepEndTimestampGMT": 1790143200000}})))
            conn.execute("INSERT INTO activity VALUES (?,?,?,?,?,?,?)", (123, "road_biking", "2026-09-23 10:00:00",
                "2026-09-23 02:00:00", "私密地点骑行", 39.9, json.dumps({"startLatitude": 39.9,
                "ownerFullName": "Private Person", "timeInPowerZone1": 50})))
            conn.commit()
            conn.close()
            result = build_payload(db, "2026-09-23", "2026-09-23")
            sleep = result["data"]["sleep"][0]
            self.assertEqual(50, sleep["avgSleepHrv"])
            self.assertEqual("2026-09-22T22:00:00", sleep["sleepStartGmt"])
            self.assertEqual("骑行", result["data"]["activities"][0]["activityName"])
            encoded = json.dumps(result, ensure_ascii=False)
            for private in ("私密地点", "Private Person", "startLatitude", "39.9"):
                self.assertNotIn(private, encoded)
            with self.assertRaisesRegex(ValueError, "缺少每日"):
                build_payload(db, "2026-09-22", "2026-09-23")

    def test_training_status_digs_acwr_out_of_device_bucket(self):
        """ACWR 与负荷隧道藏在 latestTrainingStatusData[设备ID] 里，浅挖一层会全部丢失。

        判灯引擎正是靠这几个值（ACWR 三件套 + 隧道上下界），丢了不会报错，只会让灯变保守。
        """
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE training_status(calendar_date TEXT, status TEXT, acute_load REAL,
                    chronic_load REAL, raw_json TEXT);
            """)
            conn.execute("INSERT INTO daily_summary VALUES (?,?)", ("2026-09-23", 1000))
            conn.execute("INSERT INTO training_status VALUES (?,?,?,?,?)", (
                "2026-09-23", "MAINTAINING_2", 520, 635, json.dumps({
                    "lastPrimarySyncDate": "2026-09-23",
                    "latestTrainingStatusData": {"3610031482": {
                        "primaryTrainingDevice": True,
                        "trainingStatus": 4,
                        "trainingStatusFeedbackPhrase": "MAINTAINING_2",
                        "acuteTrainingLoadDTO": {
                            "acwrPercent": 33, "acwrStatus": "OPTIMAL",
                            "dailyAcuteChronicWorkloadRatio": 0.8,
                            "dailyTrainingLoadAcute": 520, "dailyTrainingLoadChronic": 635,
                            "minTrainingLoadChronic": 508, "maxTrainingLoadChronic": 952.5}}}})))
            conn.commit()
            conn.close()

            row = build_payload(db, "2026-09-23", "2026-09-23")["data"]["trainingStatus"][0]

            self.assertEqual(33, row["acwrPercent"])
            self.assertEqual("OPTIMAL", row["acwrStatus"])
            self.assertEqual(0.8, row["acwrRatio"])
            self.assertEqual(508, row["chronicLoadMin"])
            self.assertEqual(952.5, row["chronicLoadMax"])
            self.assertEqual("MAINTAINING_2", row["trainingStatusPhrase"])

    def test_training_status_carries_vo2max_and_fitness_age_from_side_tables(self):
        """最大摄氧量与体能年龄在独立表里，且同一天骑行/跑步各一行。

        这两项以前没接进来，判灯只能干看着「有氧能力」一栏空着。取错运动类型
        （拿跑步的 VO2max 当骑行的）不会报错，只会让阈值静默偏高一档。
        两个日期分别按「骑行先」与「跑步先」入库，确保结果与入库顺序无关。
        """
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE training_status(calendar_date TEXT, status TEXT, raw_json TEXT);
                CREATE TABLE vo2max(calendar_date TEXT, sport TEXT, value REAL, raw_json TEXT);
                CREATE TABLE fitness_age(calendar_date TEXT, chronological_age INTEGER,
                    fitness_age REAL, achievable_fitness_age REAL);
            """)
            for day in ("2026-09-23", "2026-09-24"):
                conn.execute("INSERT INTO daily_summary VALUES (?,?)", (day, 1000))
                conn.execute("INSERT INTO training_status VALUES (?,?,?)", (day, "MAINTAINING_2", "{}"))
                conn.execute("INSERT INTO fitness_age VALUES (?,?,?,?)", (day, 26, 18.0, 18.0))
            conn.execute("INSERT INTO vo2max VALUES (?,?,?,?)", ("2026-09-23", "CYCLING", 59.3, "{}"))
            conn.execute("INSERT INTO vo2max VALUES (?,?,?,?)", ("2026-09-23", "RUNNING", 52.1, "{}"))
            conn.execute("INSERT INTO vo2max VALUES (?,?,?,?)", ("2026-09-24", "RUNNING", 52.4, "{}"))
            conn.execute("INSERT INTO vo2max VALUES (?,?,?,?)", ("2026-09-24", "CYCLING", 59.9, "{}"))
            conn.commit()
            conn.close()

            rows = build_payload(db, "2026-09-23", "2026-09-24")["data"]["trainingStatus"]

            self.assertEqual([59.3, 59.9], [r["vo2maxValue"] for r in rows])  # 均取骑行
            self.assertEqual([18, 18], [r["fitnessAge"] for r in rows])
            self.assertIsInstance(rows[0]["fitnessAge"], int)

    def test_activity_keeps_device_id(self):
        """设备 ID 是判灯按设备区分数据源用的；漏掉后只能全按「未知设备」处理。"""
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE activity(activity_id INTEGER, activity_type TEXT, start_time_local TEXT,
                    start_time_gmt TEXT, device_id INTEGER, vo2max_value REAL, raw_json TEXT);
            """)
            conn.execute("INSERT INTO daily_summary VALUES (?,?)", ("2026-09-23", 1000))
            conn.execute("INSERT INTO activity VALUES (?,?,?,?,?,?,?)", (123, "road_biking",
                "2026-09-23 10:00:00", "2026-09-23 02:00:00", 3610031482, 59.0, "{}"))
            conn.commit()
            conn.close()

            activity = build_payload(db, "2026-09-23", "2026-09-23")["data"]["activities"][0]

            self.assertEqual(3610031482, activity["deviceId"])
            self.assertEqual(59.0, activity["vo2maxValue"])

    def test_naps_come_from_daily_nap_dtos_array(self):
        """午睡是 dailySleepDTO 里的数组，且只在有午睡时才出现。

        曾经因为「抽样的那天没有午睡」而误判成上游不提供 → 按次起止与来源全丢。
        """
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE sleep(calendar_date TEXT, sleep_time_seconds INTEGER, raw_json TEXT);
            """)
            conn.execute("INSERT INTO daily_summary VALUES (?,?)", ("2026-09-17", 1000))
            conn.execute("INSERT INTO sleep VALUES (?,?,?)", ("2026-09-17", 20000, json.dumps({
                "dailySleepDTO": {
                    "sleepStartTimestampGMT": 1790114400000,
                    "sleepEndTimestampGMT": 1790143200000,
                    "dailyNapDTOS": [{
                        "calendarDate": "2026-09-17",
                        "napStartTimestampGMT": "2026-09-17T05:32:57",
                        "napEndTimestampGMT": "2026-09-17T05:55:57",
                        "napTimeSec": 1380,
                        "napFeedback": "IDEAL_TIMING_IDEAL_DURATION_LOW_NEED",
                        "napSource": 0}]}})))
            conn.commit()
            conn.close()

            naps = build_payload(db, "2026-09-17", "2026-09-17")["data"]["naps"]

            self.assertEqual(1, len(naps))
            self.assertEqual("2026-09-17T05:32:57", naps[0]["napStartGmt"])
            self.assertEqual("2026-09-17T05:55:57", naps[0]["napEndGmt"])
            self.assertEqual(1380, naps[0]["napSeconds"])
            self.assertEqual(0, naps[0]["napSource"])

    def test_hr_zones_transposed_from_raw_array(self):
        """区间存在 raw_json 数组里、秒数是小数：要转成整秒并带上区间下界。"""
        with tempfile.TemporaryDirectory() as directory:
            db = Path(directory) / "garmin.db"
            conn = sqlite3.connect(db)
            conn.executescript("""
                CREATE TABLE daily_summary(calendar_date TEXT, total_steps INTEGER);
                CREATE TABLE activity(activity_id INTEGER, activity_type TEXT, start_time_local TEXT,
                    start_time_gmt TEXT, activity_name TEXT, raw_json TEXT);
                CREATE TABLE activity_hr_zones(activity_id INTEGER, zone1_seconds REAL, raw_json TEXT);
            """)
            conn.execute("INSERT INTO daily_summary VALUES (?,?)", ("2026-09-23", 1000))
            conn.execute("INSERT INTO activity VALUES (?,?,?,?,?,?)", (
                123, "road_biking", "2026-09-23 10:00:00", "2026-09-23 02:00:00", "骑行", "{}"))
            conn.execute("INSERT INTO activity_hr_zones VALUES (?,?,?)", (123, 4.618, json.dumps([
                {"zoneNumber": 1, "zoneLowBoundary": 100, "secsInZone": 4.618},
                {"zoneNumber": 2, "zoneLowBoundary": 119, "secsInZone": 2018.877}])))
            conn.commit()
            conn.close()

            zones = build_payload(db, "2026-09-23", "2026-09-23")["data"]["activityHrZones"]["123"]

            self.assertEqual([1, 2], [z["zoneNumber"] for z in zones])
            self.assertEqual(100, zones[0]["zoneLowBoundary"])
            self.assertEqual(5, zones[0]["secondsInZone"])       # round(4.618)
            self.assertEqual(2019, zones[1]["secondsInZone"])    # round(2018.877)

    def test_timestamp_conversion_uses_utc_and_not_machine_timezone(self):
        self.assertEqual("1970-01-01T00:00:01", iso_gmt(1000))
        self.assertEqual("2026-09-23T00:00:00", iso_gmt("2026-09-23T08:00:00+08:00"))

    def test_upstream_boundary_excludes_profile_gps_and_person_names(self):
        self.assertFalse(allowed("user_profile"))
        self.assertFalse(allowed("activity_trackpoints"))
        self.assertTrue(allowed("gql_training_status_daily_2026-09-23"))
        self.assertEqual({"steps": 20, "nested": [{"hr": 60}]}, scrub({"steps": 20,
            "ownerFullName": "name", "nested": [{"startLatitude": 1, "locationName": "place", "hr": 60}]}))


class UploadTest(unittest.TestCase):
    def test_http_200_business_failure_is_not_success(self):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"code":40100,"message":"denied"}')

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaisesRegex(runner.SyncError, "40100"):
                runner.api(f"http://127.0.0.1:{server.server_port}", "/ingest", {}, "test", attempts=3)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_failure_preserves_remaining_range_and_only_acknowledged_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            config = {"server": "https://example.test", "uploadToken": "test"}
            payload = {"data": {"dailyHealth": [1], "activities": []}}
            with patch.object(runner, "build_payload", return_value=payload), patch.object(
                    runner, "api", side_effect=[123, runner.SyncError("network")]):
                with self.assertRaises(runner.SyncError):
                    runner.upload_range(state, config, state / "db", "2026-09-01", "2026-09-08")
            progress = runner.read_json(state / "progress.json")
            self.assertEqual("2026-09-07", progress["lastUploadedDate"])
            self.assertEqual("2026-09-08", progress["pending"]["start"])
            self.assertEqual(0o600, (state / "progress.json").stat().st_mode & 0o777)
            with patch.object(runner, "build_payload", return_value=payload), patch.object(runner, "api", return_value=124):
                result = runner.upload_range(state, config, state / "db", "2026-09-08", "2026-09-08")
            self.assertEqual([124], result["jobIds"])
            self.assertIsNone(runner.read_json(state / "progress.json")["pending"])

    def test_bad_receipt_does_not_advance_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with patch.object(runner, "build_payload", return_value={"data": {"dailyHealth": [1]}}), patch.object(
                    runner, "api", return_value=None):
                with self.assertRaises(runner.SyncError):
                    runner.upload_range(state, {"server": "https://example.test", "uploadToken": "test"},
                                        state / "db", "2026-09-01", "2026-09-01")
            progress = runner.read_json(state / "progress.json")
            self.assertNotIn("lastUploadedDate", progress)
            self.assertIsNotNone(progress["pending"])

    def test_parallel_run_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with runner.exclusive_lock(Path(directory)):
                with self.assertRaises(runner.SyncError):
                    with runner.exclusive_lock(Path(directory)):
                        self.fail("同一状态目录不能重复运行")


if __name__ == "__main__":
    unittest.main()
