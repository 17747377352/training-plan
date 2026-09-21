"""同步工人的数据归一化与任务编排测试。"""

from datetime import UTC, date, datetime

import httpx

from training_plan_collector.garmin_auth import SessionOutcome
from training_plan_collector.settings import CollectorSettings
from training_plan_collector.sync_worker import SyncWorker, _gmt_to_iso


def build_worker() -> SyncWorker:
    settings = CollectorSettings(_env_file=None, server_token="t")
    return SyncWorker(settings, auth_service=None, redis_client=None)  # type: ignore[arg-type]


def test_date_range_single_day():
    assert build_worker()._date_range("2026-09-20", "2026-09-20") == ["2026-09-20"]


def test_date_range_multiple_days_inclusive():
    assert build_worker()._date_range("2026-09-18", "2026-09-20") == [
        "2026-09-18",
        "2026-09-19",
        "2026-09-20",
    ]


def test_date_range_swaps_reversed_input():
    assert build_worker()._date_range("2026-09-20", "2026-09-18") == [
        "2026-09-18",
        "2026-09-19",
        "2026-09-20",
    ]


def test_date_range_falls_back_on_invalid_input():
    days = build_worker()._date_range("not-a-date", None)
    assert len(days) == 1


def test_gmt_millis_to_iso():
    millis = int(datetime(2026, 9, 20, 3, 30, tzinfo=UTC).timestamp() * 1000)

    assert _gmt_to_iso(millis) == "2026-09-20T03:30:00"
    assert _gmt_to_iso(None) is None
    assert _gmt_to_iso("x") is None


def test_sleep_row_maps_real_response_keys():
    """字段名依据 2026-09-21 实拉的真实睡眠响应，不是库里的 fixture。

    ``garminconnect`` 的 SleepData 声明了 avgSleepHRV / avgSpO2，但那是照着
    tests/test_typed.py 里手写的 fixture 写的，真实响应里没有这两个键。
    照库的模型取会让血氧与睡眠 HRV 静默全空——这正是线上发生过的事。
    """

    raw = {
        "dailySleepDTO": {
            "calendarDate": "2026-09-20",
            "sleepStartTimestampGMT": 1789836851000,
            "sleepEndTimestampGMT": 1789863731000,
            "sleepTimeSeconds": 26460,
            "deepSleepSeconds": 6720,
            "lightSleepSeconds": 16740,
            "remSleepSeconds": 3000,
            "awakeSleepSeconds": 420,
            "averageSpO2Value": 94.0,
            "lowestSpO2Value": 83,
            "highestSpO2Value": 100,
            "averageRespirationValue": 14.2,
            "sleepScores": {"overall": {"value": 80, "qualifierKey": "GOOD"}},
        },
        "wellnessSpO2SleepSummaryDTO": {"averageSPO2": 94.0, "lowestSPO2": 83},
        "avgOvernightHrv": 70.0,
        "hrvStatus": "UNBALANCED",
    }
    row = build_worker()._sleep_row(raw, "2026-09-20")

    assert row is not None
    assert row["calendarDate"] == "2026-09-20"
    assert row["sleepTimeSeconds"] == 26460
    assert row["sleepScore"] == 80
    # 夜间 HRV 在响应顶层，不在 dailySleepDTO 里
    assert row["avgSleepHrv"] == 70.0
    assert row["avgSpo2"] == 94.0
    assert row["avgRespiration"] == 14.2
    assert row["sleepStartGmt"].startswith("2026-")


def test_sleep_row_falls_back_to_spo2_summary_block():
    """dailySleepDTO 里没有血氧时，退回 wellnessSpO2SleepSummaryDTO。"""

    raw = {
        "dailySleepDTO": {"sleepStartTimestampGMT": 1789836851000},
        "wellnessSpO2SleepSummaryDTO": {"averageSPO2": 91.0},
    }

    row = build_worker()._sleep_row(raw, "2026-09-20")

    assert row is not None
    assert row["avgSpo2"] == 91.0
    # 顶层没有 HRV 时如实为空，不要瞎猜
    assert row["avgSleepHrv"] is None


