import type { CheckinForm, DailyCheckin } from "../types/api";
import { request } from "../utils/request";

export interface CheckinQuery {
  startDate?: string;
  endDate?: string;
}

export function listCheckins(query: CheckinQuery): Promise<DailyCheckin[]> {
  return request({ method: "GET", url: "/api/checkins", params: query });
}

/** 提交或更新某一天的打卡。 */
export function saveCheckin(
  calendarDate: string,
  form: CheckinForm,
): Promise<DailyCheckin> {
  return request({
    method: "PUT",
    url: `/api/checkins/${calendarDate}`,
    data: form,
  });
}

export function deleteCheckin(calendarDate: string): Promise<void> {
  return request({ method: "DELETE", url: `/api/checkins/${calendarDate}` });
}
