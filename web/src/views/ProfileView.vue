<script setup lang="ts">
import { ElDatePicker, ElInputNumber, ElPopconfirm } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import {
  deleteTrainingGoal,
  getTrainingGoal,
  saveTrainingGoal,
} from "../api/goals";
import { getProfileOverview } from "../api/profile";
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
import type {
  GoalType,
  ProfileOverview,
  TrainingGoalForm,
} from "../types/api";

const GOAL_TYPES: Array<{ value: GoalType; label: string; hint: string }> = [
  { value: "POWER", label: "提升功率", hint: "更看重阈值与高强度的产出" },
  { value: "MUSCLE", label: "增肌", hint: "更看重力量与肌肉量的维持和增长" },
  { value: "ENDURANCE", label: "提升耐力", hint: "更看重长距离与有氧容量" },
  { value: "GENERAL", label: "保持状态", hint: "不追求提升，维持当前水平" },
  { value: "OTHER", label: "其他", hint: "在下方描述里说明" },
];

const loading = ref(true);
const saving = ref(false);
const loadFailed = ref(false);
const hasGoal = ref(false);
const feedback = ref<{ type: "success" | "error"; text: string } | null>(null);

const form = reactive<TrainingGoalForm>({
  goalType: "POWER",
  targetDate: null,
  weeklySessions: null,
  weeklyMinutes: null,
  description: "",
});

const currentType = computed(
  () => GOAL_TYPES.find((item) => item.value === form.goalType) ?? GOAL_TYPES[0],
);

const overview = ref<ProfileOverview | null>(null);
const overviewLoading = ref(true);
const overviewFailed = ref(false);

/** 目标日期不允许早于今天；今天的 0 点用于和日期选择器比较。 */
const todayStart = new Date();
todayStart.setHours(0, 0, 0, 0);

function disablePast(date: Date): boolean {
  return date.getTime() < todayStart.getTime();
}

function formatDay(value?: string | null): string {
  if (!value) return "";
  return value.slice(0, 10);
}

