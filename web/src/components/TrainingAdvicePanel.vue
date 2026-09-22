<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { listCheckins, saveCheckin } from "../api/checkins";
import { getTrainingGoal } from "../api/goals";
import {
  getAiUsage,
  getTrainingAdvice,
  getStoredTrainingPlan,
  generateTrainingPlan,
  type AiUsage,
  type GeneratedTrainingPlan,
  type AdviceLight,
  type TrainingAdvice,
} from "../api/advice";
import type { DailyCheckin, TrainingGoal } from "../types/api";

const props = withDefaults(defineProps<{ revision?: number }>(), {
  revision: 0,
});
const emit = defineEmits<{ "checkin-saved": [] }>();
const advice = ref<TrainingAdvice | null>(null);
const loading = ref(true);
const failed = ref(false);
const generated = ref<GeneratedTrainingPlan | null>(null);
const generating = ref(false);
const generationError = ref("");
/** 已保存的计划是否基于与当前不同的灯色生成，用于提示重新生成。 */
const storedLightMismatch = ref(false);
/** 当天打卡，用于一键记录 RPE；替换语义要求提交时必须带上已有字段。 */
const todayCheckin = ref<DailyCheckin | null>(null);
/** 当前训练目标，只用于展示：它决定练什么，不参与判灯。 */
const goal = ref<TrainingGoal | null>(null);
const savingRpe = ref(false);
const quickError = ref("");
/** 当日 AI 用量，用于显示剩余次数并避免误以为可以无限生成。 */
const usage = ref<AiUsage | null>(null);
/** 本次生成是否为指纹复用（数据没变，没有调用模型）。 */
const reusedNotice = ref(false);

/**
 * 一键体感。四个选项各自对应一个引擎结论，点下去立刻能看到灯色或处方变化：
 * RPE ≤6 不影响判灯、7–8 黄灯减量、≥9 红灯休息。
 *
 * 说明：主观疲劳对判灯是**不对称**的——感觉差会下调安排，感觉好并不会解锁更长
 * 的处方（60 分钟那档还要求 Garmin 诊断为低强度有氧不足且四项依据齐全，实测
 * 200 天里这两件事从未同时成立）。这里如实呈现，不拿「填了就给 60 分钟」当诱因。
 */
const RPE_PRESETS = [
  { value: 3, label: "轻松", hint: "精力充足" },
  { value: 5, label: "正常", hint: "一般状态" },
  { value: 7, label: "有点累", hint: "需要减量" },
  { value: 9, label: "很累", hint: "优先休息" },
] as const;

async function loadTodayCheckin(day: string): Promise<void> {
  try {
    const rows = await listCheckins({ startDate: day, endDate: day });
    todayCheckin.value = rows[0] ?? null;
  } catch {
    todayCheckin.value = null;
  }
}

/** 当天已填的 RPE，没有则 null。 */
const todayRpe = computed(() => todayCheckin.value?.rpe ?? null);

async function setRpe(value: number): Promise<void> {
  // 用后端给的日期，避免浏览器时区与服务端 Asia/Shanghai 不一致
  const day = advice.value?.calendarDate;
  if (!day || savingRpe.value) return;
  savingRpe.value = true;
  quickError.value = "";
  try {
    // 打卡接口是替换语义：不带上已填的体重与备注会把它们清空
    await saveCheckin(day, {
      weightKg: todayCheckin.value?.weightKg ?? null,
      rpe: value,
      note: todayCheckin.value?.note ?? null,
    });
    await refresh();
    emit("checkin-saved");
  } catch (error) {
    quickError.value =
      error instanceof Error ? error.message : "打卡失败，请重试";
  } finally {
    savingRpe.value = false;
  }
}
const prescription = computed(
  () => generated.value?.prescription ?? advice.value?.prescription,
);
let requestId = 0;
const labels: Record<AdviceLight, string> = {
  GREEN: "正常",
  YELLOW: "需减量",
  RED: "优先恢复",
  UNKNOWN: "待补齐",
  INFO: "处方参数",
};
const confidenceLabels = { HIGH: "完整", MEDIUM: "部分缺失", LOW: "不足" };

