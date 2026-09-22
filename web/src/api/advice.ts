import { request } from "../utils/request";

export type AdviceLight = "GREEN" | "YELLOW" | "RED" | "UNKNOWN" | "INFO";
export interface TrainingAdvice {
  calendarDate: string;
  ruleVersion: string;
  garminAccountId?: number;
  sourceDescription: string;
  light: "GREEN" | "YELLOW" | "RED";
  headline: string;
  summary: string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  availableRecoverySignals: number;
  factors: Array<{
    key: string;
    label: string;
    light: AdviceLight;
    recoverySignal: boolean;
    available: boolean;
    sourceDate?: string;
    value: string;
    explanation: string;
  }>;
  actions: string[];
  wattsPerKg?: number;
  prescription: {
    type: "REST" | "RECOVERY" | "ENDURANCE";
    title: string;
    durationMinutes: number;
    intensity: string;
    purpose: string;
    adjustment: string;
    steps: Array<{
      name: string;
      minutes: number;
      ftpPercentMin?: number;
      ftpPercentMax?: number;
      powerMinWatts?: number;
      powerMaxWatts?: number;
      effort: string;
    }>;
  };
}

export function getTrainingAdvice(): Promise<TrainingAdvice> {
  return request({ method: "GET", url: "/api/training-advice" });
}

export interface AiUsage {
  calendarDate: string;
  limitPerDay: number;
  usedToday: number;
  remainingToday: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  reusedToday: number;
}

export interface GeneratedTrainingPlan {
  calendarDate: string;
  generatedAt: string;
  provider: string;
  model: string;
  promptVersion: string;
  dataFingerprint: string;
  dataSummary: string;
  light: TrainingAdvice["light"];
  rationale: string;
  prescription: TrainingAdvice["prescription"];
  /** 数据指纹未变，直接返回了已存计划而没有调用模型（不消耗配额）。 */
  reused: boolean;
  usage?: AiUsage | null;
}

/**
 * 用户点击后才发送生成请求；等待时间覆盖服务端的 90 秒模型读取超时。
 *
 * @param force true 表示忽略数据指纹强制重新调用模型（会消耗配额）
 */
export function generateTrainingPlan(
  force = false,
): Promise<GeneratedTrainingPlan> {
  return request({
    method: "POST",
    url: "/api/training-plans/generate",
    params: { force },
    timeout: 120_000,
  });
}

/** 当日 AI 用量与剩余配额。 */
export function getAiUsage(): Promise<AiUsage> {
  return request({ method: "GET", url: "/api/training-plans/usage" });
}

/**
 * 读取已保存的当天计划，用于刷新后恢复。
 *
 * 未生成过时后端返回 data: null —— 这是正常状态，不是错误，
 * 所以按 null 处理而不是抛出。
 */
export async function getStoredTrainingPlan(
  date?: string,
): Promise<GeneratedTrainingPlan | null> {
  const plan = await request<GeneratedTrainingPlan | null | undefined>({
    method: "GET",
    url: "/api/training-plans",
    params: { date },
  });
  // 后端按 NON_NULL 序列化，未生成过时 data 字段会被整个省略，
  // 这里统一成 null，避免调用方去区分 undefined。
  return plan ?? null;
}
