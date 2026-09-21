<script setup lang="ts">
import { BarChart, LineChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from "echarts/components";
import { init, use, type ECharts, type EChartsCoreOption } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { listDailyHealth, listHrv, listSleep } from "../api/health";
import PageHeading from "../components/PageHeading.vue";
import type {
  DailyHealthTrend,
  HrvTrend,
  SleepTrend,
  TrendQuery,
} from "../types/api";

use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
]);

type PeriodDays = 7 | 30 | 90;

interface AggregatedSleep {
  sleepTimeSeconds: number | null;
  deepSleepSeconds: number | null;
  lightSleepSeconds: number | null;
  remSleepSeconds: number | null;
  awakeSleepSeconds: number | null;
  sleepScore: number | null;
  avgSleepHrv: number | null;
  avgSpo2: number | null;
  avgRespiration: number | null;
  mainSleepSeconds: number;
}

interface TrendRow {
  calendarDate: string;
  label: string;
  daily?: DailyHealthTrend;
  hrv?: HrvTrend;
  sleep?: AggregatedSleep;
}

/** 周期统计：本期平均值，用于与上一周期对比。 */
interface PeriodStats {
  restingHeartRate: number | null;
  hrv: number | null;
  sleepHours: number | null;
  sleepScore: number | null;
}

/** 单日恢复状态标记。 */
interface DayFlag {
  level: "yellow" | "red";
  reasons: string[];
}

/**
 * 恢复状态判定阈值，对应训练方案第六节的自动可判部分。
 * 逐条写明以便调整时能对应到规则。
 */
const RHR_YELLOW_DELTA = 5; // 静息心率高于个人 7 日基线 5 bpm
const RHR_RED_DELTA = 10; // 高出 10 bpm 升级为需重视
const SHORT_SLEEP_HOURS = 6; // 低于 6 小时视为睡眠明显不足
const RED_CONSECUTIVE_DAYS = 3; // 连续多日未恢复则升级
const BASELINE_MIN_SAMPLES = 3; // 基线至少要有几天数据才算得上

const PERIOD_OPTIONS: Array<{ label: string; value: PeriodDays }> = [
  { label: "7 天", value: 7 },
  { label: "30 天", value: 30 },
  { label: "90 天", value: 90 },
];

const periodDays = ref<PeriodDays>(30);
const loading = ref(false);
const loadFailed = ref(false);
const dailyHealth = ref<DailyHealthTrend[]>([]);
const hrvRecords = ref<HrvTrend[]>([]);
const sleepRecords = ref<SleepTrend[]>([]);

const recoveryChartElement = ref<HTMLElement | null>(null);
const heartRateChartElement = ref<HTMLElement | null>(null);
const activityChartElement = ref<HTMLElement | null>(null);
let recoveryChart: ECharts | null = null;
let heartRateChart: ECharts | null = null;
let activityChart: ECharts | null = null;

const sleepByDate = computed(() => {
  const result = new Map<string, AggregatedSleep>();
  for (const row of sleepRecords.value) {
    const current = result.get(row.calendarDate) ?? emptyAggregatedSleep();
    current.sleepTimeSeconds = addNullable(
      current.sleepTimeSeconds,
      row.sleepTimeSeconds,
    );
    current.deepSleepSeconds = addNullable(
      current.deepSleepSeconds,
      row.deepSleepSeconds,
    );
    current.lightSleepSeconds = addNullable(
      current.lightSleepSeconds,
      row.lightSleepSeconds,
    );
    current.remSleepSeconds = addNullable(
      current.remSleepSeconds,
      row.remSleepSeconds,
    );
    current.awakeSleepSeconds = addNullable(
      current.awakeSleepSeconds,
      row.awakeSleepSeconds,
    );

    const duration = row.sleepTimeSeconds ?? 0;
    if (duration >= current.mainSleepSeconds) {
      current.mainSleepSeconds = duration;
      current.sleepScore = row.sleepScore ?? null;
      current.avgSleepHrv = row.avgSleepHrv ?? null;
      current.avgSpo2 = row.avgSpo2 ?? null;
      current.avgRespiration = row.avgRespiration ?? null;
    }
    result.set(row.calendarDate, current);
  }
  return result;
});