def test_sleep_row_skipped_without_start_timestamp():
    assert build_worker()._sleep_row({"dailySleepDTO": {}}, "2026-09-20") is None


def test_hrv_row_maps_baseline():
    raw = {
        "hrvSummary": {
            "calendarDate": "2026-09-20",
            "lastNightAvg": 70.0,
            "weeklyAvg": 79.0,
            "status": "UNBALANCED",
            "baseline": {"lowUpper": 79.0, "balancedLow": 80.0, "balancedUpper": 107.0},
        }
    }
    row = build_worker()._hrv_row(raw, "2026-09-20")

    assert row is not None
    assert row["lastNightAvg"] == 70.0
    assert row["status"] == "UNBALANCED"
    assert row["baselineBalancedLow"] == 80.0
    assert row["baselineBalancedUpper"] == 107.0


def test_hrv_row_skipped_when_summary_missing():
    assert build_worker()._hrv_row({}, "2026-09-20") is None


def test_error_code_mapping():
    worker = build_worker()
    assert worker._error_code("RATE_LIMITED") == "GARMIN_RATE_LIMITED"
    assert worker._error_code("TOKEN_INVALID") == "GARMIN_AUTH_REQUIRED"
    assert worker._error_code("UNREACHABLE") == "GARMIN_AUTH_REQUIRED"
    assert worker._error_code("FAILED") == "GARMIN_SYNC_ERROR"


def test_safe_swallows_single_point_failure():
    def boom():
        raise RuntimeError("garmin 该日无数据")

    assert build_worker()._safe(boom) is None


def test_activity_row_uses_real_garmin_field_names():
    """字段名依据 2026-09-21 实拉核对，写错就会静默丢数据。"""

    activity = {
        "activityId": 12345678901,
        "activityName": "丰台区 公路骑行",
        "activityType": {"typeId": 10, "typeKey": "road_biking", "parentTypeId": 2},
        "startTimeGMT": "2026-09-19 00:53:51",
        "startTimeLocal": "2026-09-19 08:53:51",
        "duration": 15299.0,
        "movingDuration": 15280.0,
        "elapsedDuration": 23489.0,
        "distance": 103550.0,
        "elevationGain": 2322.0,
        "averageSpeed": 6.777,
        "averageHR": 156.0,
        "maxHR": 185.0,
        "calories": 2592.0,
        "avgPower": 148.0,
        "normPower": 186.0,
        "max20MinPower": 216.0,
        "intensityFactor": 0.87,
        "trainingStressScore": 321.6,
        "averageBikingCadenceInRevPerMinute": 77.0,
        "avgLeftBalance": 56.0,
        "powerTimeInZone_1": 408.0,
        "powerTimeInZone_4": 916.0,
        "powerTimeInZone_7": 11.0,
        "vO2MaxValue": 59.0,
        "deviceId": 3355668899,
        # 以下字段刻意不应被采集
        "locationName": "丰台区",
        "startLatitude": 39.85,
        "startLongitude": 116.28,
        "ownerFullName": "某姓名",
    }

    row = build_worker()._activity_row(activity)

    assert row is not None
    assert row["garminActivityId"] == 12345678901
    assert row["activityTypeKey"] == "road_biking"
    assert row["parentTypeId"] == 2
    assert row["normPower"] == 186.0
    assert row["trainingStressScore"] == 321.6
    assert row["max20minPower"] == 216.0
    # 踏频来自 averageBikingCadenceInRevPerMinute，不是 avgCadence
    assert row["avgCadence"] == 77.0
    assert row["avgLeftBalance"] == 56.0
    assert row["powerZone1Seconds"] == 408.0
    assert row["powerZone4Seconds"] == 916.0
    assert row["powerZone7Seconds"] == 11.0
    assert row["vo2maxValue"] == 59.0
    # 隐私字段不得出现在上报内容里
    joined = " ".join(row.keys())
    for forbidden in ("location", "Latitude", "Longitude", "owner"):
        assert forbidden.lower() not in joined.lower()


