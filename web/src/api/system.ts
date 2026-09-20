import type { SystemStatus } from "../types/api";
import { request } from "../utils/request";

export function getSystemStatus(): Promise<SystemStatus> {
  return request<SystemStatus>({
    url: "/api/system/health",
    method: "GET",
  });
}