function buildRows(dateKeys: string[]): TrendRow[] {
  const dailyMap = new Map(
    dailyHealth.value.map((row) => [row.calendarDate, row]),
  );
  const hrvMap = new Map(
    hrvRecords.value.map((row) => [row.calendarDate, row]),
  );
  return dateKeys.map((calendarDate) => ({
    calendarDate,
    label: formatChartDate(calendarDate),
    daily: dailyMap.get(calendarDate),
    hrv: hrvMap.get(calendarDate),
    sleep: sleepByDate.value.get(calendarDate),
  }));
}

const trendRows = computed<TrendRow[]>(() =>
  buildRows(buildDateKeys(periodDays.value)),
);

/** 上一个等长周期，用于计算环比。 */
const previousRows = computed<TrendRow[]>(() =>
  buildRows(buildPreviousDateKeys(periodDays.value)),
);

const availableRows = computed(() =>
  trendRows.value
    .filter((row) => Boolean(row.daily || row.hrv || row.sleep))
    .reverse(),
);

const hasAnyData = computed(() => availableRows.value.length > 0);
const latestDataDate = computed(
  () => availableRows.value[0]?.calendarDate ?? null,
);
const latestSleepRow = computed(() =>
  [...trendRows.value]
    .reverse()
    .find((row) => row.sleep?.sleepTimeSeconds != null),
);
const latestHrvRow = computed(() =>
  [...trendRows.value].reverse().find((row) => row.hrv?.lastNightAvg != null),
);
const latestHeartRateRow = computed(() =>
  [...trendRows.value]
    .reverse()
    .find((row) => row.daily?.restingHeartRate != null),
);
const coverageText = computed(
  () => availableRows.value.length + " / " + periodDays.value + " 天有数据",
);

function averageOf(values: Array<number | null | undefined>): number | null {
  const numbers = values.filter(
    (value): value is number => value != null && Number.isFinite(value),
  );
  if (numbers.length === 0) return null;
  return numbers.reduce((sum, value) => sum + value, 0) / numbers.length;
}

function buildStats(rows: TrendRow[]): PeriodStats {
  const active = rows.filter((row) => row.daily || row.hrv || row.sleep);
  return {
    restingHeartRate: averageOf(
      active.map((row) => row.daily?.restingHeartRate),
    ),
    hrv: averageOf(active.map((row) => row.hrv?.lastNightAvg)),
    sleepHours: averageOf(active.map((row) => sleepHours(row))),
    sleepScore: averageOf(active.map((row) => row.sleep?.sleepScore)),
  };
}

const periodStats = computed(() => buildStats(trendRows.value));
const previousStats = computed(() => buildStats(previousRows.value));

/** 环比文案；上一周期无数据时返回“无对比”。 */
function formatDelta(
  current: number | null,
  previous: number | null,
  digits = 1,
  suffix = "",
): string {
  if (current == null) return "--";
  if (previous == null) return "无对比";
  const diff = current - previous;
  const sign = diff > 0 ? "+" : diff < 0 ? "−" : "±";
  return "较上期 " + sign + Math.abs(diff).toFixed(digits) + suffix;
}

/** 排除当天在内、向前 7 天的静息心率基线。 */
const restingHeartRateByDate = computed(() => {
  const map = new Map<string, number>();
  for (const row of dailyHealth.value) {
    if (row.restingHeartRate != null) {
      map.set(row.calendarDate, row.restingHeartRate);
    }
  }
  return map;
});

function restingHeartRateBaseline(calendarDate: string): number | null {
  const base = new Date(calendarDate + "T00:00:00");
  const samples: number[] = [];
  for (let offset = 1; offset <= 7; offset += 1) {
    const date = new Date(base);
    date.setDate(base.getDate() - offset);
    const value = restingHeartRateByDate.value.get(dateKey(date));
    if (value != null) samples.push(value);
  }
  if (samples.length < BASELINE_MIN_SAMPLES) return null;
  return samples.reduce((sum, value) => sum + value, 0) / samples.length;
}