def test_activity_row_skipped_without_id():
    assert build_worker()._activity_row({"activityName": "无 ID"}) is None


def test_activity_row_tolerates_missing_type():
    row = build_worker()._activity_row({"activityId": 1})

    assert row is not None
    assert row["activityTypeKey"] is None
    assert row["distanceMeters"] is None


def test_activity_time_fields_converted_to_iso():
    """Garmin 用空格分隔时间，直接透传会让平台的时间列全为空。"""

    row = build_worker()._activity_row({
        "activityId": 1,
        "startTimeGMT": "2026-09-19 00:53:51",
        "startTimeLocal": "2026-09-19 08:53:51",
    })

    assert row is not None
    assert row["startTimeGmt"] == "2026-09-19T00:53:51"
    assert row["startTimeLocal"] == "2026-09-19T08:53:51"


def test_activity_time_fields_tolerate_missing_values():
    row = build_worker()._activity_row({"activityId": 1, "startTimeGMT": None})

    assert row is not None
    assert row["startTimeGmt"] is None


class _FakeResponse:
    """最小响应替身，只实现 SyncWorker 用到的方法。"""

    def __init__(
        self, data: dict | None = None, status_code: int = 200, code: int = 200
    ):
        self._data = data or {}
        self.status_code = status_code
        # 平台业务码：HTTP 200 也可能是业务失败，采集器必须看这个字段
        self._code = code

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise httpx.HTTPStatusError(
                f"status {self.status_code}",
                request=httpx.Request("POST", "http://platform.test"),
                response=httpx.Response(self.status_code),
            )

    def json(self) -> dict:
        return {"code": self._code, "message": "success", "data": self._data}


class _FakeClient:
    """按路径脚本化返回的 httpx.AsyncClient 替身，并记录全部请求。"""

    def __init__(self, handler):
        self._handler = handler
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self) -> "_FakeClient":
        return self

    async def __aexit__(self, *exc_info) -> bool:
        return False

    async def post(self, path: str, json: dict | None = None) -> _FakeResponse:
        self.calls.append((path, json or {}))
        return self._handler(path)


def _install_fake_client(monkeypatch, handler) -> _FakeClient:
    client = _FakeClient(handler)
    monkeypatch.setattr(
        "training_plan_collector.sync_worker.httpx.AsyncClient", lambda **kwargs: client
    )
    return client


def _handshake_handler(on_running=None):
    """会话握手成功，其余端点按需定制。"""

    def handler(path: str) -> _FakeResponse:
        if path.endswith("/session"):
            return _FakeResponse({"tokenJson": "{}", "region": "GLOBAL"})
        if path.endswith("/running") and on_running is not None:
            return on_running()
        return _FakeResponse({})

    return handler


async def test_http_error_reports_failure_instead_of_leaving_job_running(monkeypatch):
    """平台内部接口报错时也要上报失败。

    否则任务会一直停在 RUNNING，直到平台的僵死清理器在 60 分钟后才收敛成
    「超时」，看板上看到的是超时而不是真实原因。
    """

    def on_running():
        raise httpx.ConnectError("platform dropped the connection")

    client = _install_fake_client(monkeypatch, _handshake_handler(on_running))

    await build_worker()._handle({"jobId": 9, "region": "GLOBAL"})

    fail_payload = dict(client.calls).get("/internal/collector/jobs/9/fail")
    assert fail_payload is not None, "HTTP 异常必须上报失败，否则任务会一直停在 RUNNING"
    assert fail_payload["errorCode"] == "SYSTEM_ERROR"
    assert "/internal/collector/jobs/9/complete" not in dict(client.calls)


