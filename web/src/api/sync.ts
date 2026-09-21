import type { PageResult, SyncJob, SyncJobQuery, SyncOverview } from "../types/api";
import { request } from "../utils/request";

export function listSyncJobs(query: SyncJobQuery): Promise<PageResult<SyncJob>> {
  return request({ method: "GET", url: "/api/sync/jobs", params: query });
}

export function getSyncOverview(): Promise<SyncOverview> {
  return request({ method: "GET", url: "/api/sync/jobs/overview" });
}

/**
 * 重试一次同步任务。
 *
 * 平台只是建任务并投递队列，不等待采集器执行，所以用默认超时即可；
 * 想看结果刷新列表就行。
 */
export function retrySyncJob(jobId: number): Promise<number> {
  return request({ method: "POST", url: `/api/sync/jobs/${jobId}/retry` });
}