/**
 * 逐日恢复标记：静息心率相对个人 7 日基线、睡眠时长两个可自动判定的维度。
 * 判定标准写在文件顶部的阈值常量里。
 */
const dayFlags = computed<Map<string, DayFlag>>(() => {
  const flags = new Map<string, DayFlag>();
  let consecutive = 0;
  for (const row of trendRows.value) {
    const reasons: string[] = [];
    let level: "yellow" | "red" | null = null;

    const restingHeartRate = row.daily?.restingHeartRate ?? null;
    const baseline =
      restingHeartRate == null
        ? null
        : restingHeartRateBaseline(row.calendarDate);
    if (restingHeartRate != null && baseline != null) {
      const delta = restingHeartRate - baseline;
      if (delta >= RHR_RED_DELTA) {
        level = "red";
        reasons.push("静息心率高于 7 日基线 " + delta.toFixed(0) + " bpm");
      } else if (delta >= RHR_YELLOW_DELTA) {
        level = "yellow";
        reasons.push("静息心率高于 7 日基线 " + delta.toFixed(0) + " bpm");
      }
    }

    const hours = sleepHours(row);
    if (hours != null && hours < SHORT_SLEEP_HOURS) {
      reasons.push("睡眠 " + hours.toFixed(1) + " 小时");
      if (level !== "red") level = "yellow";
    }

    if (level == null) {
      consecutive = 0;
      continue;
    }
    if (level === "yellow") {
      consecutive += 1;
      if (consecutive >= RED_CONSECUTIVE_DAYS) {
        level = "red";
        reasons.push("连续 " + consecutive + " 天需留意");
      }
    } else {
      consecutive = 0;
    }
    flags.set(row.calendarDate, { level, reasons });
  }
  return flags;
});

const flaggedRows = computed(() =>
  trendRows.value
    .filter((row) => dayFlags.value.has(row.calendarDate))
    .reverse(),
);

const flagLevelText: Record<DayFlag["level"], string> = {
  yellow: "需留意",
  red: "需重视",
};

function flagLevel(calendarDate: string): DayFlag | undefined {
  return dayFlags.value.get(calendarDate);
}

const dayDetailVisible = ref(false);
const selectedRow = ref<TrendRow | null>(null);

function openDayDetail(row: TrendRow): void {
  selectedRow.value = row;
  dayDetailVisible.value = true;
}

const selectedFlag = computed(() =>
  selectedRow.value ? flagLevel(selectedRow.value.calendarDate) : undefined,
);

function emptyAggregatedSleep(): AggregatedSleep {
  return {
    sleepTimeSeconds: null,
    deepSleepSeconds: null,
    lightSleepSeconds: null,
    remSleepSeconds: null,
    awakeSleepSeconds: null,
    sleepScore: null,
    avgSleepHrv: null,
    avgSpo2: null,
    avgRespiration: null,
    mainSleepSeconds: 0,
  };
}

function addNullable(
  current?: number | null,
  next?: number | null,
): number | null {
  if (next == null) return current ?? null;
  return (current ?? 0) + next;
}

function dateKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return year + "-" + month + "-" + day;
}

function buildDateKeys(days: number): string[] {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const result: string[] = [];
  for (let offset = days - 1; offset >= 0; offset -= 1) {
    const date = new Date(today);
    date.setDate(today.getDate() - offset);
    result.push(dateKey(date));
  }
  return result;
}

/** 上一个等长周期的日期键，升序。用于环比与滚动基线。 */
function buildPreviousDateKeys(days: number): string[] {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const result: string[] = [];
  for (let offset = days * 2 - 1; offset >= days; offset -= 1) {
    const date = new Date(today);
    date.setDate(today.getDate() - offset);
    result.push(dateKey(date));
  }
  return result;
}

