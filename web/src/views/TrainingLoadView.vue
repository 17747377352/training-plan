<script setup lang="ts">
import { LineChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
} from "echarts/components";
import { init, use, type ECharts, type EChartsCoreOption } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { ElDatePicker, ElInputNumber, ElPopconfirm } from "element-plus";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { deleteCheckin, listCheckins, saveCheckin } from "../api/checkins";
import { listFtp, listTrainingLoad } from "../api/training";
import PageHeading from "../components/PageHeading.vue";
import type { DailyCheckin, FtpRecord, TrainingLoad } from "../types/api";

use([
  LineChart,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  CanvasRenderer,
]);

type PeriodDays = 30 | 90 | 200;

const PERIOD_OPTIONS: Array<{ label: string; value: PeriodDays }> = [
  { label: "近 30 天", value: 30 },
  { label: "近 90 天", value: 90 },
  { label: "近 200 天", value: 200 },
];

/** Garmin 训练状态短语的中文文案。 */
const STATUS_LABELS: Record<string, string> = {
  PRODUCTIVE_6: "有效训练",
  PRODUCTIVE_5: "有效训练",
  MAINTAINING_4: "维持中",
  MAINTAINING_3: "维持中",
  RECOVERY_2: "恢复中",
  RECOVERY_1: "恢复中",
  UNPRODUCTIVE_7: "训练无效",
  STRAINED_8: "过度负荷",
  OVERREACHING_9: "过度训练",
  DETRAINING_0: "停训退化",
  NO_STATUS_1: "数据不足",
};

/** ACWR 区间状态的中文文案。 */
const ACWR_LABELS: Record<string, string> = {
  OPTIMAL: "最优区间",
  HIGH: "偏高",
  LOW: "偏低",
  VERY_HIGH: "过高",
  VERY_LOW: "过低",
};

/** 负荷平衡诊断的中文文案。 */
const BALANCE_LABELS: Record<string, string> = {
  BALANCED: "负荷均衡",
  AEROBIC_LOW_SHORTAGE: "低强度有氧不足",
  AEROBIC_HIGH_SHORTAGE: "高强度有氧不足",
  ANAEROBIC_SHORTAGE: "无氧不足",
  AEROBIC_LOW_EXCESS: "低强度有氧过多",
  AEROBIC_HIGH_EXCESS: "高强度有氧过多",
  ANAEROBIC_EXCESS: "无氧过多",
};

interface BalanceRow {
  label: string;
  actual: number | null;
  min: number | null;
  max: number | null;
  level: "low" | "ok" | "high" | "unknown";
}

const loading = ref(false);
const loadFailed = ref(false);
const period = ref<PeriodDays>(90);
const loadRows = ref<TrainingLoad[]>([]);
const ftpRows = ref<FtpRecord[]>([]);
const checkins = ref<DailyCheckin[]>([]);
const saving = ref(false);
const feedback = ref<{ type: "success" | "error"; text: string } | null>(null);

const checkinForm = reactive<{
  calendarDate: string;
  weightKg: number | null;
  rpe: number | null;
  note: string;
}>({ calendarDate: today(), weightKg: null, rpe: null, note: "" });

const chartRef = ref<HTMLDivElement | null>(null);
let chart: ECharts | null = null;

function today(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, "0");
  const day = `${now.getDate()}`.padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

function dateKeys(days: number): string[] {
  const keys: string[] = [];
  const end = new Date(`${today()}T00:00:00`);
  for (let offset = days - 1; offset >= 0; offset -= 1) {
    const day = new Date(end);
    day.setDate(end.getDate() - offset);
    const month = `${day.getMonth() + 1}`.padStart(2, "0");
    const date = `${day.getDate()}`.padStart(2, "0");
    keys.push(`${day.getFullYear()}-${month}-${date}`);
  }
  return keys;
}

const latest = computed<TrainingLoad | null>(
  () => loadRows.value[loadRows.value.length - 1] ?? null,
);

const currentFtp = computed<FtpRecord | null>(
  () => ftpRows.value[ftpRows.value.length - 1] ?? null,
);

/** 最近一次填了体重的打卡。 */
const latestWeight = computed<DailyCheckin | null>(() => {
  for (let i = checkins.value.length - 1; i >= 0; i -= 1) {
    if (checkins.value[i].weightKg != null) return checkins.value[i];
  }
  return null;
});

/** 功体比；缺体重或缺 FTP 时如实显示为空。 */
const wattsPerKg = computed<number | null>(() => {
  const ftp = currentFtp.value?.ftpWatts;
  const weight = latestWeight.value?.weightKg;
  if (ftp == null || weight == null || weight <= 0) return null;
  return ftp / weight;
});

