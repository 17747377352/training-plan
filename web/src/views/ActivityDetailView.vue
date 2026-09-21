<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getActivity, listActivityHrZones } from "../api/activities";
import PageHeading from "../components/PageHeading.vue";
import type { ActivityDetail, ActivityHrZone } from "../types/api";

interface DetailField {
  key: keyof ActivityDetail;
  label: string;
  value: string;
}

interface DetailSection {
  title: string;
  description: string;
  fields: DetailField[];
  priority?: boolean;
}

const TYPE_LABELS: Record<string, string> = {
  road_biking: "公路骑行",
  cycling: "骑行",
  indoor_cycling: "室内骑行",
  mountain_biking: "山地骑行",
  gravel_cycling: "碎石路骑行",
  cyclocross: "公路越野",
  e_bike_fitness: "电助力骑行",
};

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const loadFailed = ref(false);
const activity = ref<ActivityDetail | null>(null);

const headingDescription = computed(() => {
  if (!activity.value) return "查看活动的完整采集字段。";
  const time = formatDateTime(activity.value.startTimeLocal);
  return `${time} · ${activityTypeLabel(activity.value.activityTypeKey)}`;
});

const summaryItems = computed(() => {
  const value = activity.value;
  if (!value) return [];
  return [
    { label: "距离", value: formatDistance(value.distanceMeters) },
    {
      label: "移动时间",
      value: formatDuration(
        value.movingDurationSeconds ?? value.durationSeconds,
      ),
    },
    { label: "累计爬升", value: formatNumber(value.elevationGain, " m") },
    {
      label: "AP / NP",
      value: `${formatNumber(value.avgPower)} / ${formatNumber(value.normPower, " W")}`,
    },
    {
      label: "TSS / IF",
      value: `${formatNumber(value.trainingStressScore, "", 1)} / ${formatNumber(value.intensityFactor, "", 2)}`,
    },
    {
      label: "平均心率",
      value: formatNumber(value.averageHr, " bpm"),
    },
  ];
});

const powerZones = computed(() => {
  const value = activity.value;
  if (!value) return [];
  return [
    {
      key: "powerZone1Seconds" as const,
      label: "Z1",
      seconds: value.powerZone1Seconds,
    },
    {
      key: "powerZone2Seconds" as const,
      label: "Z2",
      seconds: value.powerZone2Seconds,
    },
    {
      key: "powerZone3Seconds" as const,
      label: "Z3",
      seconds: value.powerZone3Seconds,
    },
    {
      key: "powerZone4Seconds" as const,
      label: "Z4",
      seconds: value.powerZone4Seconds,
    },
    {
      key: "powerZone5Seconds" as const,
      label: "Z5",
      seconds: value.powerZone5Seconds,
    },
    {
      key: "powerZone6Seconds" as const,
      label: "Z6",
      seconds: value.powerZone6Seconds,
    },
    {
      key: "powerZone7Seconds" as const,
      label: "Z7",
      seconds: value.powerZone7Seconds,
    },
  ];
});

const powerZoneTotal = computed(() =>
  powerZones.value.reduce((total, zone) => total + (zone.seconds ?? 0), 0),
);

/**
 * 心率区间来自单独的活动接口（功率区间是活动列表里白拿的，心率不是）。
 * 取不到就整体不显示，而不是留一块空表。
 */
const hrZones = ref<ActivityHrZone[]>([]);

const hrZoneTotal = computed(() =>
  hrZones.value.reduce((total, zone) => total + (zone.secondsInZone ?? 0), 0),
);

function hrZoneWidth(seconds?: number | null): string {
  if (!seconds || hrZoneTotal.value <= 0) return "0%";
  return `${Math.max(2, (seconds / hrZoneTotal.value) * 100)}%`;
}

function hrZoneShare(seconds?: number | null): string {
  if (!seconds || hrZoneTotal.value <= 0) return "--";
  return `${((seconds / hrZoneTotal.value) * 100).toFixed(1)}%`;
}