async function refresh() {
  // 保存打卡和手动刷新可能交错返回，只展示最后一次评估，加载失败不保留旧处方。
  const id = ++requestId;
  loading.value = true;
  failed.value = false;
  advice.value = null;
  generated.value = null;
  storedLightMismatch.value = false;
  generationError.value = "";
  try {
    // 计划已经落库，刷新时读回来，不再每次进页面都清空
    const [result, stored, currentGoal, currentUsage] = await Promise.all([
      getTrainingAdvice(),
      getStoredTrainingPlan().catch(() => null),
      getTrainingGoal().catch(() => null),
      getAiUsage().catch(() => null),
    ]);
    goal.value = currentGoal;
    usage.value = currentUsage;
    if (id !== requestId) return;
    advice.value = result;
    await loadTodayCheckin(result.calendarDate);
    if (stored && stored.calendarDate === result.calendarDate) {
      generated.value = stored;
      // 灯色变了说明恢复状态已不同，旧计划要标注出来而不是假装仍适用
      storedLightMismatch.value = stored.light !== result.light;
    }
  } catch {
    if (id === requestId) failed.value = true;
  } finally {
    if (id === requestId) loading.value = false;
  }
}

async function generate(force = false) {
  if (generating.value || loading.value) return;
  const id = requestId;
  generating.value = true;
  generationError.value = "";
  reusedNotice.value = false;
  generated.value = null;
  try {
    const result = await generateTrainingPlan(force);
    if (id !== requestId) return;
    // 生成期间用户可能在另一页更新了数据；刷新判灯，并拒绝展示跨日或灯色已改变的旧计划。
    const latest = await getTrainingAdvice();
    if (id !== requestId) return;
    advice.value = latest;
    if (
      latest.calendarDate !== result.calendarDate ||
      latest.light !== result.light
    ) {
      generationError.value =
        "生成期间恢复状态已变化，请根据最新状态重新生成。";
      return;
    }
    generated.value = result;
    reusedNotice.value = result.reused;
    if (result.usage) usage.value = result.usage;
    else usage.value = await getAiUsage().catch(() => usage.value);
    storedLightMismatch.value = false;
  } catch (error) {
    if (id === requestId)
      generationError.value =
        error instanceof Error ? error.message : "DeepSeek 生成失败，请重试。";
  } finally {
    generating.value = false;
  }
}

watch(() => props.revision, refresh);
onMounted(refresh);
onBeforeUnmount(() => {
  requestId++;
});
</script>