function statusLabel(phrase?: string | null): string {
  if (!phrase) return "--";
  return STATUS_LABELS[phrase] ?? phrase;
}

function acwrLabel(status?: string | null): string {
  if (!status) return "--";
  return ACWR_LABELS[status] ?? status;
}

function acwrTagType(status?: string | null): "success" | "warning" | "danger" | "info" {
  if (status === "OPTIMAL") return "success";
  if (status === "HIGH" || status === "LOW") return "warning";
  if (status === "VERY_HIGH" || status === "VERY_LOW") return "danger";
  return "info";
}

const balanceRows = computed<BalanceRow[]>(() => {
  const row = latest.value;
  if (!row) return [];
  const build = (
    label: string,
    actual?: number | null,
    min?: number | null,
    max?: number | null,
  ): BalanceRow => {
    let level: BalanceRow["level"] = "unknown";
    if (actual != null && min != null && max != null) {
      if (actual < min) level = "low";
      else if (actual > max) level = "high";
      else level = "ok";
    }
    return { label, actual: actual ?? null, min: min ?? null, max: max ?? null, level };
  };
  return [
    build(
      "低强度有氧",
      row.loadAerobicLow,
      row.loadAerobicLowTargetMin,
      row.loadAerobicLowTargetMax,
    ),
    build(
      "高强度有氧",
      row.loadAerobicHigh,
      row.loadAerobicHighTargetMin,
      row.loadAerobicHighTargetMax,
    ),
    build(
      "无氧",
      row.loadAnaerobic,
      row.loadAnaerobicTargetMin,
      row.loadAnaerobicTargetMax,
    ),
  ];
});

const balanceText = computed(() => {
  const phrase = latest.value?.balanceFeedbackPhrase;
  if (!phrase) return null;
  return BALANCE_LABELS[phrase] ?? phrase;
});

/** 负荷占目标区间的比例，用于画条形。 */
function balancePercent(row: BalanceRow): number {
  if (row.actual == null || row.max == null || row.max <= 0) return 0;
  return Math.min(100, (row.actual / row.max) * 100);
}

function formatNumber(value?: number | null, digits = 0, suffix = ""): string {
  if (value == null || !Number.isFinite(value)) return "--";
  return `${value.toFixed(digits)}${suffix}`;
}

function formatWeight(value?: number | null): string {
  return value == null ? "未填" : `${value.toFixed(1)} kg`;
}

async function loadAll(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const days = dateKeys(period.value);
    const startDate = days[0];
    const endDate = days[days.length - 1];
    const [load, ftp, checkinList] = await Promise.all([
      listTrainingLoad({ startDate, endDate }),
      listFtp(),
      listCheckins({ startDate: dateKeys(200)[0], endDate }),
    ]);
    loadRows.value = load;
    ftpRows.value = ftp;
    checkins.value = checkinList;
  } catch {
    loadFailed.value = true;
    loadRows.value = [];
  } finally {
    loading.value = false;
  }
  await nextTick();
  renderChart();
}

