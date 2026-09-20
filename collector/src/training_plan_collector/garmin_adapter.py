"""Thin adapter around python-garminconnect typed and raw responses."""

from typing import Any

from garminconnect import Garmin
from garminconnect.typed import DailyStats, SleepData

from training_plan_collector.read_only import ReadOnlyGarminClient


class GarminReadAdapter:
    """Expose only the Garmin read methods approved by the platform."""

    def __init__(self, client: Garmin | ReadOnlyGarminClient) -> None:
        self._client = (
            client if isinstance(client, ReadOnlyGarminClient) else ReadOnlyGarminClient(client)
        )

    @property
    def client(self) -> ReadOnlyGarminClient:
        """返回只读代理，便于上层继续读取其他已批准指标。"""

        return self._client

    def get_daily_stats(self, date: str) -> DailyStats:
        """Return validated daily statistics using the library's Pydantic model."""

        return self._client.typed.get_stats(date)

    def get_sleep_data(self, date: str) -> tuple[SleepData, dict[str, Any]]:
        """Return typed sleep summary together with raw minute-level arrays."""

        raw = self._client.get_sleep_data(date)
        return SleepData.model_validate(raw), raw
