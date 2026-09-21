import type {
  DailyHealthTrend,
  HrvTrend,
  SleepTrend,
  TrendQuery,
} from "../types/api";
import { request } from "../utils/request";

export function listDailyHealth(
  query: TrendQuery,
): Promise<DailyHealthTrend[]> {
  return request({ method: "GET", url: "/api/health/daily", params: query });
}

export function listHrv(query: TrendQuery): Promise<HrvTrend[]> {
  return request({ method: "GET", url: "/api/health/hrv", params: query });
}

export function listSleep(query: TrendQuery): Promise<SleepTrend[]> {
  return request({ method: "GET", url: "/api/sleep", params: query });
}
