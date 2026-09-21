"""同步任务消费者：从 Redis 队列取任务，拉取 Garmin 数据并回传平台。

架构约定：
  * 平台负责创建任务、解密令牌与落库；采集器只负责取数与格式转换。
  * 令牌按任务 ID 通过内部接口换取，不随任务载荷进入队列，避免长期凭据被持久化。
  * 所有 Garmin 读取都经过只读白名单代理。
"""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, date, datetime, timedelta
from typing import Any

import httpx
import structlog

from training_plan_collector.garmin_adapter import GarminReadAdapter
from training_plan_collector.garmin_auth import GarminAuthService
from training_plan_collector.read_only import ReadOnlyViolation
from training_plan_collector.settings import CollectorSettings

logger = structlog.get_logger()

QUEUE_POP_TIMEOUT_SECONDS = 5
HTTP_TIMEOUT_SECONDS = 60.0
ERROR_GARMIN_AUTH = "GARMIN_AUTH_REQUIRED"
ERROR_GARMIN_SYNC = "GARMIN_SYNC_ERROR"
ERROR_RATE_LIMITED = "GARMIN_RATE_LIMITED"
ERROR_UNEXPECTED = "SYSTEM_ERROR"

# 本期只同步骑行活动；徒步等其他类型不进入骑行统计。
ACTIVITY_TYPE_CYCLING = "cycling"

SUCCESS_CODE = 200


class CollectorApiError(Exception):
    """平台内部接口返回了非成功业务码。

    平台的响应统一是 ``{"code": ..., "message": ..., "data": ...}``，业务失败
    仍走 HTTP 200。只看 HTTP 状态码会把「任务已作废」当成执行成功，
    于是采集器继续拉数、继续回传，日志里还会打出 sync_job_succeeded。
    """

    def __init__(self, path: str, code: Any, message: str | None):
        super().__init__(f"{path} 返回业务码 {code}: {message}")
        self.path = path
        self.code = code


def _datetime_to_iso(value: Any) -> str | None:
    """把 Garmin 的 "yyyy-MM-dd HH:mm:ss" 转成 ISO 的 T 分隔格式。

    平台侧统一按 ISO 解析；Garmin 用空格分隔，直接透传会导致时间列全为空。
    """

    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    return text.replace(" ", "T", 1)


def _gmt_to_iso(millis: Any) -> str | None:
    """Garmin 的毫秒时间戳转 GMT ISO 字符串。"""

    if not isinstance(millis, (int, float)):
        return None
    return datetime.fromtimestamp(millis / 1000, tz=UTC).replace(tzinfo=None).isoformat()


