"""验证时间单位、隐私白名单、业务回执和断点补传这些真实失败边界。"""

import argparse
import contextlib
import io
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from datetime import timedelta
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


class ScheduleTest(unittest.TestCase):
    """定时任务跑在无人看见的地方：配置错了只会静默不执行，所以逐项钉住。"""

    def spec(self, hour=10, minute=30):
        return runner.schedule_plist("/opt/homebrew/bin/python3", Path("/skill"),
                                     Path("/state"), hour, minute, "com.example.job")

    def test_plist_runs_daily_sync_on_the_configured_state_dir(self):
        spec = self.spec()
        self.assertEqual("com.example.job", spec["Label"])
        self.assertEqual(["/usr/bin/env", "python3", "/skill/scripts/garmin_sync.py",
                          "--state-dir", "/state", "sync"], spec["ProgramArguments"])
        self.assertEqual({"Hour": 10, "Minute": 30}, spec["StartCalendarInterval"])
        self.assertEqual("/state/logs/schedule.log", spec["StandardOutPath"])
        # 装载即跑会让「改个时间」意外触发一次真实取数
        self.assertFalse(spec["RunAtLoad"])

    def test_plist_does_not_pin_a_homebrew_cellar_interpreter(self):
        """Homebrew 升级后 Cellar 里的版本目录会消失，写死路径会让任务永久静默失败。

        `sys.executable` 在本机就是 Cellar 路径，所以这里专门按那种形状传进去。
        """
        cellar = "/opt/homebrew/Cellar/python@3.14/3.14.3_1/Frameworks/Python.framework/Versions/3.14/bin/python3.14"
        spec = runner.schedule_plist(cellar, Path("/skill"), Path("/state"), 10, 30, "com.example.job")
        self.assertNotIn("Cellar", " ".join(spec["ProgramArguments"]))
        self.assertEqual(["/usr/bin/env", "python3", "/skill/scripts/garmin_sync.py",
                          "--state-dir", "/state", "sync"], spec["ProgramArguments"])
        path = spec["EnvironmentVariables"]["PATH"]
        # launchd 环境极简：PATH 里必须能找到解释器、Chrome 所在的 brew 前缀与 uv
        self.assertIn("/opt/homebrew/bin", path)
        self.assertIn(".local/bin", path)

    def test_plist_rejects_impossible_time(self):
        with self.assertRaisesRegex(runner.SyncError, "定时时间无效"):
            self.spec(hour=24)
        with self.assertRaisesRegex(runner.SyncError, "定时时间无效"):
            self.spec(minute=60)

    def test_plist_is_valid_xml_for_launchd(self):
        spec = self.spec()
        if not hasattr(runner, "plistlib"):  # 兜底：模块必须暴露 plistlib 才能编码
            self.fail("garmin_sync 未导入 plistlib")
        decoded = runner.plistlib.loads(runner.plistlib.dumps(spec))
        self.assertEqual(spec["Label"], decoded["Label"])
        self.assertEqual(spec["ProgramArguments"], decoded["ProgramArguments"])

    def test_install_refuses_an_unready_state_dir(self):
        """未配对或没配密码时装上定时任务，只会得到每天静默失败的任务 —— 装载时就该报错。

        卸载不能受这条限制：任务已经坏了还得能摘掉。
        """
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with patch.object(runner, "plist_path", return_value=state / "job.plist"), \
                 patch.object(runner, "launchctl") as launchctl:
                with self.assertRaisesRegex(runner.SyncError, "尚未配对"):
                    runner.schedule(argparse.Namespace(label="com.example.job", hour=10, minute=30,
                                                       print_only=False, uninstall=False), state)
                self.assertFalse(launchctl.called, "未就绪时不应该碰 launchctl")
                self.assertFalse((state / "job.plist").exists(), "未就绪时不应该写出 plist")
                # 只写配对、没有密码 → 仍然拒绝
                runner.write_private(state / "config.json",
                                     {"server": "https://example.test", "uploadToken": "t"})
                with self.assertRaisesRegex(runner.SyncError, "尚未配置本机 Garmin 密码"):
                    runner.schedule(argparse.Namespace(label="com.example.job", hour=10, minute=30,
                                                       print_only=False, uninstall=False), state)
                # 卸载不受限制
                with contextlib.redirect_stdout(io.StringIO()):
                    runner.schedule(argparse.Namespace(label="com.example.job", print_only=False,
                                                      uninstall=True), state)