<template>
  <section class="advice-panel" aria-label="今日训练建议" :aria-busy="loading">
    <div class="advice-heading">
      <div>
        <p class="advice-eyebrow">TODAY'S TRAINING</p>
        <h2>今日训练建议</h2>
      </div>
      <el-button :loading="loading" @click="refresh">重新评估</el-button>
    </div>
    <p v-if="loading" class="advice-state" role="status">
      正在汇总恢复状态与训练依据…
    </p>
    <div v-else-if="failed" class="advice-state" role="alert">
      <p>训练建议加载失败，请重试。</p>
      <el-button @click="refresh">重试</el-button>
    </div>
    <template v-else-if="advice && prescription">
      <div
        class="advice-verdict"
        :class="`signal-${advice.light.toLowerCase()}`"
        aria-live="polite"
      >
        <span class="signal-orb" aria-hidden="true" />
        <div class="verdict-copy">
          <h3>{{ advice.headline }}</h3>
          <p>{{ advice.summary }}</p>
          <span class="verdict-date"
            >{{ advice.calendarDate }} · 上海时间 · 恢复依据
            {{ advice.availableRecoverySignals }}/4（{{
              confidenceLabels[advice.confidence]
            }}）</span
          >
        </div>
        <div v-if="advice.wattsPerKg != null" class="advice-ratio">
          <strong>{{ advice.wattsPerKg.toFixed(2) }}</strong
          ><span>W/kg · 当前功体比</span>
        </div>
      </div>
      <div class="ai-generation">
        <div>
          <strong>让 DeepSeek 制定今天的训练计划</strong>
          <p>
            生成时会将近 28 天训练、恢复、打卡和骑行指标及 FTP 历史发送给
            DeepSeek，结合当前灯色安排训练。
          </p>
        </div>
        <div class="generation-actions">
          <el-button
            type="primary"
            :loading="generating"
            @click="generate(generated != null)"
          >
            {{
              generating
                ? "DeepSeek 正在生成…"
                : generated
                  ? "重新生成计划"
                  : "DeepSeek 生成计划"
            }}
          </el-button>
          <el-button
            v-if="generated"
            :loading="generating"
            @click="generate(true)"
          >
            强制重新生成
          </el-button>
        </div>
      </div>
      <el-alert
        v-if="generationError"
        :title="generationError"
        type="error"
        :closable="false"
        show-icon
        class="generation-error"
      />
      <el-alert
        v-if="reusedNotice"
        title="数据没有变化，已直接复用今天的计划，没有调用模型、也没有消耗次数。需要换一版请点「强制重新生成」。"
        type="info"
        :closable="false"
        show-icon
        class="generation-stale"
      />
      <el-alert
        v-if="storedLightMismatch"
        title="这份计划是按当时的恢复状态生成的，当前灯色已变化，建议重新生成。"
        type="warning"
        :closable="false"
        show-icon
        class="generation-stale"
      />
      <p v-if="usage" class="generation-usage">
        今日 DeepSeek 生成 {{ usage.usedToday }} / {{ usage.limitPerDay }} 次 · 剩余
        {{ usage.remainingToday }} 次
        <template v-if="usage.totalTokens > 0">
          · 已用 {{ usage.totalTokens }} tokens
        </template>
        <template v-if="usage.reusedToday > 0">
          · 复用 {{ usage.reusedToday }} 次（不计数）
        </template>
      </p>
      <p v-if="generated" class="generation-meta">
        {{ generated.provider }} · {{ generated.model }} ·
        {{ new Date(generated.generatedAt).toLocaleString("zh-CN") }} 生成<br />
        {{ generated.dataSummary }}
      </p>
      <div class="advice-content">
        <article class="prescription-card">
          <p class="advice-eyebrow">
            {{
              generated
                ? "DEEPSEEK · 今日训练计划"
                : "基础规则建议 · 尚未生成 AI 计划"
            }}
          </p>
          <h3>{{ prescription.title }}</h3>
          <p class="prescription-intensity">
            {{ prescription.durationMinutes }} 分钟 ·
            {{ prescription.intensity }}
          </p>
          <p>{{ prescription.purpose }}</p>
          <ol v-if="prescription.steps.length" class="prescription-steps">
            <li v-for="(step, index) in prescription.steps" :key="index">
              <span class="step-index">{{ index + 1 }}</span>
              <div>
                <div class="step-heading">
                  <strong>{{ step.name }}</strong
                  ><span>{{ step.minutes }} 分钟</span>
                </div>
                <p class="step-power">
                  <template
                    v-if="
                      step.powerMinWatts != null && step.powerMaxWatts != null
                    "
                    >{{ step.powerMinWatts }}–{{ step.powerMaxWatts }} W · </template
                  >{{ step.ftpPercentMin }}–{{ step.ftpPercentMax }}% FTP
                </p>
                <p v-if="step.powerMinWatts == null" class="step-effort">
                  当前 FTP 不可用，按下方体感执行
                </p>
                <p class="step-effort">{{ step.effort }}</p>
              </div>
            </li>
          </ol>
          <p class="prescription-adjustment">
            {{ prescription.adjustment }}
          </p>
          <RouterLink class="advice-link" to="/training-load#checkin"
            >记录今天的体重与疲劳 →</RouterLink
          >
        </article>
        <div class="advice-evidence">
          <h3>为什么是这个灯色</h3>
          <p class="evidence-intro">
            恢复信号决定灯色，FTP 与体重用于处方换算。
          </p>
          <div class="factor-grid">
            <article
              v-for="factor in advice.factors"
              :key="factor.key"
              class="factor-card"
            >
              <div class="factor-heading">
                <h4>{{ factor.label }}</h4>
                <span
                  class="factor-badge"
                  :class="`signal-${factor.light.toLowerCase()}`"
                  >{{ labels[factor.light] }}</span
                >
              </div>
              <strong class="factor-value">{{ factor.value }}</strong>
              <p>{{ factor.explanation }}</p>
              <span class="factor-date"
                >数据日期：{{ factor.sourceDate ?? "暂无记录" }}</span
              >
            </article>
          </div>
        </div>
      </div>
      <p class="advice-goal">
        <template v-if="goal">
          训练目标：<strong>{{ goal.goalLabel }}</strong>
          <template v-if="goal.daysToTarget != null">
            · 距目标 {{ goal.daysToTarget }} 天
          </template>
          <template v-if="goal.weeklySessions">
            · 每周 {{ goal.weeklySessions }} 次
          </template>
          <template v-if="goal.weeklyMinutes">
            / {{ goal.weeklyMinutes }} 分钟
          </template>
          <RouterLink class="advice-link" to="/settings">调整目标 →</RouterLink>
        </template>
        <template v-else>
          还没有训练目标，生成计划只能给出通用安排。<RouterLink
            class="advice-link"
            to="/settings"
            >去设置训练目标 →</RouterLink
          >
        </template>
      </p>
      <div class="quick-rpe">
        <div class="quick-rpe-head">
          <strong>今天感觉如何？</strong>
          <span v-if="todayRpe != null">
            已记录 RPE {{ todayRpe }} · 点其他选项可改
          </span>
          <span v-else>
            主观感受是客观指标看不到的那一面：感觉差时它会下调今天的安排，
            也是判定依据里唯一由你提供的维度
          </span>
        </div>
        <div class="quick-rpe-options">
          <button
            v-for="preset in RPE_PRESETS"
            :key="preset.value"
            type="button"
            class="quick-rpe-option"
            :class="{ active: todayRpe === preset.value }"
            :disabled="savingRpe"
            @click="setRpe(preset.value)"
          >
            <span class="quick-rpe-label">{{ preset.label }}</span>
            <span class="quick-rpe-hint">{{ preset.hint }} · RPE {{ preset.value }}</span>
          </button>
        </div>
        <p v-if="quickError" class="quick-rpe-error">{{ quickError }}</p>
      </div>
      <div v-if="advice.actions.length" class="advice-actions">
        <strong>补齐这些信息，建议会更明确</strong>
        <ul>
          <li v-for="action in advice.actions" :key="action">{{ action }}</li>
        </ul>
        <RouterLink class="advice-link" to="/garmin"
          >前往 Garmin 同步 →</RouterLink
        >
      </div>
      <details class="advice-method">
        <summary>判断规则与数据来源</summary>
        <p>
          任一红色恢复信号或至少两个黄色恢复信号 → 红灯；有黄色信号，或可用恢复
          依据不足三项 → 黄灯；可用依据达到三项且无异常 → 绿灯。缺一项但无异常时
          仍给绿灯，但处方收紧（45 分钟而非 60 分钟）。低负荷不自动要求补强度。
        </p>
        <p>{{ advice.sourceDescription }}。主观疲劳与体重取自你的手工打卡。</p>
        <p>
          这是用于安排骑行的保守规则建议，阈值尚未经过个人效果校准，不代表医学诊断。规则版本：{{
            advice.ruleVersion
          }}。
        </p>
      </details>
    </template>
  </section>