function buildChartOption(): EChartsCoreOption {
  const days = dateKeys(period.value);
  const byDate = new Map(loadRows.value.map((row) => [row.calendarDate, row]));
  const acwr = days.map((day) => {
    const value = byDate.get(day)?.acwrPercent;
    return value == null ? null : value;
  });
  const acute = days.map((day) => byDate.get(day)?.acuteLoad ?? null);
  const chronic = days.map((day) => byDate.get(day)?.chronicLoad ?? null);
  const tunnelMin = days.map((day) => byDate.get(day)?.chronicLoadMin ?? null);
  const tunnelMax = days.map((day) => byDate.get(day)?.chronicLoadMax ?? null);

  return {
    animationDuration: 300,
    color: ["#2ca58d", "#c45656", "#315f9f", "#9a72b0"],
    tooltip: {
      trigger: "axis",
      backgroundColor: "rgba(21, 34, 53, 0.94)",
      borderWidth: 0,
      textStyle: { color: "#ffffff", fontSize: 11 },
    },
    legend: { top: 0, textStyle: { color: "#5b6b7c", fontSize: 11 } },
    grid: { left: 46, right: 20, top: 54, bottom: 34 },
    xAxis: {
      type: "category",
      data: days,
      axisLabel: {
        color: "#8792a1",
        fontSize: 10,
        formatter: (value: string) => value.slice(5),
      },
      axisLine: { lineStyle: { color: "#e6ecef" } },
    },
    yAxis: [
      {
        type: "value",
        name: "负荷",
        nameTextStyle: { color: "#8792a1", fontSize: 10 },
        axisLabel: { color: "#8792a1", fontSize: 10 },
        splitLine: { lineStyle: { color: "#f1f5f7" } },
      },
      {
        type: "value",
        name: "ACWR %",
        nameTextStyle: { color: "#8792a1", fontSize: 10 },
        axisLabel: { color: "#8792a1", fontSize: 10 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: "急性负荷",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: acute,
      },
      {
        name: "慢性负荷",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: chronic,
        // 慢性负荷的合理隧道：低于下界是练少了，高于上界是练多了
        markArea: {
          silent: true,
          itemStyle: { color: "rgba(44, 165, 141, 0.08)" },
          data: [[{ yAxis: tunnelMin[0] ?? 0 }, { yAxis: tunnelMax[0] ?? 0 }]],
        },
      },
      {
        name: "ACWR %",
        type: "line",
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: acwr,
        markLine: {
          silent: true,
          symbol: "none",
          lineStyle: { color: "#c45656", type: "dashed", width: 1 },
          label: { formatter: "最优 50-75%", color: "#c45656", fontSize: 10 },
          data: [{ yAxis: 80 }],
        },
      },
    ],
  };
}

function renderChart(): void {
  if (!chartRef.value) return;
  if (!chart) {
    chart = init(chartRef.value);
    window.addEventListener("resize", resizeChart);
  }
  chart.setOption(buildChartOption(), true);
  chart.resize();
}

function resizeChart(): void {
  chart?.resize();
}

async function submitCheckin(): Promise<void> {
  feedback.value = null;
  if (
    checkinForm.weightKg == null &&
    checkinForm.rpe == null &&
    !checkinForm.note.trim()
  ) {
    feedback.value = { type: "error", text: "体重、RPE 与备注至少要填一项" };
    return;
  }
  saving.value = true;
  try {
    await saveCheckin(checkinForm.calendarDate, {
      weightKg: checkinForm.weightKg,
      rpe: checkinForm.rpe,
      note: checkinForm.note.trim() || null,
    });
    feedback.value = { type: "success", text: `${checkinForm.calendarDate} 已保存` };
    checkinForm.note = "";
    await loadAll();
  } catch (error) {
    feedback.value = {
      type: "error",
      text: error instanceof Error ? error.message : "保存失败，请稍后再试",
    };
  } finally {
    saving.value = false;
  }
}

async function removeCheckin(calendarDate: string): Promise<void> {
  feedback.value = null;
  try {
    await deleteCheckin(calendarDate);
    feedback.value = { type: "success", text: `${calendarDate} 已删除` };
    await loadAll();
  } catch {
    feedback.value = { type: "error", text: "删除失败" };
  }
}

const recentCheckins = computed(() => [...checkins.value].reverse().slice(0, 10));

watch(period, () => {
  void loadAll();
});

onMounted(() => {
  void loadAll();
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeChart);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <section class="page-container training-load-page">
    <PageHeading
      eyebrow="训练负荷"
      title="负荷与恢复"
      description="Garmin 已按完整历史算好急性/慢性负荷与负荷平衡诊断，这里直接用；体重与主观疲劳度由你自己填。"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </PageHeading>

    <el-alert
      v-if="loadFailed"
      title="训练负荷加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="load-error"
    />

    <div class="load-summary">
      <el-card class="load-summary-card" shadow="never">
        <p class="load-summary-label">训练状态</p>
        <p class="load-summary-value">{{ statusLabel(latest?.trainingStatusPhrase) }}</p>
        <p class="load-summary-meta">
          更新于 {{ latest?.calendarDate ?? "--" }}
        </p>
      </el-card>
      <el-card class="load-summary-card" shadow="never">
        <p class="load-summary-label">ACWR（急性/慢性）</p>
        <p class="load-summary-value">
          {{ formatNumber(latest?.acwrPercent, 0, "%") }}
        </p>
        <p class="load-summary-meta">
          <el-tag :type="acwrTagType(latest?.acwrStatus)" size="small" effect="light">
            {{ acwrLabel(latest?.acwrStatus) }}
          </el-tag>
        </p>
      </el-card>
      <el-card class="load-summary-card" shadow="never">
        <p class="load-summary-label">急性 / 慢性负荷</p>
        <p class="load-summary-value">
          {{ formatNumber(latest?.acuteLoad) }} / {{ formatNumber(latest?.chronicLoad) }}
        </p>
        <p class="load-summary-meta">
          合理区间 {{ formatNumber(latest?.chronicLoadMin) }}–{{
            formatNumber(latest?.chronicLoadMax)
          }}
        </p>
      </el-card>
      <el-card class="load-summary-card" shadow="never">
        <p class="load-summary-label">当前 FTP</p>
        <p class="load-summary-value">
          {{ currentFtp ? `${currentFtp.ftpWatts} W` : "--" }}
        </p>
        <p class="load-summary-meta">
          {{ currentFtp ? `${currentFtp.effectiveDate} 起` : "未取到" }}
        </p>
      </el-card>
      <el-card class="load-summary-card" shadow="never">
        <p class="load-summary-label">功体比</p>
        <p class="load-summary-value">
          {{ wattsPerKg == null ? "--" : `${wattsPerKg.toFixed(2)} W/kg` }}
        </p>
        <p class="load-summary-meta">
          <template v-if="wattsPerKg == null">需要体重，请在下方打卡填写</template>
          <template v-else>
            {{ currentFtp?.ftpWatts }} W ÷ {{ formatWeight(latestWeight?.weightKg) }}
          </template>
        </p>
      </el-card>
    </div>

    <el-card class="balance-card" shadow="never">
      <div class="balance-head">
        <p class="load-section-title">负荷平衡诊断</p>
        <el-tag
          v-if="balanceText"
          :type="latest?.balanceFeedbackPhrase === 'BALANCED' ? 'success' : 'warning'"
          effect="light"
        >
          {{ balanceText }}
        </el-tag>
      </div>
      <ul class="balance-list">
        <li v-for="row in balanceRows" :key="row.label" :class="`balance-${row.level}`">
          <div class="balance-row-head">
            <strong>{{ row.label }}</strong>
            <span>
              实际 {{ formatNumber(row.actual) }} · 目标
              {{ formatNumber(row.min) }}–{{ formatNumber(row.max) }}
            </span>
          </div>
          <div class="balance-bar">
            <div class="balance-bar-fill" :style="{ width: `${balancePercent(row)}%` }" />
          </div>
        </li>
      </ul>
      <p class="balance-hint">
        Garmin 按 4 周累积负荷与目标区间对比给出结论。低强度有氧对应 Z1–Z2
        轻松骑，高强度有氧对应阈值附近，无氧对应冲刺与短间歇。
      </p>
    </el-card>

    <el-card class="load-chart-card" shadow="never">
      <div class="load-chart-head">
        <p class="load-section-title">负荷趋势</p>
        <el-select v-model="period" class="load-period-select" size="small">
          <el-option
            v-for="option in PERIOD_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </div>
      <div ref="chartRef" class="load-chart" />
    </el-card>

    <el-card class="checkin-card" shadow="never">
      <p class="load-section-title">每日打卡</p>
      <p class="checkin-hint">
        体重与主观疲劳度在 Garmin API 里不存在，只能手填。体重填一次就能算出功体比。
      </p>
      <div class="checkin-form">
        <ElDatePicker
          v-model="checkinForm.calendarDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="日期"
          class="checkin-date"
        />
        <ElInputNumber
          v-model="checkinForm.weightKg"
          :min="20"
          :max="300"
          :precision="2"
          :step="0.1"
          controls-position="right"
          placeholder="体重 kg"
          class="checkin-weight"
        />
        <el-select
          v-model="checkinForm.rpe"
          clearable
          placeholder="RPE 1-10"
          class="checkin-rpe"
        >
          <el-option
            v-for="value in 10"
            :key="value"
            :label="`RPE ${value}`"
            :value="value"
          />
        </el-select>
        <el-input
          v-model="checkinForm.note"
          placeholder="备注（睡眠、状态、腿感…）"
          maxlength="512"
          class="checkin-note"
        />
        <el-button type="primary" :loading="saving" @click="submitCheckin">
          保存
        </el-button>
      </div>

      <el-alert
        v-if="feedback"
        :title="feedback.text"
        :type="feedback.type === 'success' ? 'success' : 'error'"
        :closable="false"
        show-icon
        class="checkin-feedback"
      />

      <el-empty v-if="recentCheckins.length === 0" description="还没有打卡记录" />
      <el-table v-else :data="recentCheckins" class="checkin-table" row-key="calendarDate">
        <el-table-column label="日期" prop="calendarDate" width="120" />
        <el-table-column label="体重" width="100">
          <template #default="{ row }">{{ formatWeight(row.weightKg) }}</template>
        </el-table-column>
        <el-table-column label="RPE" width="80">
          <template #default="{ row }">{{ row.rpe ?? "--" }}</template>
        </el-table-column>
        <el-table-column label="备注" prop="note" min-width="200">
          <template #default="{ row }">{{ row.note || "--" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <ElPopconfirm
              title="删除这条打卡？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="removeCheckin(row.calendarDate)"
            >
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </ElPopconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>