async def test_successful_job_reports_complete_and_never_fails(monkeypatch):
    """正常路径只上报 complete，不得同时上报 fail。"""

    client = _install_fake_client(monkeypatch, _handshake_handler())
    worker = build_worker()

    class _Auth:
        def restore_session(self, token_json, region):
            return SessionOutcome(status="CONNECTED", client=object())

    worker._auth = _Auth()
    worker._collect = lambda adapter, start, end, region: {
        "dailyHealth": [],
        "sleep": [],
        "hrv": [],
        "activities": [],
    }

    await worker._handle({"jobId": 11, "region": "GLOBAL"})

    paths = [path for path, _ in client.calls]
    assert "/internal/collector/jobs/11/complete" in paths
    assert "/internal/collector/jobs/11/fail" not in paths


async def test_business_error_code_aborts_job_instead_of_reporting_success(monkeypatch):
    """平台用 HTTP 200 + 业务码表示失败时，采集器不能当成执行成功。

    「任务已被判超时作废」就是这个形态：只 raise_for_status() 会一路走下去，
    继续拉数、继续回传，日志里还打出 sync_job_succeeded。
    """

    def handler(path: str) -> _FakeResponse:
        if path.endswith("/running"):
            return _FakeResponse(code=40400, status_code=200)
        if path.endswith("/session"):
            return _FakeResponse({"tokenJson": "{}", "region": "GLOBAL"})
        return _FakeResponse({})

    client = _install_fake_client(monkeypatch, handler)

    class _RecordingAuth:
        """记录是否真的去连了 Garmin；被拒绝的任务不该走到这一步。"""

        def __init__(self) -> None:
            self.called = False

        def restore_session(self, token_json, region):
            self.called = True
            raise AssertionError("平台已拒绝该任务，不应继续恢复 Garmin 会话")

    worker = build_worker()
    worker._auth = _RecordingAuth()

    await worker._handle({"jobId": 21, "region": "GLOBAL"})

    # 关键断言：拿到业务失败码后立刻停手，而不是继续去连 Garmin
    assert worker._auth.called is False
    paths = [path for path, _ in client.calls]
    assert "/internal/collector/jobs/21/fail" in paths
    assert "/internal/collector/jobs/21/ingest" not in paths
    assert "/internal/collector/jobs/21/complete" not in paths


# --- 训练状态 / FTP / 心率区间：字段名依据 2026-09-21 实拉响应 ---

DEVICE_ID = 3610031482


def _training_status_payload(primary=True, device_id=DEVICE_ID):
    """与真实 get_training_status 响应同构（按设备 ID 分组）。"""

    return {
        "mostRecentVO2Max": {
            "generic": {"calendarDate": "2026-09-19", "vo2MaxValue": 59.0, "fitnessAge": 20}
        },
        "mostRecentTrainingStatus": {
            "latestTrainingStatusData": {
                str(device_id): {
                    "deviceId": device_id,
                    "calendarDate": "2026-09-21",
                    "primaryTrainingDevice": primary,
                    "trainingStatus": 7,
                    "trainingStatusFeedbackPhrase": "PRODUCTIVE_6",
                    "acuteTrainingLoadDTO": {
                        "acwrPercent": 52,
                        "acwrStatus": "OPTIMAL",
                        "dailyAcuteChronicWorkloadRatio": 1.2,
                        "dailyTrainingLoadAcute": 819,
                        "dailyTrainingLoadChronic": 658,
                        "minTrainingLoadChronic": 526.4,
                        "maxTrainingLoadChronic": 987.0,
                    },
                }
            }
        },
        "mostRecentTrainingLoadBalance": {
            "metricsTrainingLoadBalanceDTOMap": {
                str(device_id): {
                    "monthlyLoadAerobicLow": 157.42,
                    "monthlyLoadAerobicLowTargetMin": 433,
                    "monthlyLoadAerobicLowTargetMax": 952,
                    "monthlyLoadAerobicHigh": 2161.64,
                    "monthlyLoadAerobicHighTargetMin": 519,
                    "monthlyLoadAerobicHighTargetMax": 1039,
                    "monthlyLoadAnaerobic": 231.98,
                    "monthlyLoadAnaerobicTargetMin": 173,
                    "monthlyLoadAnaerobicTargetMax": 519,
                    "trainingBalanceFeedbackPhrase": "AEROBIC_LOW_SHORTAGE",
                }
            }
        },
    }