/**
 * 一次取回「本周期 + 上一周期」，供环比和滚动基线使用。
 * 最多 2×90=180 天，仍在后端 366 天上限内。
 */
function buildQuery(): TrendQuery {
  const dates = buildPreviousDateKeys(periodDays.value);
  const current = buildDateKeys(periodDays.value);
  return {
    startDate: dates[0] ?? current[0],
    endDate: current[current.length - 1],
  };
}

function formatChartDate(value: string): string {
  const [, month, day] = value.split("-");
  return month + "/" + day;
}

function formatDate(value?: string | null): string {
  if (!value) return "--";
  return value.replaceAll("-", "/");
}

function formatNumber(value?: number | null, suffix = "", digits = 0): string {
  if (value == null || !Number.isFinite(value)) return "--";
  return value.toFixed(digits) + suffix;
}

function formatDistanceValue(meters?: number | null): string {
  if (meters == null || !Number.isFinite(meters)) return "--";
  return (meters / 1000).toFixed(2) + " km";
}

function formatHours(seconds?: number | null): string {
  if (seconds == null || !Number.isFinite(seconds)) return "--";
  return (seconds / 3600).toFixed(1) + " h";
}

function formatStatus(status?: string | null): string {
  const labels: Record<string, string> = {
    BALANCED: "平衡",
    UNBALANCED: "不平衡",
    LOW: "偏低",
    POOR: "较差",
  };
  if (!status) return "未评估";
  return labels[status.toUpperCase()] ?? status;
}

function statusTagType(
  status?: string | null,
): "success" | "warning" | "danger" | "info" {
  if (!status) return "info";
  const normalized = status.toUpperCase();
  if (normalized === "BALANCED") return "success";
  if (normalized === "UNBALANCED") return "warning";
  if (normalized === "LOW" || normalized === "POOR") return "danger";
  return "info";
}

function sleepHours(row: TrendRow): number | null {
  const seconds = row.sleep?.sleepTimeSeconds;
  return seconds == null ? null : Number((seconds / 3600).toFixed(2));
}

function baseChartOption(): EChartsCoreOption {
  return {
    animationDuration: 300,
    color: ["#2ca58d", "#315f9f", "#9a72b0"],
    tooltip: {
      trigger: "axis",
      backgroundColor: "rgba(21, 34, 53, 0.94)",
      borderWidth: 0,
      textStyle: { color: "#ffffff", fontSize: 11 },
    },
    legend: {
      top: 0,
      right: 0,
      itemWidth: 14,
      itemHeight: 7,
      textStyle: { color: "#718096", fontSize: 10 },
    },
    grid: { top: 46, right: 48, bottom: 30, left: 48 },
    xAxis: {
      type: "category",
      boundaryGap: true,
      data: trendRows.value.map((row) => row.label),
      axisLine: { lineStyle: { color: "#dbe3e8" } },
      axisTick: { show: false },
      axisLabel: { color: "#8a96a6", fontSize: 10 },
    },
  };
}

