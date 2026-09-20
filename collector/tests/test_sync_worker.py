"""同步工人的数据归一化与任务编排测试。"""

from datetime import UTC, datetime

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


def test_sleep_row_maps_typed_fields():
    raw = {
        "dailySleepDTO": {
            "calendarDate": "2026-09-20",
            "sleepStartTimestampGMT": 1758330000000,
            "sleepEndTimestampGMT": 1758355200000,
            "sleepTimeSeconds": 26460,
            "deepSleepSeconds": 6720,
            "lightSleepSeconds": 15000,
            "remSleepSeconds": 3000,
            "awakeSleepSeconds": 600,
            "avgSleepHRV": 52.3,
            "avgSpO2": 96.0,
            "averageRespirationValue": 14.2,
            "sleepScores": {"overall": {"value": 80, "qualifierKey": "GOOD"}},
        }
    }
    row = build_worker()._sleep_row(raw, "2026-09-20")

    assert row is not None
    assert row["calendarDate"] == "2026-09-20"
    assert row["sleepTimeSeconds"] == 26460
    assert row["sleepScore"] == 80
    assert row["avgSleepHrv"] == 52.3
    assert row["sleepStartGmt"].startswith("2025-") or row["sleepStartGmt"].startswith("2026-")


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