const detailSections = computed<DetailSection[]>(() => {
  const value = activity.value;
  if (!value) return [];

  const field = (
    key: keyof ActivityDetail,
    label: string,
    formatted: string,
  ): DetailField => ({ key, label, value: formatted });

  return [
    {
      title: "时间",
      description: "本地开始时间与三种时长口径。",
      fields: [
        field(
          "startTimeLocal",
          "本地开始时间",
          formatDateTime(value.startTimeLocal),
        ),
        field(
          "durationSeconds",
          "计时时长",
          formatDuration(value.durationSeconds),
        ),
        field(
          "movingDurationSeconds",
          "移动时长",
          formatDuration(value.movingDurationSeconds),
        ),
        field(
          "elapsedDurationSeconds",
          "总耗时",
          formatDuration(value.elapsedDurationSeconds),
        ),
      ],
    },
    {
      title: "距离、海拔与速度",
      description: "路程、爬升、海拔范围与速度。",
      fields: [
        field(
          "distanceMeters",
          "距离",
          formatDistanceWithMeters(value.distanceMeters),
        ),
        field(
          "elevationGain",
          "累计爬升",
          formatNumber(value.elevationGain, " m", 1),
        ),
        field(
          "elevationLoss",
          "累计下降",
          formatNumber(value.elevationLoss, " m", 1),
        ),
        field(
          "avgElevation",
          "平均海拔",
          formatNumber(value.avgElevation, " m", 1),
        ),
        field(
          "maxElevation",
          "最高海拔",
          formatNumber(value.maxElevation, " m", 1),
        ),
        field(
          "minElevation",
          "最低海拔",
          formatNumber(value.minElevation, " m", 1),
        ),
        field("averageSpeed", "平均速度", formatSpeed(value.averageSpeed)),
        field("maxSpeed", "最大速度", formatSpeed(value.maxSpeed)),
        field("lapCount", "圈数", formatNumber(value.lapCount)),
      ],
    },
    {
      title: "心率、能量与环境",
      description: "生理强度、能量消耗和环境记录。",
      fields: [
        field("averageHr", "平均心率", formatNumber(value.averageHr, " bpm")),
        field("maxHr", "最大心率", formatNumber(value.maxHr, " bpm")),
        field("calories", "活动热量", formatNumber(value.calories, " kcal", 0)),
        field(
          "bmrCalories",
          "基础代谢热量",
          formatNumber(value.bmrCalories, " kcal", 0),
        ),
        field(
          "avgRespirationRate",
          "平均呼吸频率",
          formatNumber(value.avgRespirationRate, " 次/分", 1),
        ),
        field(
          "minTemperature",
          "最低温度",
          formatNumber(value.minTemperature, " °C", 1),
        ),
        field(
          "maxTemperature",
          "最高温度",
          formatNumber(value.maxTemperature, " °C", 1),
        ),
        field(
          "vo2maxValue",
          "VO₂max 估算",
          formatNumber(value.vo2maxValue, "", 1),
        ),
      ],
    },
    {
      title: "功率与骑行效率",
      description: "功率输出、强度、踏频与左右平衡。",
      priority: true,
      fields: [
        field("avgPower", "平均功率 AP", formatNumber(value.avgPower, " W", 0)),
        field("maxPower", "最大功率", formatNumber(value.maxPower, " W", 0)),
        field(
          "normPower",
          "标准化功率 NP",
          formatNumber(value.normPower, " W", 0),
        ),
        field(
          "max20minPower",
          "20 分钟最大平均功率",
          formatNumber(value.max20minPower, " W", 0),
        ),
        field(
          "intensityFactor",
          "强度因子 IF",
          formatNumber(value.intensityFactor, "", 2),
        ),
        field(
          "trainingStressScore",
          "训练压力分数 TSS",
          formatNumber(value.trainingStressScore, "", 1),
        ),
        field(
          "avgCadence",
          "平均踏频",
          formatNumber(value.avgCadence, " rpm", 0),
        ),
        field(
          "maxCadence",
          "最大踏频",
          formatNumber(value.maxCadence, " rpm", 0),
        ),
        field(
          "avgLeftBalance",
          "左侧发力占比",
          formatNumber(value.avgLeftBalance, "%", 1),
        ),
        field("strokes", "总踩踏圈数", formatNumber(value.strokes, "", 0)),
      ],
    },
    {
      title: "训练效果",
      description: "Garmin 对这次活动的训练刺激与负荷评估。",
      fields: [
        field(
          "aerobicTrainingEffect",
          "有氧训练效果",
          formatNumber(value.aerobicTrainingEffect, "", 1),
        ),
        field(
          "anaerobicTrainingEffect",
          "无氧训练效果",
          formatNumber(value.anaerobicTrainingEffect, "", 1),
        ),
        field(
          "trainingEffectLabel",
          "训练效果标签",
          formatText(value.trainingEffectLabel),
        ),
        field(
          "activityTrainingLoad",
          "活动训练负荷",
          formatNumber(value.activityTrainingLoad, "", 1),
        ),
      ],
    },
    {
      title: "记录信息",
      description: "设备标识与平台入库时间。",
      fields: [
        field("deviceId", "记录设备 ID", formatId(value.deviceId)),
        field("createTime", "创建时间", formatDateTime(value.createTime)),
        field("updateTime", "更新时间", formatDateTime(value.updateTime)),
      ],
    },
  ];
});

