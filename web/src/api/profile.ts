import type { ProfileOverview } from "../types/api";
import { request } from "../utils/request";

/**
 * 读取个人中心的 Garmin 身体数据。
 *
 * 各项都是「最新值 + 生效日期」，账号下没有数据时对应字段为 null（后端逐项判空）。
 */
export function getProfileOverview(): Promise<ProfileOverview> {
  return request<ProfileOverview>({ method: "GET", url: "/api/profile/overview" });
}