def test_training_status_row_maps_load_and_balance():
    row = build_worker()._training_status_row(_training_status_payload(), "2026-09-21")

    assert row is not None
    assert row["calendarDate"] == "2026-09-21"
    assert row["acuteLoad"] == 819
    assert row["chronicLoad"] == 658
    assert row["chronicLoadMin"] == 526.4
    assert row["acwrPercent"] == 52
    assert row["acwrStatus"] == "OPTIMAL"
    assert row["acwrRatio"] == 1.2
    assert row["trainingStatusPhrase"] == "PRODUCTIVE_6"
    # 负荷平衡来自另一个分组，必须按同一台设备取
    assert row["loadAerobicLow"] == 157.42
    assert row["loadAerobicLowTargetMin"] == 433
    assert row["loadAerobicHigh"] == 2161.64
    assert row["balanceFeedbackPhrase"] == "AEROBIC_LOW_SHORTAGE"
    assert row["vo2maxValue"] == 59.0
    assert row["fitnessAge"] == 20


def test_training_status_row_prefers_primary_device():
    payload = _training_status_payload(primary=False, device_id=111)
    payload["mostRecentTrainingStatus"]["latestTrainingStatusData"][str(DEVICE_ID)] = {
        "deviceId": DEVICE_ID,
        "calendarDate": "2026-09-21",
        "primaryTrainingDevice": True,
        "trainingStatus": 7,
        "acuteTrainingLoadDTO": {"dailyTrainingLoadAcute": 819, "dailyTrainingLoadChronic": 658},
    }
    payload["mostRecentTrainingLoadBalance"]["metricsTrainingLoadBalanceDTOMap"][str(DEVICE_ID)] = {
        "monthlyLoadAerobicLow": 157.42,
        "trainingBalanceFeedbackPhrase": "AEROBIC_LOW_SHORTAGE",
    }

    row = build_worker()._training_status_row(payload, "2026-09-21")

    # 不能拿到哪台算哪台：非主设备那条的急性负荷是空的
    assert row["acuteLoad"] == 819
    assert row["loadAerobicLow"] == 157.42


def test_training_status_row_returns_none_without_data():
    assert build_worker()._training_status_row({}, "2026-09-21") is None
    assert build_worker()._training_status_row({"mostRecentTrainingStatus": {}}, "2026-09-21") is None


def test_hr_zone_rows_sorted_and_typed():
    zones = [
        {"zoneNumber": 5, "zoneLowBoundary": 179, "secsInZone": 828.0},
        {"zoneNumber": 1, "zoneLowBoundary": 100, "secsInZone": 1476.0},
    ]

    rows = build_worker()._hr_zone_rows(zones)

    assert [r["zoneNumber"] for r in rows] == [1, 5]
    assert rows[0]["secondsInZone"] == 1476
    assert rows[1]["zoneLowBoundary"] == 179
    # 缺失的秒数按 0 处理，不要写成 null
    assert build_worker()._hr_zone_rows([{"zoneNumber": 2}])[0]["secondsInZone"] == 0


def test_ftp_rows_from_history_series():
    class _Client:
        def get_functional_threshold_power_range(self, start, end, sport=None):
            assert sport == "CYCLING", "取骑行 FTP 必须显式指定 sport"
            return [
                {"from": "2026-07-22", "until": "2026-07-22", "value": 219.0},
                {"from": "2026-08-22", "until": "2026-08-22", "value": 216.0},
                {"from": "2026-06-26", "until": "2026-06-26", "value": 211.0},
            ]

        def get_cycling_ftp(self):
            raise AssertionError("有历史时不该再查当前值")

    class _Adapter:
        client = _Client()

    rows = build_worker()._ftp_rows(_Adapter())

    assert [r["effectiveDate"] for r in rows] == ["2026-06-26", "2026-07-22", "2026-08-22"]
    assert rows[-1]["ftpWatts"] == 216


