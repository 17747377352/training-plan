import type { FtpRecord, TrainingLoad } from "../types/api";
import { request } from "../utils/request";

export interface TrainingLoadQuery {
  startDate?: string;
  endDate?: string;
}

/** 每日训练状态与负荷（ACWR、急性/慢性负荷、负荷平衡诊断）。 */
export function listTrainingLoad(
  query: TrainingLoadQuery,
): Promise<TrainingLoad[]> {
  return request({ method: "GET", url: "/api/training-load", params: query });
}

/** 骑行 FTP 历史，按生效日期升序。 */
export function listFtp(): Promise<FtpRecord[]> {
  return request({ method: "GET", url: "/api/ftp" });
}