const prioritySections = computed(() =>
  detailSections.value.filter((section) => section.priority),
);

const regularSections = computed(() =>
  detailSections.value.filter((section) => !section.priority),
);

function activityTypeLabel(typeKey?: string | null): string {
  if (!typeKey) return "骑行";
  return (
    TYPE_LABELS[typeKey] ||
    typeKey
      .split("_")
      .filter(Boolean)
      .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
      .join(" ")
  );
}

function formatText(value?: string | null): string {
  return value?.trim() || "--";
}

function formatId(value?: number | string | null): string {
  return value == null ? "--" : String(value);
}

function formatNumber(value?: number | null, suffix = "", digits = 0): string {
  if (value == null || !Number.isFinite(value)) return "--";
  return `${value.toFixed(digits)}${suffix}`;
}

function formatDuration(seconds?: number | null): string {
  if (seconds == null || !Number.isFinite(seconds)) return "--";
  const rounded = Math.max(0, Math.round(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  if (hours > 0) return `${hours}小时${minutes}分`;
  if (minutes > 0) return `${minutes}分钟`;
  return "<1分钟";
}

function formatDistance(meters?: number | null): string {
  if (meters == null || !Number.isFinite(meters)) return "--";
  return `${(meters / 1000).toFixed(1)} km`;
}

function formatDistanceWithMeters(meters?: number | null): string {
  if (meters == null || !Number.isFinite(meters)) return "--";
  return `${formatDistance(meters)} · ${meters.toFixed(1)} m`;
}

function formatSpeed(metersPerSecond?: number | null): string {
  if (metersPerSecond == null || !Number.isFinite(metersPerSecond)) return "--";
  return `${(metersPerSecond * 3.6).toFixed(1)} km/h · ${metersPerSecond.toFixed(2)} m/s`;
}

function formatDateTime(value?: string | null): string {
  if (!value) return "--";
  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/,
  );
  if (!match) return value;
  return `${match[1]}/${match[2]}/${match[3]} ${match[4]}:${match[5]}:${match[6] ?? "00"}`;
}

function zoneWidth(seconds?: number | null): string {
  if (!seconds || powerZoneTotal.value <= 0) return "0%";
  return `${Math.max(2, (seconds / powerZoneTotal.value) * 100)}%`;
}

async function loadActivity(): Promise<void> {
  const activityId = Number(route.params.id);
  if (!Number.isSafeInteger(activityId) || activityId <= 0) {
    loadFailed.value = true;
    activity.value = null;
    return;
  }

  loading.value = true;
  loadFailed.value = false;
  try {
    activity.value = await getActivity(activityId);
  } catch {
    loadFailed.value = true;
    activity.value = null;
  } finally {
    loading.value = false;
  }

  // 心率区间是附加信息，取不到不影响活动详情本身
  try {
    hrZones.value = await listActivityHrZones(activityId);
  } catch {
    hrZones.value = [];
  }
}

function goBack(): void {
  void router.push({ name: "activities" });
}

watch(() => route.params.id, loadActivity, { immediate: true });
</script>

<template>
  <section class="page-container activity-detail-page">
    <PageHeading
      eyebrow="活动详情"
      title="活动详情"
      :description="headingDescription"
    >
      <template #actions>
        <el-button @click="goBack">← 返回活动列表</el-button>
      </template>
    </PageHeading>

    <el-alert
      v-if="loadFailed"
      title="无法读取这条活动"
      description="活动可能不存在，或不属于当前账号。"
      type="error"
      :closable="false"
      show-icon
      class="activity-load-error"
    />

    <div v-loading="loading" class="activity-detail-shell">
      <template v-if="activity">
        <section class="activity-detail-hero">
          <div class="detail-hero-heading">
            <div>
              <span>关键摘要</span>
              <h2>
                {{ activity.activityName?.trim() || "未命名活动" }}
              </h2>
              <p>{{ activityTypeLabel(activity.activityTypeKey) }}</p>
            </div>
            <el-tag
              v-if="activity.trainingEffectLabel"
              type="success"
              effect="plain"
            >
              {{ activity.trainingEffectLabel }}
            </el-tag>
          </div>
          <div class="detail-summary-grid">
            <div
              v-for="item in summaryItems"
              :key="item.label"
              class="detail-summary-item"
            >
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
        </section>

        <div class="priority-detail-sections">
          <section
            v-for="section in prioritySections"
            :key="section.title"
            class="activity-detail-card"
          >
            <header class="detail-card-heading">
              <div>
                <h2>{{ section.title }}</h2>
                <p>{{ section.description }}</p>
              </div>
            </header>
            <dl class="detail-field-grid">
              <div
                v-for="field in section.fields"
                :key="field.key"
                class="detail-field"
              >
                <dt>
                  <span>{{ field.label }}</span>
                  <code>{{ field.key }}</code>
                </dt>
                <dd>{{ field.value }}</dd>
              </div>
            </dl>
          </section>
        </div>

        <section class="activity-detail-card power-zone-card">
          <header class="detail-card-heading">
            <div>
              <h2>功率区间</h2>
              <p>区间时长与本次有记录区间的占比。</p>
            </div>
            <strong>{{ formatDuration(powerZoneTotal) }}</strong>
          </header>
          <div class="power-zone-list">
            <div
              v-for="(zone, index) in powerZones"
              :key="zone.key"
              class="power-zone-row"
            >
              <span class="power-zone-label">{{ zone.label }}</span>
              <div class="power-zone-track">
                <span
                  class="power-zone-fill"
                  :class="`zone-${index + 1}`"
                  :style="{ width: zoneWidth(zone.seconds) }"
                ></span>
              </div>
              <strong>{{ formatDuration(zone.seconds) }}</strong>
              <code>{{ zone.key }}</code>
            </div>
          </div>
        </section>

        <section v-if="hrZones.length > 0" class="activity-detail-card power-zone-card">
          <header class="detail-card-heading">
            <div>
              <h2>心率区间</h2>
              <p>各区间停留时长与占比，与功率区间互为印证。</p>
            </div>
            <strong>{{ formatDuration(hrZoneTotal) }}</strong>
          </header>
          <div class="power-zone-list">
            <div
              v-for="zone in hrZones"
              :key="zone.zoneNumber"
              class="power-zone-row"
            >
              <span class="power-zone-label">H{{ zone.zoneNumber }}</span>
              <div class="power-zone-track">
                <span
                  class="power-zone-fill"
                  :class="`zone-${zone.zoneNumber}`"
                  :style="{ width: hrZoneWidth(zone.secondsInZone) }"
                ></span>
              </div>
              <strong>{{ formatDuration(zone.secondsInZone) }}</strong>
              <code>≥{{ zone.zoneLowBoundary ?? "--" }} bpm · {{ hrZoneShare(zone.secondsInZone) }}</code>
            </div>
          </div>
        </section>

        <div class="activity-detail-sections">
          <section
            v-for="section in regularSections"
            :key="section.title"
            class="activity-detail-card"
          >
            <header class="detail-card-heading">
              <div>
                <h2>{{ section.title }}</h2>
                <p>{{ section.description }}</p>
              </div>
            </header>
            <dl class="detail-field-grid">
              <div
                v-for="field in section.fields"
                :key="field.key"
                class="detail-field"
              >
                <dt>
                  <span>{{ field.label }}</span>
                  <code>{{ field.key }}</code>
                </dt>
                <dd>{{ field.value }}</dd>
              </div>
            </dl>
          </section>
        </div>
      </template>
    </div>
  </section>
</template>