</template>

<style scoped>
.ai-generation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px;
  margin-bottom: 20px;
  border: 1px solid #d9e5ee;
  border-radius: 12px;
  background: #f2f7fb;
}
.ai-generation strong {
  color: #315f86;
  font-size: 13px;
}
.ai-generation p,
.generation-meta {
  color: #637585;
  font-size: 11px;
  line-height: 1.8;
  margin: 5px 0 0;
}
.generation-error,
.generation-meta {
  margin-bottom: 18px;
}
@media (max-width: 600px) {
  .ai-generation {
    align-items: stretch;
    flex-direction: column;
  }
}

.advice-panel {
  margin-bottom: 24px;
}
.advice-heading,
.factor-heading,
.step-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.advice-heading {
  margin-bottom: 18px;
}
.advice-heading h2,
.advice-content h3,
.factor-heading h4,
.advice-verdict h3 {
  margin: 0;
}
.advice-eyebrow {
  color: #728178;
  letter-spacing: 0.1em;
  font-size: 10px;
  font-weight: 700;
  margin: 0 0 6px;
}
.advice-heading h2 {
  font-size: 21px;
}
.advice-state {
  padding: 36px 24px;
  background: #fff;
  border: 1px solid #e2e9e5;
  border-radius: 16px;
  color: #64736c;
}
.advice-verdict {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px;
  border-radius: 16px;
  border: 1px solid currentColor;
  margin-bottom: 20px;
}
.signal-green {
  color: #21674a;
  background: #eff8f2;
}
.signal-yellow {
  color: #8b5a17;
  background: #fff8e8;
}
.signal-red {
  color: #9f3936;
  background: #fff1ef;
}
.signal-unknown {
  color: #626d7c;
  background: #f0f3f7;
}
.signal-info {
  color: #315f86;
  background: #edf5fb;
}
.signal-orb {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 8px #ffffff80;
  margin: 6px;
}
.verdict-copy {
  flex: 1;
}
.advice-verdict h3 {
  font-size: 22px;
}
.advice-verdict p {
  margin: 8px 0;
  line-height: 1.7;
  font-size: 13px;
}
.verdict-date {
  font-size: 11px;
}
.advice-ratio {
  display: flex;
  flex-direction: column;
  gap: 5px;
  text-align: right;
  white-space: nowrap;
}
.advice-ratio strong {
  font-size: 30px;
  font-variant-numeric: tabular-nums;
}
.advice-ratio span {
  font-size: 11px;
}
.advice-content {
  display: grid;
  grid-template-columns: minmax(260px, 0.8fr) minmax(0, 1.6fr);
  gap: 24px;
  align-items: start;
}
.prescription-card {
  background: #fff;
  border: 1px solid #e2e9e5;
  border-radius: 16px;
  padding: 24px;
}
.prescription-card h3 {
  font-size: 19px;
}
.prescription-card p,
.evidence-intro {
  line-height: 1.7;
  font-size: 12px;
  color: #63716c;
}
.prescription-card .prescription-intensity {
  font-size: 15px;
  color: #2e5544;
  font-weight: 600;
}
.prescription-steps {
  padding: 0;
  list-style: none;
  margin: 22px 0;
}
.prescription-steps li {
  display: grid;
  grid-template-columns: 25px 1fr;
  gap: 10px;
  margin-bottom: 20px;
}
.step-index {
  width: 24px;
  height: 24px;
  line-height: 24px;
  text-align: center;
  border-radius: 50%;
  background: #edf4ef;
  color: #456d52;
  font-size: 11px;
}
.step-heading {
  font-size: 12px;
}
.step-heading span {
  color: #64736c;
  white-space: nowrap;
}
.prescription-card .step-power {
  color: #2c4b3d;
  margin: 4px 0;
  font-variant-numeric: tabular-nums;
}
.prescription-card .step-effort {
  margin: 0;
  font-size: 11px;
}
.prescription-adjustment {
  padding-top: 16px;
  border-top: 1px solid #edf0ee;
}
.advice-link {
  display: inline-block;
  margin-top: 8px;
  color: #2f6750;
  font-size: 12px;
}
.advice-evidence > h3 {
  font-size: 16px;
  margin-top: 4px;
}
.evidence-intro {
  margin: 6px 0 14px;
}
.factor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.factor-card {
  padding: 16px;
  background: #fff;
  border: 1px solid #e6ece8;
  border-radius: 12px;
}
.factor-heading {
  align-items: start;
  gap: 5px;
}
.factor-heading h4 {
  font-size: 12px;
  line-height: 1.8;
}
.factor-badge {
  flex-shrink: 0;
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 10px;
}
.factor-value {
  display: block;
  font-size: 13px;
  line-height: 1.6;
  margin-top: 10px;
  overflow-wrap: anywhere;
}
.factor-card p {
  font-size: 11px;
  color: #64716b;
  line-height: 1.8;
  margin: 6px 0;
}
.factor-date {
  font-size: 10px;
  color: #78837d;
}
.advice-actions {
  background: #f5f7f4;
  border-radius: 12px;
  padding: 18px 22px;
  font-size: 12px;
  color: #53625a;
  margin-top: 20px;
}
.advice-actions ul {
  padding-left: 18px;
  line-height: 1.9;
  margin-bottom: 0;
}
.advice-method {
  color: #6c7871;
  font-size: 11px;
  line-height: 1.8;
  margin-top: 18px;
}
.advice-method summary {
  cursor: pointer;
}
@media (max-width: 1100px) {
  .advice-content {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .advice-verdict {
    padding: 18px;
    flex-wrap: wrap;
    gap: 12px;
  }
  .advice-verdict h3 {
    font-size: 18px;
  }
  .advice-ratio {
    width: 100%;
    text-align: left;
    padding-left: 42px;
  }
  .factor-grid {
    grid-template-columns: 1fr;
  }
  .prescription-card {
    padding: 18px;
  }
}
</style>
