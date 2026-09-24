<script setup lang="ts">
import { LineChart } from "echarts/charts";
import { GridComponent, MarkPointComponent, TooltipComponent } from "echarts/components";
import { init, use, type ECharts, type EChartsCoreOption } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { FtpRecord } from "../types/api";

use([LineChart, GridComponent, MarkPointComponent, TooltipComponent, CanvasRenderer]);

const props = defineProps<{ rows: FtpRecord[] }>();

const chartRef = ref<HTMLDivElement | null>(null);
let chart: ECharts | null = null;

const SOURCE_LABEL: Record<string, string> = {
  GARMIN: "Garmin 接口测得",
  DERIVED: "由 NP/IF 反解",
  MANUAL: "手工录入",
};

/**
 * FTP 是「一直沿用到下次变更」的值，所以画成阶跃线（step）而不是折线：
 * 折线会暗示中间那些日期存在过渡值，实际并没有。
 *
 * 最后一个点会延长到今天 —— 它仍然生效；但不会伪造新的变更点。
 */
function buildOption(): EChartsCoreOption {
  const rows = [...props.rows].sort((a, b) =>
    a.effectiveDate.localeCompare(b.effectiveDate),
  );
  const today = new Date().toISOString().slice(0, 10);
  const points: Array<[string, number]> = rows.map((row) => [
    row.effectiveDate.slice(0, 10),
    row.ftpWatts,
  ]);
  const last = rows[rows.length - 1];
  if (last && last.effectiveDate.slice(0, 10) < today) {
    points.push([today, last.ftpWatts]);
  }

  const byDate = new Map(rows.map((row) => [row.effectiveDate.slice(0, 10), row]));

  return {
    grid: { left: 44, right: 18, top: 22, bottom: 28 },
    tooltip: {
      trigger: "axis",
      formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params];
        const first = list[0] as { value?: [string, number] } | undefined;
        const value = first?.value;
        if (!value) return "";
        const row = byDate.get(value[0]);
        const source = row ? SOURCE_LABEL[row.source] ?? row.source : "沿用上一个值";
        return `${value[0]}<br/><strong>${value[1]} W</strong><br/>${source}`;
      },
    },
    xAxis: {
      type: "time",
      axisLine: { lineStyle: { color: "#dfe4ea" } },
      axisLabel: { color: "#8792a1", fontSize: 11 },
    },
    yAxis: {
      type: "value",
      name: "W",
      nameTextStyle: { color: "#8792a1", fontSize: 11 },
      scale: true,
      splitLine: { lineStyle: { color: "#f0f2f5" } },
      axisLabel: { color: "#8792a1", fontSize: 11 },
    },
    series: [
      {
        name: "FTP",
        type: "line",
        step: "end",
        symbolSize: 7,
        showSymbol: true,
        // 变更点用圆点；只有一次记录时也能看出那一点
        lineStyle: { width: 2, color: "#3b82f6" },
        itemStyle: { color: "#3b82f6" },
        areaStyle: { color: "rgba(59,130,246,0.08)" },
        data: points,
      },
    ],
  };
}

function render(): void {
  if (!chartRef.value) return;
  if (!chart) {
    chart = init(chartRef.value);
    window.addEventListener("resize", resize);
  }
  chart.setOption(buildOption(), true);
  chart.resize();
}

function resize(): void {
  chart?.resize();
}

onMounted(render);
watch(() => props.rows, render, { deep: true });

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div ref="chartRef" class="ftp-chart" />
</template>
