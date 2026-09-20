"""Thin adapter around python-garminconnect typed and raw responses."""

from typing import Any

from garminconnect import Garmin
from garminconnect.typed import DailyStats, SleepData


class GarminReadAdapter:
    """Expose only the Garmin read methods approved by the platform."""

    def __init__(self, client: Garmin) -> None:
        self._client = client

    def get_daily_stats(self, date: str) -> DailyStats:
        """Return validated daily statistics using the library's Pydantic model."""

        return self._client.typed.get_stats(date)

    def get_sleep_data(self, date: str) -> tuple[SleepData, dict[str, Any]]:
        """Return typed sleep summary together with raw minute-level arrays."""

        raw = self._client.get_sleep_data(date)
        return SleepData.model_validate(raw), raw
