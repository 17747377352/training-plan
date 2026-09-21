import type { TrainingGoal, TrainingGoalForm } from "../types/api";
import { request } from "../utils/request";

/**
 * 读取训练目标。
 *
 * 未设置时后端返回 data: null（NON_NULL 序列化会整个省略该字段），
 * 这里统一成 null，调用方不必区分 undefined。
 */
export async function getTrainingGoal(): Promise<TrainingGoal | null> {
  const goal = await request<TrainingGoal | null | undefined>({
    method: "GET",
    url: "/api/training-goals",
  });
  return goal ?? null;
}

export function saveTrainingGoal(
  form: TrainingGoalForm,
): Promise<TrainingGoal> {
  return request({ method: "PUT", url: "/api/training-goals", data: form });
}

export function deleteTrainingGoal(): Promise<void> {
  return request({ method: "DELETE", url: "/api/training-goals" });
}