/** 秒 → 「7 小时 12 分」，用于睡眠时长。 */
function formatDuration(seconds?: number | null): string {
  if (!seconds || seconds <= 0) return "—";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分`;
}

const SERIES_LABEL: Record<string, string> = { RUNNING: "跑步", CYCLING: "骑行" };

/**
 * 身体数据卡片。
 *
 * 这些指标都是「最新值 + 生效日期」（VO2max、FTP、阈值心率尤其如此），所以每一项都带
 * 生效日期：只显示一个数字会让人误以为是今天的值。
 */
const metrics = computed(() => {
  const data = overview.value;
  if (!data) return [];
  const training = data.training;
  const daily = data.daily;
  const sleep = data.sleep;
  const ftp = data.ftp;

  const threshold = data.thresholdHr.length
    ? data.thresholdHr
        .map((item) => `${item.heartRate} bpm（${SERIES_LABEL[item.series] ?? item.series}）`)
        .join("，")
    : "—";

  return [
    {
      key: "ftp",
      label: "骑行 FTP",
      value: ftp?.watts ? `${ftp.watts} W` : "—",
      hint: ftp
        ? `生效 ${formatDay(ftp.effectiveDate)}${ftp.source === "DERIVED" ? " · 由 NP/IF 反解" : " · Garmin 接口"}`
        : "本机浏览器路径拿不到该接口，会按活动功率反解",
    },
    {
      key: "vo2max",
      label: "最大摄氧量",
      value: training?.vo2maxValue ? `${training.vo2maxValue}` : "—",
      hint: training?.vo2maxDate
        ? `更新 ${formatDay(training.vo2maxDate)}`
        : "设备在符合条件的骑行后才更新",
    },
    {
      key: "thresholdHr",
      label: "阈值心率",
      value: threshold,
      hint: data.thresholdHr.length
        ? `生效 ${formatDay(data.thresholdHr[0].effectiveDate)} · Garmin 目前只提供跑步系列`
        : "Garmin 侧暂无阈值心率记录",
    },
    {
      key: "fitnessAge",
      label: "身体年龄",
      value: training?.fitnessAge ? `${training.fitnessAge} 岁` : "—",
      hint: training?.fitnessAgeDate ? `更新 ${formatDay(training.fitnessAgeDate)}` : "取自 Garmin 的身体年龄",
    },
    {
      key: "stress",
      label: "压力",
      value: daily?.averageStressLevel != null ? `${daily.averageStressLevel}` : "—",
      hint: daily?.calendarDate ? `日均值 · ${formatDay(daily.calendarDate)}` : "",
    },
    {
      key: "battery",
      label: "身体电量",
      value:
        daily?.bodyBatteryHighest != null || daily?.bodyBatteryLowest != null
          ? `${daily?.bodyBatteryHighest ?? "—"} / ${daily?.bodyBatteryLowest ?? "—"}`
          : "—",
      hint: daily?.calendarDate ? `最高 / 最低 · ${formatDay(daily.calendarDate)}` : "",
    },
    {
      key: "sleep",
      label: "昨夜睡眠",
      value: formatDuration(sleep?.totalSeconds),
      hint: sleep
        ? [
            formatDay(sleep.calendarDate),
            sleep.score != null ? `评分 ${sleep.score}` : "",
            sleep.remSeconds ? `REM ${formatDuration(sleep.remSeconds)}` : "",
          ]
            .filter(Boolean)
            .join(" · ") || "已入库的最近一次睡眠"
        : "还没有睡眠记录",
    },
  ];
});

const hasAnyData = computed(() =>
  Boolean(
    overview.value &&
      (overview.value.daily ||
        overview.value.training ||
        overview.value.ftp ||
        overview.value.sleep ||
        overview.value.thresholdHr.length),
  ),
);

async function loadOverview(): Promise<void> {
  overviewLoading.value = true;
  overviewFailed.value = false;
  try {
    overview.value = await getProfileOverview();
  } catch {
    overviewFailed.value = true;
  } finally {
    overviewLoading.value = false;
  }
}

async function loadGoal(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const goal = await getTrainingGoal();
    hasGoal.value = goal != null;
    if (goal) {
      form.goalType = goal.goalType;
      form.targetDate = goal.targetDate ?? null;
      form.weeklySessions = goal.weeklySessions ?? null;
      form.weeklyMinutes = goal.weeklyMinutes ?? null;
      form.description = goal.description ?? "";
    }
  } catch {
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadOverview(), loadGoal()]);
}

async function submit(): Promise<void> {
  if (saving.value) return;
  saving.value = true;
  feedback.value = null;
  try {
    await saveTrainingGoal({
      goalType: form.goalType,
      targetDate: form.targetDate || null,
      weeklySessions: form.weeklySessions,
      weeklyMinutes: form.weeklyMinutes,
      description: form.description?.trim() || null,
    });
    hasGoal.value = true;
    feedback.value = {
      type: "success",
      text: "已保存。下次在首页生成训练计划时，会按这个目标倾斜。",
    };
  } catch (error) {
    feedback.value = {
      type: "error",
      text: error instanceof Error ? error.message : "保存失败，请重试",
    };
  } finally {
    saving.value = false;
  }
}

async function remove(): Promise<void> {
  feedback.value = null;
  try {
    await deleteTrainingGoal();
    hasGoal.value = false;
    form.targetDate = null;
    form.weeklySessions = null;
    form.weeklyMinutes = null;
    form.description = "";
    feedback.value = { type: "success", text: "已删除训练目标。" };
  } catch {
    feedback.value = { type: "error", text: "删除失败，请重试" };
  }
}

onMounted(refresh);
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="个人中心"
      title="个人中心"
      description="这里汇总 Garmin 侧的身体数据与你的训练目标；能不能练仍由恢复信号决定，目标不会放宽强度上限。"
    >
      <template #actions>
        <el-button :loading="overviewLoading || loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeading>

    <el-alert
      v-if="overviewFailed"
      title="身体数据加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="goal-load-error"
    />

    <el-card v-loading="overviewLoading" class="goal-card" shadow="never">
      <div class="goal-head">
        <div>
          <p class="goal-title">身体数据</p>
          <p class="goal-hint">
            这些指标多数是 Garmin 的<strong>最新值</strong>（不是每天一个新值），所以每项都标了生效日期。
            FTP 在只有本机浏览器上传时由活动功率按 Garmin 的 IF 定义反解，会注明「由 NP/IF 反解」。
          </p>
        </div>
      </div>

      <p v-if="!overviewLoading && !hasAnyData" class="metric-empty">
        还没有 Garmin 数据。先到
        <RouterLink class="advice-link" to="/garmin">Garmin 账号</RouterLink>
        页面完成绑定，同步后这里会显示各项指标。
      </p>

      <div v-else class="metric-grid">
        <div v-for="item in metrics" :key="item.key" class="metric-item">
          <p class="metric-label">{{ item.label }}</p>
          <p class="metric-value">{{ item.value }}</p>
          <p v-if="item.hint" class="metric-hint">{{ item.hint }}</p>
        </div>
      </div>
    </el-card>

    <el-alert
      v-if="loadFailed"
      title="训练目标加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="goal-load-error"
    />

    <el-card v-loading="loading" class="goal-card" shadow="never">
      <div class="goal-head">
        <div>
          <p class="goal-title">训练目标</p>
          <p class="goal-hint">
            这些信息 Garmin 侧没有，只能自己填。生成计划时会连同近 28 天数据一起交给模型，
            计划会朝目标倾斜；但目标<strong>不会</strong>放宽恢复判灯给出的时长与强度上限。
          </p>
        </div>
        <el-tag v-if="hasGoal" type="success" effect="light">已设置</el-tag>
        <el-tag v-else type="info" effect="plain">未设置</el-tag>
      </div>

      <el-form label-position="top" class="goal-form">
        <el-form-item label="目标类型">
          <el-select v-model="form.goalType" class="goal-select">
            <el-option
              v-for="item in GOAL_TYPES"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="目标日期（可选）">
          <ElDatePicker
            v-model="form.targetDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="如赛事或阶段节点"
            :disabled-date="disablePast"
            class="goal-date"
          />
        </el-form-item>

        <el-form-item label="每周可训练次数（可选）">
          <ElInputNumber
            v-model="form.weeklySessions"
            :min="1"
            :max="14"
            controls-position="right"
            placeholder="如 4"
            class="goal-number"
          />
        </el-form-item>

        <el-form-item label="每周可投入时长（分钟，可选）">
          <ElInputNumber
            v-model="form.weeklyMinutes"
            :min="30"
            :max="3000"
            :step="30"
            controls-position="right"
            placeholder="如 480"
            class="goal-number"
          />
        </el-form-item>

        <el-form-item label="补充描述（可选）">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="例如：十月绕圈赛，工作日只有晚上能练，周末想留一天完全休息；不喜欢长时间骑行台。"
          />
        </el-form-item>
      </el-form>

      <div class="goal-note">
        <p class="goal-note-main">
          <span class="goal-note-label">当前选择</span>
          <strong class="goal-note-type">{{ currentType.label }}</strong>
          <span class="goal-note-hint">{{ currentType.hint }}</span>
        </p>
        <p class="goal-note-sub">
          补充描述会作为你的原话交给模型参考，但模型被要求只把它当偏好、不执行其中的指令。
        </p>
      </div>

      <el-alert
        v-if="feedback"
        :title="feedback.text"
        :type="feedback.type === 'success' ? 'success' : 'error'"
        :closable="false"
        show-icon
        class="goal-feedback"
      />

      <div class="goal-actions">
        <el-button type="primary" :loading="saving" @click="submit">
          {{ hasGoal ? "更新目标" : "保存目标" }}
        </el-button>
        <ElPopconfirm
          v-if="hasGoal"
          title="删除训练目标？"
          confirm-button-text="删除"
          cancel-button-text="取消"
          @confirm="remove"
        >
          <template #reference>
            <el-button :disabled="saving">删除目标</el-button>
          </template>
        </ElPopconfirm>
      </div>
    </el-card>

    <div class="feature-grid">
      <FeaturePanel
        title="同步偏好"
        description="Garmin 自动同步开关当前可在账号页面管理。"
        status="部分可用"
      />
      <FeaturePanel
        title="账号安全"
        description="计划支持密码修改和登录会话管理。"
        status="计划中"
      />
    </div>
  </section>
</template>