def test_ftp_rows_falls_back_to_current_value():
    class _Client:
        def get_functional_threshold_power_range(self, start, end, sport=None):
            return []

        def get_cycling_ftp(self):
            return {"calendarDate": "2026-08-29T17:25:41.0", "functionalThresholdPower": 213}

    class _Adapter:
        client = _Client()

    rows = build_worker()._ftp_rows(_Adapter())

    assert rows == [{"effectiveDate": "2026-08-29", "ftpWatts": 213}]


def test_ftp_rows_skip_entries_without_value():
    class _Client:
        def get_functional_threshold_power_range(self, start, end, sport=None):
            return [{"from": "2026-07-01", "value": None}, {"from": "", "value": 200}]

        def get_cycling_ftp(self):
            return {}

    class _Adapter:
        client = _Client()

    assert build_worker()._ftp_rows(_Adapter()) == []


def test_collect_uses_hrv_range_endpoint_and_returns_all_payload_keys():
    """HRV 走区间接口（一次请求），且上报载荷包含新增的三类数据。"""

    calls: list[tuple] = []

    class _Client:
        def get_hrv_data_range(self, start, end):
            calls.append(("hrv_range", start, end))
            return {
                "hrvSummaries": [
                    {"calendarDate": "2026-09-19", "lastNightAvg": 79, "weeklyAvg": 77,
                     "status": "UNBALANCED",
                     "baseline": {"lowUpper": 75, "balancedLow": 81, "balancedUpper": 106}},
                    {"calendarDate": "2026-09-20", "lastNightAvg": 70, "weeklyAvg": 79,
                     "status": "UNBALANCED",
                     "baseline": {"lowUpper": 75, "balancedLow": 80, "balancedUpper": 107}},
                ]
            }

        def get_hrv_data(self, day):
            raise AssertionError("HRV 应走区间接口，不该再逐日拉取")

        def get_training_status(self, day):
            calls.append(("training_status", day))
            return {}

        def get_activities_by_date(self, start, end, kind=None, order=None):
            calls.append(("activities", start, end, kind))
            return []

        def get_activity_hr_in_timezones(self, activity_id):
            raise AssertionError("没有活动时不该请求心率区间")

        def get_functional_threshold_power_range(self, start, end, sport=None):
            return [{"from": "2026-08-29", "value": 213}]

        def get_cycling_ftp(self):
            return {}

    class _Adapter:
        client = _Client()

        def get_daily_stats(self, day):
            return None

        def get_sleep_data(self, day):
            return None

    result = build_worker()._collect(_Adapter(), "2026-09-19", "2026-09-21", "GLOBAL")

    # 区间接口一次拿整段，而不是每天一次
    assert [c for c in calls if c[0] == "hrv_range"] == [("hrv_range", "2026-09-19", "2026-09-21")]
    assert len([c for c in calls if c[0] == "training_status"]) == 3
    assert [r["calendarDate"] for r in result["hrv"]] == ["2026-09-19", "2026-09-20"]
    assert result["ftpHistory"] == [{"effectiveDate": "2026-08-29", "ftpWatts": 213}]
    # 载荷键名与平台 SyncIngestRequest 的字段一一对应
    assert set(result) == {
        "dailyHealth", "sleep", "naps", "hrv", "activities",
        "trainingStatus", "ftpHistory", "activityHrZones",
    }