class SyncGuardTest(unittest.TestCase):
    """定时任务无人看着跑，`sync` 的闸门就是「不许静默降级」的保证：

    缺新鲜每日数据、活动列表请求失败，都必须报错并且**不推进检查点**，否则第二天会以为
    这段已经同步过，缺口就永久留在那里。
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name)
        runner.write_private(self.state / "config.json", {
            "server": "https://example.test", "uploadToken": "t", "garminPassword": "p",
            "dataDir": str(self.state / "data")})
        # 日期按真实「今天」推算：sync 只上传到昨天为止，写死日期会让用例过几天就失效
        self.end = runner.date.today() - timedelta(days=1)
        self.start = self.end - timedelta(days=2)
        self.dates = [(self.start + timedelta(days=i)).isoformat() for i in range(3)]

    def tearDown(self):
        self.tmp.cleanup()

    def run_sync(self, manifest):
        with patch.object(runner, "capture", return_value=manifest), \
             patch.object(runner, "upload_range", return_value={"jobIds": [7]}) as upload:
            with contextlib.redirect_stdout(io.StringIO()):
                try:
                    runner.sync(argparse.Namespace(since=self.start.isoformat(), visible=False), self.state)
                except runner.SyncError as error:
                    return upload, str(error)
        return upload, None

    def test_missing_daily_data_blocks_checkpoint(self):
        upload, error = self.run_sync({"freshDailyDates": self.dates[:2],
                                       "endpoints": {"activities": {"status": 200}}})
        self.assertIn("未取得新鲜每日数据", error)
        self.assertIn(self.dates[2], error)          # 缺的是哪一天要说清楚
        self.assertFalse(upload.called, "缺数据时不该上传")
        self.assertNotIn("lastFetchedDate", runner.read_json(self.state / "progress.json"))

    def test_failed_activity_list_is_not_treated_as_no_activities(self):
        """活动接口挂了会把「取数失败」伪装成「这几天没骑车」，必须拒绝而不是传空列表。"""
        upload, error = self.run_sync({"freshDailyDates": self.dates,
                                       "endpoints": {"activities": {"status": 500}}})
        self.assertIn("活动列表本次取数失败", error)
        self.assertFalse(upload.called)
        self.assertNotIn("lastFetchedDate", runner.read_json(self.state / "progress.json"))

    def test_complete_data_advances_checkpoint_and_uploads(self):
        upload, error = self.run_sync({"freshDailyDates": self.dates,
                                       "endpoints": {"activities": {"status": 200}}})
        self.assertIsNone(error)
        self.assertTrue(upload.called)
        # 上传区间必须是「起点 ~ 昨天」：当天不传，因为当天数据还在变
        self.assertEqual((self.start.isoformat(), self.end.isoformat()), upload.call_args[0][3:5])
        self.assertEqual(self.end.isoformat(),
                         runner.read_json(self.state / "progress.json")["lastFetchedDate"])


class DoctorTest(unittest.TestCase):
    """`doctor` 是判断「无人值守到底有没有在跑」的入口，三项都不能缺。"""

    def doctor(self, state, extra=()):
        argv = ["--state-dir", str(state), "doctor", *extra]
        with patch.object(runner, "api", return_value={"status": "UP"}), \
             patch.object(runner, "plist_path", return_value=state / "job.plist"), \
             patch.object(runner, "launchctl", return_value=(True, "")), \
             contextlib.redirect_stdout(io.StringIO()) as out:
            code = runner.main(argv)
        self.assertEqual(0, code)
        return json.loads(out.getvalue())

    def test_doctor_reports_schedule_state_and_last_run(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            runner.write_private(state / "config.json",
                                 {"server": "https://example.test", "uploadToken": "t"})
            runner.write_private(state / "last-run.json",
                                 {"ok": True, "action": "sync", "jobIds": [99]})
            result = self.doctor(state)
            self.assertFalse(result["schedule"]["installed"], "没装任务时不能报成已装")
            self.assertEqual([99], result["lastRun"]["jobIds"])
            (state / "job.plist").write_text("plist")
            result = self.doctor(state)
            self.assertTrue(result["schedule"]["installed"])
            self.assertTrue(result["schedule"]["loaded"])

    def test_doctor_shows_only_the_tail_of_the_schedule_log(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            runner.write_private(state / "config.json",
                                 {"server": "https://example.test", "uploadToken": "t"})
            (state / "logs").mkdir()
            lines = ["old-" + str(i) for i in range(10)] + ["", "  ", "newest"]
            (state / "logs" / "schedule.log").write_text("\n".join(lines))
            tail = self.doctor(state)["logTail"]
            self.assertEqual("newest", tail[-1])
            self.assertLessEqual(len(tail), 3, "只看尾巴，不要把整份日志塞进摘要")
            self.assertNotIn("old-0", tail)

    def test_doctor_truncates_long_log_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            runner.write_private(state / "config.json",
                                 {"server": "https://example.test", "uploadToken": "t"})
            (state / "logs").mkdir()
            (state / "logs" / "schedule.log").write_text("x" * 5000)
            self.assertEqual(200, len(self.doctor(state)["logTail"][0]))


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
