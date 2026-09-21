import type {
  ActivityDetail,
  ActivityQuery,
  ActivitySummary,
  PageResult,
} from "../types/api";
import { request } from "../utils/request";

export function listActivities(
  query: ActivityQuery,
): Promise<PageResult<ActivitySummary>> {
  return request({
    method: "GET",
    url: "/api/activities",
    params: query,
  });
}

export function listActivityTypes(): Promise<string[]> {
  return request({ method: "GET", url: "/api/activities/types" });
}

export function getActivity(id: number): Promise<ActivityDetail> {
  return request({ method: "GET", url: `/api/activities/${id}` });
}
