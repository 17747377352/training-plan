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
    ) -> dict[str, list[dict[str, Any]]]:
        """按日期逐日拉取，逐类数据独立容错，缺一天不影响其他天。"""

        days = self._date_range(start_date, end_date)
        daily: list[dict[str, Any]] = []
        sleep: list[dict[str, Any]] = []
        hrv: list[dict[str, Any]] = []
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

            raw_hrv = self._safe(lambda d=day: adapter.client.get_hrv_data(d))
            if raw_hrv:
                row = self._hrv_row(raw_hrv, day)
                if row is not None:
                    hrv.append(row)

        start, end = days[0], days[-1]
        for activity in self._safe(lambda: adapter.client.get_activities_by_date(
                start, end, ACTIVITY_TYPE_CYCLING, "asc")) or []:
            row = self._activity_row(activity)
            if row is not None:
                activities[row["garminActivityId"]] = row

        return {
            "dailyHealth": daily,
            "sleep": sleep,
            "hrv": hrv,
            "activities": list(activities.values()),
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
        dto = raw.get("dailySleepDTO") or {}
        start = dto.get("sleepStartTimestampGMT")
        if start is None:
            return None
        scores = dto.get("sleepScores") or {}
        overall = (scores.get("overall") or {}).get("value")
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
            "avgSleepHrv": dto.get("avgSleepHRV"),
            "avgSpo2": dto.get("avgSpO2"),
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