def test_ftp_rows_requests_bounded_window():
    """FTP 历史区间上限约一年，请求过宽会被 400 拒绝并静默退化成单条。"""

    seen: dict[str, str] = {}

    class _Client:
        def get_functional_threshold_power_range(self, start, end, sport=None):
            seen["start"] = start
            seen["end"] = end
            return [{"from": "2026-08-22", "value": 216.0}]

        def get_cycling_ftp(self):
            raise AssertionError("有历史时不该退回当前值")

    class _Adapter:
        client = _Client()

    rows = build_worker()._ftp_rows(_Adapter())

    span = (date.fromisoformat(seen["end"]) - date.fromisoformat(seen["start"])).days
    assert span <= 366, f"FTP 历史窗口 {span} 天会被 Garmin 拒绝"
    assert rows == [{"effectiveDate": "2026-08-22", "ftpWatts": 216}]


NAPS = [
    {"calendarDate": "2026-09-21", "napTimeSec": 2580,
     "napStartTimestampGMT": "2026-09-21T05:27:17", "napEndTimestampGMT": "2026-09-21T06:10:17",
     "napFeedback": "IDEAL_TIMING_LONG_DURATION_LOW_NEED", "napSource": 0},
]


def test_nap_rows_read_daily_nap_list():
    """午睡在 dailySleepDTO.dailyNapDTOS 里，是数组——一天可能睡多次。"""

    rows = build_worker()._nap_rows({"dailySleepDTO": {"calendarDate": "2026-09-21",
                                                      "dailyNapDTOS": NAPS}})

    assert rows == [{
        "calendarDate": "2026-09-21",
        "napStartGmt": "2026-09-21T05:27:17",
        "napEndGmt": "2026-09-21T06:10:17",
        "napSeconds": 2580,
        "napFeedback": "IDEAL_TIMING_LONG_DURATION_LOW_NEED",
        "napSource": 0,
    }]


def test_nap_rows_keep_every_nap_of_the_day():
    """单列合计会丢掉第二次午睡，所以必须逐条产出。"""

    twice = NAPS + [{"calendarDate": "2026-09-21", "napTimeSec": 1200,
                     "napStartTimestampGMT": "2026-09-21T09:00:00"}]

    rows = build_worker()._nap_rows({"dailySleepDTO": {"dailyNapDTOS": twice}})

    assert [r["napSeconds"] for r in rows] == [2580, 1200]


def test_nap_rows_skip_entries_without_start_timestamp():
    """没有起点就无法幂等去重，宁可少一条也不能在重复同步时累积多行。"""

    no_start = [{"napTimeSec": 600}, {"napStartTimestampGMT": "", "napTimeSec": 600}]

    assert build_worker()._nap_rows({"dailySleepDTO": {"dailyNapDTOS": no_start}}) == []


def test_nap_rows_empty_when_no_nap():
    assert build_worker()._nap_rows({"dailySleepDTO": {"napTimeSeconds": 0}}) == []
    assert build_worker()._nap_rows({}) == []


def test_collect_payload_includes_naps(monkeypatch):
    """载荷必须带上 naps，且午睡不混进 sleep 列表。"""

    class _Client:
        def get_hrv_data_range(self, start, end):
            return {"hrvSummaries": []}

        def get_training_status(self, day):
            return {}

        def get_activities_by_date(self, start, end, kind=None, order=None):
            return []

        def get_functional_threshold_power_range(self, start, end, sport=None):
            return []

        def get_cycling_ftp(self):
            return {}

    class _Adapter:
        client = _Client()

        def get_daily_stats(self, day):
            return None

        def get_sleep_data(self, day):
            return (None, {"dailySleepDTO": {"calendarDate": day,
                                            "sleepStartTimestampGMT": 1758330000000,
                                            "sleepTimeSeconds": 19072,
                                            "dailyNapDTOS": NAPS}})

    result = build_worker()._collect(_Adapter(), "2026-09-21", "2026-09-21", "GLOBAL")

    assert len(result["sleep"]) == 1
    assert result["sleep"][0]["sleepTimeSeconds"] == 19072
    assert [n["napSeconds"] for n in result["naps"]] == [2580]
    assert "naps" in result