function renderCharts(): void {
  if (!hasAnyData.value) {
    disposeCharts();
    return;
  }

  if (!recoveryChart && recoveryChartElement.value) {
    recoveryChart = init(recoveryChartElement.value);
  }
  if (!heartRateChart && heartRateChartElement.value) {
    heartRateChart = init(heartRateChartElement.value);
  }
  if (!activityChart && activityChartElement.value) {
    activityChart = init(activityChartElement.value);
  }

  recoveryChart?.setOption(
    {
      ...baseChartOption(),
      yAxis: [
        {
          type: "value",
          name: "睡眠 h",
          min: 0,
          axisLabel: { color: "#8a96a6", fontSize: 10 },
          splitLine: { lineStyle: { color: "#edf1f3" } },
        },
        {
          type: "value",
          name: "HRV ms",
          scale: true,
          axisLabel: { color: "#8a96a6", fontSize: 10 },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: "睡眠时长",
          type: "bar",
          data: trendRows.value.map(sleepHours),
          barMaxWidth: 18,
          itemStyle: { color: "#79c9b6", borderRadius: [4, 4, 0, 0] },
        },
        {
          name: "夜间 HRV",
          type: "line",
          yAxisIndex: 1,
          data: trendRows.value.map((row) => row.hrv?.lastNightAvg ?? null),
          showSymbol: periodDays.value <= 30,
          symbolSize: 5,
          connectNulls: false,
          lineStyle: { width: 2.2, color: "#315f9f" },
          itemStyle: { color: "#315f9f" },
        },
        {
          name: "HRV 7日均值",
          type: "line",
          yAxisIndex: 1,
          data: trendRows.value.map((row) => row.hrv?.weeklyAvg ?? null),
          showSymbol: false,
          connectNulls: false,
          lineStyle: { width: 1.5, type: "dashed", color: "#9a72b0" },
          itemStyle: { color: "#9a72b0" },
        },
      ],
    },
    true,
  );

  heartRateChart?.setOption(
    {
      ...baseChartOption(),
      color: ["#2ca58d", "#7a92a8", "#d36b6b"],
      yAxis: {
        type: "value",
        name: "bpm",
        scale: true,
        axisLabel: { color: "#8a96a6", fontSize: 10 },
        splitLine: { lineStyle: { color: "#edf1f3" } },
      },
      series: [
        {
          name: "静息心率",
          type: "line",
          data: trendRows.value.map(
            (row) => row.daily?.restingHeartRate ?? null,
          ),
          showSymbol: periodDays.value <= 30,
          symbolSize: 5,
          lineStyle: { width: 2.2 },
        },
        {
          name: "最低心率",
          type: "line",
          data: trendRows.value.map((row) => row.daily?.minHeartRate ?? null),
          showSymbol: false,
          lineStyle: { width: 1.4 },
        },
        {
          name: "最高心率",
          type: "line",
          data: trendRows.value.map((row) => row.daily?.maxHeartRate ?? null),
          showSymbol: false,
          lineStyle: { width: 1.4 },
        },
      ],
    },
    true,
  );

  activityChart?.setOption(
    {
      ...baseChartOption(),
      color: ["#36a68c", "#e0a34b"],
      yAxis: [
        {
          type: "value",
          name: "步数",
          min: 0,
          axisLabel: { color: "#8a96a6", fontSize: 10 },
          splitLine: { lineStyle: { color: "#edf1f3" } },
        },
        {
          type: "value",
          name: "kcal",
          min: 0,
          axisLabel: { color: "#8a96a6", fontSize: 10 },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: "步数",
          type: "bar",
          data: trendRows.value.map((row) => row.daily?.steps ?? null),
          barMaxWidth: 18,
          itemStyle: { borderRadius: [4, 4, 0, 0] },
        },
        {
          name: "活动热量",
          type: "line",
          yAxisIndex: 1,
          data: trendRows.value.map(
            (row) => row.daily?.activeKilocalories ?? null,
          ),
          showSymbol: periodDays.value <= 30,
          symbolSize: 5,
          lineStyle: { width: 2 },
        },
      ],
    },
    true,
  );
}

function resizeCharts(): void {
  recoveryChart?.resize();
  heartRateChart?.resize();
  activityChart?.resize();
}

function disposeCharts(): void {
  recoveryChart?.dispose();
  heartRateChart?.dispose();
  activityChart?.dispose();
  recoveryChart = null;
  heartRateChart = null;
  activityChart = null;
}

async function loadTrends(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const query = buildQuery();
    const [daily, hrv, sleep] = await Promise.all([
      listDailyHealth(query),
      listHrv(query),
      listSleep(query),
    ]);
    dailyHealth.value = daily;
    hrvRecords.value = hrv;
    sleepRecords.value = sleep;
  } catch {
    loadFailed.value = true;
    dailyHealth.value = [];
    hrvRecords.value = [];
    sleepRecords.value = [];
  } finally {
    loading.value = false;
    await nextTick();
    renderCharts();
  }
}