class SyncWorker:
    """Redis 同步任务消费者。"""

    def __init__(
        self,
        settings: CollectorSettings,
        auth_service: GarminAuthService,
        redis_client: Any,
    ) -> None:
        self._settings = settings
        self._auth = auth_service
        self._redis = redis_client
        self._stopped = asyncio.Event()
        self._base_url = settings.server_base_url.rstrip("/")
        self._token = (
            settings.server_token.get_secret_value() if settings.server_token else ""
        )

    def stop(self) -> None:
        """请求停止消费循环。"""

        self._stopped.set()

    async def run(self) -> None:
        """消费循环：阻塞取任务，逐个执行，失败不影响后续任务。"""

        logger.info("sync_worker_started", queue=self._settings.task_queue)
        while not self._stopped.is_set():
            try:
                item = await self._redis.brpop(
                    self._settings.task_queue, timeout=QUEUE_POP_TIMEOUT_SECONDS
                )
            except Exception as exception:  # noqa: BLE001
                logger.warning(
                    "sync_worker_redis_error", error_type=type(exception).__name__
                )
                await asyncio.sleep(QUEUE_POP_TIMEOUT_SECONDS)
                continue
            if item is None:
                continue
            _, raw = item
            try:
                await self._handle(json.loads(raw))
            except Exception as exception:  # noqa: BLE001
                logger.warning(
                    "sync_worker_task_crashed", error_type=type(exception).__name__
                )

    async def _handle(self, payload: dict[str, Any]) -> None:
        job_id = payload.get("jobId")
        if job_id is None:
            logger.warning("sync_worker_missing_job_id")
            return
        logger.info("sync_job_received", job_id=job_id)

        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=HTTP_TIMEOUT_SECONDS,
            headers={"X-Collector-Token": self._token},
        ) as client:
            try:
                session = await self._post(client, f"/internal/collector/jobs/{job_id}/session")
                token_json = session.get("tokenJson")
                region = session.get("region") or payload.get("region") or "GLOBAL"
                if not token_json:
                    await self._fail(client, job_id, ERROR_GARMIN_AUTH)
                    return

                await self._post(client, f"/internal/collector/jobs/{job_id}/running")

                outcome = self._auth.restore_session(token_json, region)
                if outcome.status != "CONNECTED" or outcome.client is None:
                    await self._fail(client, job_id, self._error_code(outcome.status))
                    return

                adapter = GarminReadAdapter(outcome.client)
                body = self._collect(
                    adapter,
                    payload.get("startDate"),
                    payload.get("endDate"),
                    region,
                )
                await self._post(client, f"/internal/collector/jobs/{job_id}/ingest", body)
                await self._post(client, f"/internal/collector/jobs/{job_id}/complete")
                logger.info(
                    "sync_job_succeeded",
                    job_id=job_id,
                    daily=len(body["dailyHealth"]),
                    sleep=len(body["sleep"]),
                    hrv=len(body["hrv"]),
                    activities=len(body["activities"]),
                )
            except ReadOnlyViolation as violation:
                logger.warning("sync_job_read_only_violation", job_id=job_id, detail=str(violation))
                await self._fail(client, job_id, ERROR_UNEXPECTED)
            except CollectorApiError as error:
                # 平台明确拒绝（例如任务已被判超时作废）：不要再拉数，也不要谎报成功
                logger.warning(
                    "sync_job_rejected", job_id=job_id, code=error.code, path=error.path
                )
                await self._fail(client, job_id, ERROR_UNEXPECTED)
            except httpx.HTTPError as error:
                # 不在这里上报失败的话，任务会一直停在 RUNNING，
                # 直到平台的僵死清理器在 60 分钟后才把它收敛成超时，
                # 看板上就成了一条「超时」而不是真实原因的记录。
                logger.warning(
                    "sync_job_http_error", job_id=job_id, error_type=type(error).__name__
                )
                await self._fail(client, job_id, ERROR_UNEXPECTED)
            except Exception as exception:  # noqa: BLE001
                logger.warning(
                    "sync_job_failed", job_id=job_id, error_type=type(exception).__name__
                )
                await self._fail(client, job_id, ERROR_UNEXPECTED)

    @staticmethod
    def _error_code(status: str) -> str:
        if status == "RATE_LIMITED":
            return ERROR_RATE_LIMITED
        if status in ("TOKEN_INVALID", "UNREACHABLE"):
            return ERROR_GARMIN_AUTH
        return ERROR_GARMIN_SYNC

    def _collect(
        self,
        adapter: GarminReadAdapter,
        start_date: str | None,
        end_date: str | None,
        region: str,
    ) -> dict[str, Any]:
        """按日期区间拉取，逐类数据独立容错，缺一天不影响其他天。

        取值方式分两类：
          * 睡眠、每日统计、训练状态只有逐日接口，必须循环（实测
            ``get_sleep_daily`` 返回的是 ``{calendarDate, values}`` 指标序列，
            没有 ``dailySleepDTO``，用它替换会静默丢掉分期/评分/血氧/睡眠 HRV）。
          * HRV、骑行列表、FTP 有区间接口，一次请求取整段。
        """

        days = self._date_range(start_date, end_date)
        start, end = days[0], days[-1]
        daily: list[dict[str, Any]] = []
        sleep: list[dict[str, Any]] = []
        training: list[dict[str, Any]] = []
        activities: dict[Any, dict[str, Any]] = {}

        for day in days:
            stats = self._safe(lambda d=day: adapter.get_daily_stats(d))
            if stats is not None:
                daily.append(self._daily_row(stats, day))

            raw_sleep = self._safe(lambda d=day: adapter.get_sleep_data(d))
            if raw_sleep is not None:
                row = self._sleep_row(raw_sleep[1], day)
                if row is not None:
                    sleep.append(row)

            # 逐日查询返回的是那一天的快照（实测急性负荷随日期变化），可以按日回补
            raw_status = self._safe(lambda d=day: adapter.client.get_training_status(d))
            if raw_status:
                row = self._training_status_row(raw_status, day)
                if row is not None:
                    training.append(row)

        hrv: list[dict[str, Any]] = []
        raw_hrv_range = self._safe(lambda: adapter.client.get_hrv_data_range(start, end))
        for summary in (raw_hrv_range or {}).get("hrvSummaries") or []:
            row = self._hrv_row({"hrvSummary": summary}, summary.get("calendarDate") or start)
            if row is not None:
                hrv.append(row)

        for activity in self._safe(lambda: adapter.client.get_activities_by_date(
                start, end, ACTIVITY_TYPE_CYCLING, "asc")) or []:
            row = self._activity_row(activity)
            if row is not None:
                activities[row["garminActivityId"]] = row

        hr_zones: dict[str, list[dict[str, Any]]] = {}
        for activity_id in activities:
            zones = self._safe(
                lambda aid=activity_id: adapter.client.get_activity_hr_in_timezones(aid)
            )
            rows = self._hr_zone_rows(zones)
            if rows:
                hr_zones[str(activity_id)] = rows

        return {
            "dailyHealth": daily,
            "sleep": sleep,
            "hrv": hrv,
            "activities": list(activities.values()),
            "trainingStatus": training,
            "ftpHistory": self._ftp_rows(adapter),
            "activityHrZones": hr_zones,
        }

    @staticmethod
    def _ftp_rows(adapter: GarminReadAdapter) -> list[dict[str, Any]]:
        """取骑行 FTP 历史；没有历史接口时退回当前值。"""

        history = SyncWorker._safe(
            lambda: adapter.client.get_functional_threshold_power_range(
                "2000-01-01", date.today().isoformat(), sport="CYCLING"
            )
        )
        rows: dict[str, dict[str, Any]] = {}
        for item in history or []:
            effective = (item.get("from") or "")[:10]
            value = item.get("value")
            if effective and value:
                rows[effective] = {"effectiveDate": effective, "ftpWatts": round(float(value))}

        if not rows:
            current = SyncWorker._safe(lambda: adapter.client.get_cycling_ftp()) or {}
            value = current.get("functionalThresholdPower")
            effective = (current.get("calendarDate") or "")[:10]
            if value and effective:
                rows[effective] = {"effectiveDate": effective, "ftpWatts": round(float(value))}
        return sorted(rows.values(), key=lambda r: r["effectiveDate"])

    @staticmethod
    def _hr_zone_rows(zones: Any) -> list[dict[str, Any]]:
        """把活动心率区间转换为平台字段。"""

        rows: list[dict[str, Any]] = []
        for zone in zones or []:
            number = zone.get("zoneNumber")
            if number is None:
                continue
            rows.append({
                "zoneNumber": number,
                "zoneLowBoundary": zone.get("zoneLowBoundary"),
                "secondsInZone": int(zone.get("secsInZone") or 0),
            })
        return sorted(rows, key=lambda r: r["zoneNumber"])

    @staticmethod
    def _training_status_row(raw: dict[str, Any], day: str) -> dict[str, Any] | None:
        """把训练状态响应转换为平台字段。

        响应按设备 ID 分组：训练状态取标了 primaryTrainingDevice 的那台，
        负荷平衡按同一台设备取，取不到再退回第一台。
        """

        by_device = (
            (raw.get("mostRecentTrainingStatus") or {}).get("latestTrainingStatusData") or {}
        )
        if not by_device:
            return None
        entries = list(by_device.values())
        entry = next((e for e in entries if e.get("primaryTrainingDevice")), None) or entries[0]
        device_id = str(entry.get("deviceId"))

        balance_map = (
            (raw.get("mostRecentTrainingLoadBalance") or {}).get("metricsTrainingLoadBalanceDTOMap")
            or {}
        )
        balance = balance_map.get(device_id) or next(iter(balance_map.values()), {})
        acute = entry.get("acuteTrainingLoadDTO") or {}
        vo2max = (raw.get("mostRecentVO2Max") or {}).get("generic") or {}

        return {
            "calendarDate": entry.get("calendarDate") or day,
            "trainingStatus": entry.get("trainingStatus"),
            "trainingStatusPhrase": entry.get("trainingStatusFeedbackPhrase"),
            "acwrPercent": acute.get("acwrPercent"),
            "acwrStatus": acute.get("acwrStatus"),
            "acwrRatio": acute.get("dailyAcuteChronicWorkloadRatio"),
            "acuteLoad": acute.get("dailyTrainingLoadAcute"),
            "chronicLoad": acute.get("dailyTrainingLoadChronic"),
            "chronicLoadMin": acute.get("minTrainingLoadChronic"),
            "chronicLoadMax": acute.get("maxTrainingLoadChronic"),
            "loadAerobicLow": balance.get("monthlyLoadAerobicLow"),
            "loadAerobicLowTargetMin": balance.get("monthlyLoadAerobicLowTargetMin"),
            "loadAerobicLowTargetMax": balance.get("monthlyLoadAerobicLowTargetMax"),
            "loadAerobicHigh": balance.get("monthlyLoadAerobicHigh"),
            "loadAerobicHighTargetMin": balance.get("monthlyLoadAerobicHighTargetMin"),
            "loadAerobicHighTargetMax": balance.get("monthlyLoadAerobicHighTargetMax"),
            "loadAnaerobic": balance.get("monthlyLoadAnaerobic"),
            "loadAnaerobicTargetMin": balance.get("monthlyLoadAnaerobicTargetMin"),
            "loadAnaerobicTargetMax": balance.get("monthlyLoadAnaerobicTargetMax"),
            "balanceFeedbackPhrase": balance.get("trainingBalanceFeedbackPhrase"),
            "vo2maxValue": vo2max.get("vo2MaxValue"),
            "fitnessAge": vo2max.get("fitnessAge"),
        }

    @staticmethod
    def _safe(fetch: Any) -> Any:
        """单点失败不影响整次同步：Garmin 对某些日期可能没有数据。"""

        try:
            return fetch()
        except Exception as exception:  # noqa: BLE001
            logger.info("sync_point_skipped", error_type=type(exception).__name__)
            return None

    @staticmethod
    def _date_range(start_date: str | None, end_date: str | None) -> list[str]:
        try:
            start = date.fromisoformat(start_date) if start_date else date.today()
            end = date.fromisoformat(end_date) if end_date else start
        except ValueError:
            start = end = date.today()
        if end < start:
            start, end = end, start
        days = []
        cursor = start
        while cursor <= end and len(days) < 400:
            days.append(cursor.isoformat())
            cursor += timedelta(days=1)
        return days

    @staticmethod
    def _daily_row(stats: Any, day: str) -> dict[str, Any]:
        return {
            "calendarDate": day,
            "steps": stats.total_steps,
            "distanceMeters": stats.total_distance_meters,
            "totalKilocalories": stats.total_kilocalories,
            "activeKilocalories": stats.active_kilocalories,
            "restingHeartRate": stats.resting_heart_rate,
            "minHeartRate": stats.min_heart_rate,
            "maxHeartRate": stats.max_heart_rate,
            "averageStressLevel": stats.average_stress_level,
            "bodyBatteryHighest": stats.body_battery_highest_value,
            "bodyBatteryLowest": stats.body_battery_lowest_value,
        }

    @staticmethod
    def _sleep_row(raw: dict[str, Any], day: str) -> dict[str, Any] | None:
        """把 Garmin 的睡眠响应转换为平台字段。

        字段名依据 2026-09-21 对真实响应的实地核对。**不要照抄
        ``garminconnect`` 的 typed 模型**：它的 ``SleepData`` 声明了
        ``avgSleepHRV`` / ``avgSpO2``，但那是照着 ``tests/test_typed.py`` 里
        手写的 fixture（``userProfilePK: 12345``）写的，真实响应里这两个键
        从来不存在。真实键名是 ``averageSpO2Value`` 与顶层的 ``avgOvernightHrv``，
        按库的模型取会让血氧与睡眠 HRV 静默全空。
        """

        dto = raw.get("dailySleepDTO") or {}
        start = dto.get("sleepStartTimestampGMT")
        if start is None:
            return None
        scores = dto.get("sleepScores") or {}
        overall = (scores.get("overall") or {}).get("value")
        spo2_summary = raw.get("wellnessSpO2SleepSummaryDTO") or {}
        return {
            "calendarDate": dto.get("calendarDate") or day,
            "sleepStartGmt": _gmt_to_iso(start),
            "sleepEndGmt": _gmt_to_iso(dto.get("sleepEndTimestampGMT")),
            "sleepTimeSeconds": dto.get("sleepTimeSeconds"),
            "deepSleepSeconds": dto.get("deepSleepSeconds"),
            "lightSleepSeconds": dto.get("lightSleepSeconds"),
            "remSleepSeconds": dto.get("remSleepSeconds"),
            "awakeSleepSeconds": dto.get("awakeSleepSeconds"),
            "sleepScore": overall,
            # 夜间 HRV 在响应的顶层，不在 dailySleepDTO 里
            "avgSleepHrv": raw.get("avgOvernightHrv"),
            # 血氧优先取 dailySleepDTO，缺失时退回 wellnessSpO2SleepSummaryDTO
            "avgSpo2": dto.get("averageSpO2Value") or spo2_summary.get("averageSPO2"),
            "avgRespiration": dto.get("averageRespirationValue"),
        }

    @staticmethod
    def _activity_row(activity: dict[str, Any]) -> dict[str, Any] | None:
        """把 Garmin 活动列表中的一条转换为平台字段。

        字段名依据 2026-09-21 对真实响应的实地核对；踏频是
        averageBikingCadenceInRevPerMinute，功率区间与左右平衡直接在列表里。
        GPS 坐标、位置名与账号姓名一律不取。
        """

        activity_id = activity.get("activityId")
        if activity_id is None:
            return None
        type_info = activity.get("activityType") or {}
        return {
            "garminActivityId": activity_id,
            "activityTypeKey": type_info.get("typeKey"),
            "activityTypeId": type_info.get("typeId"),
            "parentTypeId": type_info.get("parentTypeId"),
            "activityName": activity.get("activityName"),
            "startTimeGmt": _datetime_to_iso(activity.get("startTimeGMT")),
            "startTimeLocal": _datetime_to_iso(activity.get("startTimeLocal")),
            "durationSeconds": activity.get("duration"),
            "movingDurationSeconds": activity.get("movingDuration"),
            "elapsedDurationSeconds": activity.get("elapsedDuration"),
            "distanceMeters": activity.get("distance"),
            "elevationGain": activity.get("elevationGain"),
            "elevationLoss": activity.get("elevationLoss"),
            "avgElevation": activity.get("avgElevation"),
            "maxElevation": activity.get("maxElevation"),
            "minElevation": activity.get("minElevation"),
            "averageSpeed": activity.get("averageSpeed"),
            "maxSpeed": activity.get("maxSpeed"),
            "averageHr": activity.get("averageHR"),
            "maxHr": activity.get("maxHR"),
            "calories": activity.get("calories"),
            "bmrCalories": activity.get("bmrCalories"),
            "avgPower": activity.get("avgPower"),
            "maxPower": activity.get("maxPower"),
            "normPower": activity.get("normPower"),
            "max20minPower": activity.get("max20MinPower"),
            "intensityFactor": activity.get("intensityFactor"),
            "trainingStressScore": activity.get("trainingStressScore"),
            "avgCadence": activity.get("averageBikingCadenceInRevPerMinute"),
            "maxCadence": activity.get("maxBikingCadenceInRevPerMinute"),
            "avgLeftBalance": activity.get("avgLeftBalance"),
            "aerobicTrainingEffect": activity.get("aerobicTrainingEffect"),
            "anaerobicTrainingEffect": activity.get("anaerobicTrainingEffect"),
            "trainingEffectLabel": activity.get("trainingEffectLabel"),
            "activityTrainingLoad": activity.get("activityTrainingLoad"),
            "powerZone1Seconds": activity.get("powerTimeInZone_1"),
            "powerZone2Seconds": activity.get("powerTimeInZone_2"),
            "powerZone3Seconds": activity.get("powerTimeInZone_3"),
            "powerZone4Seconds": activity.get("powerTimeInZone_4"),
            "powerZone5Seconds": activity.get("powerTimeInZone_5"),
            "powerZone6Seconds": activity.get("powerTimeInZone_6"),
            "powerZone7Seconds": activity.get("powerTimeInZone_7"),
            "lapCount": activity.get("lapCount"),
            "strokes": activity.get("strokes"),
            "avgRespirationRate": activity.get("avgRespirationRate"),
            "minTemperature": activity.get("minTemperature"),
            "maxTemperature": activity.get("maxTemperature"),
            "vo2maxValue": activity.get("vO2MaxValue"),
            "deviceId": activity.get("deviceId"),
        }

    @staticmethod
    def _hrv_row(raw: dict[str, Any], day: str) -> dict[str, Any] | None:
        summary = raw.get("hrvSummary") or {}
        if not summary:
            return None
        baseline = summary.get("baseline") or {}
        return {
            "calendarDate": summary.get("calendarDate") or day,
            "lastNightAvg": summary.get("lastNightAvg"),
            "weeklyAvg": summary.get("weeklyAvg"),
            "status": summary.get("status"),
            "baselineLowUpper": baseline.get("lowUpper"),
            "baselineBalancedLow": baseline.get("balancedLow"),
            "baselineBalancedUpper": baseline.get("balancedUpper"),
        }

    async def _post(
        self, client: httpx.AsyncClient, path: str, body: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        response = await client.post(path, json=body or {})
        response.raise_for_status()
        payload = response.json()
        if payload.get("code") != SUCCESS_CODE:
            raise CollectorApiError(path, payload.get("code"), payload.get("message"))
        return payload.get("data") or {}

    async def _fail(self, client: httpx.AsyncClient, job_id: Any, error_code: str) -> None:
        try:
            await self._post(
                client, f"/internal/collector/jobs/{job_id}/fail", {"errorCode": error_code}
            )
        except (httpx.HTTPError, CollectorApiError) as error:
            logger.warning(
                "sync_job_fail_report_failed", job_id=job_id, error_type=type(error).__name__
            )