watch(periodDays, () => {
  void loadTrends();
});

onMounted(() => {
  window.addEventListener("resize", resizeCharts);
  void loadTrends();
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeCharts);
  disposeCharts();
});
</script>

<template>
  <section class="page-container trends-page">
    <PageHeading
      eyebrow="恢复与表现"
      title="趋势"
      description="联合查看睡眠、HRV、心率与日常活动的变化。"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadTrends">刷新</el-button>
      </template>
    </PageHeading>

    <div class="trend-toolbar">
      <div class="trend-period-selector" aria-label="趋势时间范围">
        <button
          v-for="option in PERIOD_OPTIONS"
          :key="option.value"
          type="button"
          :class="{ active: periodDays === option.value }"
          @click="periodDays = option.value"
        >
          {{ option.label }}
        </button>
      </div>
      <div class="trend-coverage">
        <span></span>
        {{ coverageText }}
      </div>
    </div>

    <el-alert
      v-if="loadFailed"
      title="趋势数据加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="trend-load-error"
    />

    <div v-loading="loading" class="trend-content-shell">
      <div class="trend-summary-grid">
        <article class="trend-summary-card">
          <span>最新数据</span>
          <strong>{{ formatDate(latestDataDate) }}</strong>
          <small>{{ coverageText }}</small>
        </article>
        <article class="trend-summary-card sleep-summary">
          <span>睡眠时长 · 本期平均</span>
          <strong>{{ formatNumber(periodStats.sleepHours, " h", 1) }}</strong>
          <small>
            {{
              formatDelta(
                periodStats.sleepHours,
                previousStats.sleepHours,
                1,
                " h",
              )
            }}
            · 评分 {{ formatNumber(periodStats.sleepScore, "", 0) }} · 最新
            {{ formatHours(latestSleepRow?.sleep?.sleepTimeSeconds) }}
          </small>
        </article>
        <article class="trend-summary-card hrv-summary">
          <span>夜间 HRV · 本期平均</span>
          <strong>{{ formatNumber(periodStats.hrv, " ms", 0) }}</strong>
          <small>
            {{ formatDelta(periodStats.hrv, previousStats.hrv, 1, " ms") }}
            · 最新 {{ formatNumber(latestHrvRow?.hrv?.lastNightAvg, " ms") }}
            {{ formatStatus(latestHrvRow?.hrv?.hrvStatus) }}
          </small>
        </article>
        <article class="trend-summary-card heart-summary">
          <span>静息心率 · 本期平均</span>
          <strong>
            {{ formatNumber(periodStats.restingHeartRate, " bpm", 0) }}
          </strong>
          <small>
            {{
              formatDelta(
                periodStats.restingHeartRate,
                previousStats.restingHeartRate,
                1,
                " bpm",
              )
            }}
            · 最新
            {{
              formatNumber(latestHeartRateRow?.daily?.restingHeartRate, " bpm")
            }}
          </small>
        </article>
        <article class="trend-summary-card flag-summary">
          <span>恢复标记</span>
          <strong>{{ flaggedRows.length }} 天</strong>
          <small>
            {{
              flaggedRows.length === 0
                ? "本期未触发提示"
                : "按训练方案规则自动判定"
            }}
          </small>
        </article>
      </div>

      <el-empty
        v-if="!loading && !loadFailed && !hasAnyData"
        description="所选时间内还没有同步的健康数据"
        class="trend-empty"
      />

      <template v-if="hasAnyData">
        <div class="trend-chart-grid">
          <article class="trend-chart-card recovery-chart-card">
            <header>
              <div>
                <h2>恢复趋势</h2>
                <p>睡眠时长、夜间 HRV 与 7 日 HRV 均值</p>
              </div>
            </header>
            <div ref="recoveryChartElement" class="trend-chart"></div>
          </article>

          <article class="trend-chart-card">
            <header>
              <div>
                <h2>心率趋势</h2>
                <p>静息、最低与最高心率</p>
              </div>
            </header>
            <div ref="heartRateChartElement" class="trend-chart"></div>
          </article>

          <article class="trend-chart-card">
            <header>
              <div>
                <h2>日常活动</h2>
                <p>步数与活动热量</p>
              </div>
            </header>
            <div ref="activityChartElement" class="trend-chart"></div>
          </article>
        </div>

        <article class="trend-table-card">
          <header>
            <div>
              <h2>每日明细</h2>
              <p>空白表示该指标当日未同步，不会按 0 处理。</p>
            </div>
          </header>
          <el-table
            :data="availableRows"
            max-height="480"
            class="trend-table trend-table-clickable"
            @row-click="openDayDetail"
          >
            <el-table-column label="日期" min-width="110">
              <template #default="scope">
                {{ formatDate(scope.row.calendarDate) }}
              </template>
            </el-table-column>
            <el-table-column label="恢复" min-width="96">
              <template #default="{ row }">
                <el-tag
                  v-if="flagLevel(row.calendarDate)"
                  :type="
                    flagLevel(row.calendarDate)?.level === 'red'
                      ? 'danger'
                      : 'warning'
                  "
                  size="small"
                >
                  {{ flagLevelText[flagLevel(row.calendarDate)!.level] }}
                </el-tag>
                <span v-else class="trend-flag-none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="睡眠" min-width="90">
              <template #default="scope">
                {{ formatHours(scope.row.sleep?.sleepTimeSeconds) }}
              </template>
            </el-table-column>
            <el-table-column label="睡眠评分" min-width="90">
              <template #default="scope">
                {{ formatNumber(scope.row.sleep?.sleepScore) }}
              </template>
            </el-table-column>
            <el-table-column label="HRV" min-width="130">
              <template #default="scope">
                <div class="trend-table-hrv">
                  <span>{{
                    formatNumber(scope.row.hrv?.lastNightAvg, " ms")
                  }}</span>
                  <el-tag
                    v-if="scope.row.hrv?.hrvStatus"
                    :type="statusTagType(scope.row.hrv.hrvStatus)"
                    effect="plain"
                    size="small"
                  >
                    {{ formatStatus(scope.row.hrv.hrvStatus) }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="静息心率" min-width="90">
              <template #default="scope">
                {{ formatNumber(scope.row.daily?.restingHeartRate, " bpm") }}
              </template>
            </el-table-column>
            <el-table-column label="身体电量" min-width="100">
              <template #default="scope">
                {{ formatNumber(scope.row.daily?.bodyBatteryLowest) }}–{{
                  formatNumber(scope.row.daily?.bodyBatteryHighest)
                }}
              </template>
            </el-table-column>
            <el-table-column label="步数" min-width="90">
              <template #default="scope">
                {{ formatNumber(scope.row.daily?.steps) }}
              </template>
            </el-table-column>
            <el-table-column label="活动热量" min-width="100">
              <template #default="scope">
                {{ formatNumber(scope.row.daily?.activeKilocalories, " kcal") }}
              </template>
            </el-table-column>
          </el-table>
        </article>
      </template>
    </div>
    <el-drawer
      v-model="dayDetailVisible"
      :title="
        selectedRow
          ? '单日明细 · ' + formatDate(selectedRow.calendarDate)
          : '单日明细'
      "
      size="440px"
    >
      <template v-if="selectedRow">
        <el-alert
          v-if="selectedFlag"
          :title="'恢复标记：' + flagLevelText[selectedFlag.level]"
          :description="selectedFlag.reasons.join('；')"
          :type="selectedFlag.level === 'red' ? 'error' : 'warning'"
          :closable="false"
          show-icon
          class="day-detail-flag"
        />

        <h3 class="day-detail-title">每日健康</h3>
        <dl class="day-detail-list">
          <div>
            <dt>步数</dt>
            <dd>{{ formatNumber(selectedRow.daily?.steps) }}</dd>
          </div>
          <div>
            <dt>距离</dt>
            <dd>{{ formatDistanceValue(selectedRow.daily?.distanceMeters) }}</dd>
          </div>
          <div>
            <dt>静息心率</dt>
            <dd>
              {{ formatNumber(selectedRow.daily?.restingHeartRate, " bpm") }}
            </dd>
          </div>
          <div>
            <dt>心率范围</dt>
            <dd>
              {{ formatNumber(selectedRow.daily?.minHeartRate) }}–{{
                formatNumber(selectedRow.daily?.maxHeartRate)
              }}
              bpm
            </dd>
          </div>
          <div>
            <dt>总热量</dt>
            <dd>
              {{ formatNumber(selectedRow.daily?.totalKilocalories, " kcal") }}
            </dd>
          </div>
          <div>
            <dt>活动热量</dt>
            <dd>
              {{ formatNumber(selectedRow.daily?.activeKilocalories, " kcal") }}
            </dd>
          </div>
          <div>
            <dt>平均压力</dt>
            <dd>{{ formatNumber(selectedRow.daily?.averageStressLevel) }}</dd>
          </div>
          <div>
            <dt>身体电量</dt>
            <dd>
              {{ formatNumber(selectedRow.daily?.bodyBatteryLowest) }}–{{
                formatNumber(selectedRow.daily?.bodyBatteryHighest)
              }}
            </dd>
          </div>
        </dl>

        <h3 class="day-detail-title">HRV</h3>
        <dl class="day-detail-list">
          <div>
            <dt>7 日均值</dt>
            <dd>{{ formatNumber(selectedRow.hrv?.weeklyAvg, " ms") }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{{ formatStatus(selectedRow.hrv?.hrvStatus) }}</dd>
          </div>
          <div>
            <dt>个人基线</dt>
            <dd>
              {{ formatNumber(selectedRow.hrv?.baselineBalancedLow) }}–{{
                formatNumber(selectedRow.hrv?.baselineBalancedUpper)
              }}
              ms
            </dd>
          </div>
        </dl>

        <h3 class="day-detail-title">睡眠</h3>
        <dl class="day-detail-list">
          <div>
            <dt>总时长</dt>
            <dd>{{ formatHours(selectedRow.sleep?.sleepTimeSeconds) }}</dd>
          </div>
          <div>
            <dt>评分</dt>
            <dd>{{ formatNumber(selectedRow.sleep?.sleepScore) }}</dd>
          </div>
          <div>
            <dt>深睡</dt>
            <dd>{{ formatHours(selectedRow.sleep?.deepSleepSeconds) }}</dd>
          </div>
          <div>
            <dt>浅睡</dt>
            <dd>{{ formatHours(selectedRow.sleep?.lightSleepSeconds) }}</dd>
          </div>
          <div>
            <dt>REM</dt>
            <dd>{{ formatHours(selectedRow.sleep?.remSleepSeconds) }}</dd>
          </div>
          <div>
            <dt>清醒</dt>
            <dd>{{ formatHours(selectedRow.sleep?.awakeSleepSeconds) }}</dd>
          </div>
          <div>
            <!-- 只保留这一行：睡眠接口的 avgOvernightHrv 与 HRV 接口的 lastNightAvg
                 是同一个测量值（实测 20 天逐日一致），两处都显示会让人以为是两个指标。
                 前者缺失时退回后者，避免无谓地显示「--」。 -->
            <dt>睡眠 HRV</dt>
            <dd>
              {{
                formatNumber(
                  selectedRow.sleep?.avgSleepHrv ?? selectedRow.hrv?.lastNightAvg,
                  " ms",
                )
              }}
            </dd>
          </div>
          <div>
            <dt>血氧</dt>
            <dd>{{ formatNumber(selectedRow.sleep?.avgSpo2, " %") }}</dd>
          </div>
          <div>
            <dt>呼吸</dt>
            <dd>
              {{ formatNumber(selectedRow.sleep?.avgRespiration, " 次/分") }}
            </dd>
          </div>
        </dl>
      </template>
    </el-drawer>
  </section>
</template>
