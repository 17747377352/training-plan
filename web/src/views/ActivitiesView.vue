<script setup lang="ts">
import { ElDatePicker, ElPagination } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import {
  listActivities as fetchActivities,
  listActivityTypes,
} from "../api/activities";
import PageHeading from "../components/PageHeading.vue";
import type { ActivityQuery, ActivitySummary } from "../types/api";

const TYPE_LABELS: Record<string, string> = {
  road_biking: "公路骑行",
  cycling: "骑行",
  indoor_cycling: "室内骑行",
  mountain_biking: "山地骑行",
  gravel_cycling: "碎石路骑行",
  cyclocross: "公路越野",
  e_bike_fitness: "电助力骑行",
};

const loading = ref(false);
const loadFailed = ref(false);
const activities = ref<ActivitySummary[]>([]);
const activityTypes = ref<string[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(10);
const filters = reactive<{
  keyword: string;
  typeKey: string;
  dateRange: [string, string] | null;
}>({
  keyword: "",
  typeKey: "",
  dateRange: null,
});

const resultText = computed(() => {
  if (loading.value) return "正在读取活动";
  return `共 ${total.value} 条活动`;
});

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

function buildQuery(): ActivityQuery {
  const query: ActivityQuery = {
    page: page.value,
    size: pageSize.value,
  };
  const keyword = filters.keyword.trim();
  if (keyword) query.keyword = keyword;
  if (filters.typeKey) query.typeKey = filters.typeKey;
  if (filters.dateRange) {
    query.startDate = filters.dateRange[0];
    query.endDate = filters.dateRange[1];
  }
  return query;
}

async function loadActivities(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const result = await fetchActivities(buildQuery());
    activities.value = result.records;
    total.value = result.total;
    page.value = result.page;
    pageSize.value = result.size;
  } catch {
    loadFailed.value = true;
    activities.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

async function loadTypes(): Promise<void> {
  try {
    activityTypes.value = await listActivityTypes();
  } catch {
    activityTypes.value = [];
  }
}

function search(): void {
  page.value = 1;
  void loadActivities();
}

function resetFilters(): void {
  filters.keyword = "";
  filters.typeKey = "";
  filters.dateRange = null;
  page.value = 1;
  void loadActivities();
}

function changePage(nextPage: number): void {
  page.value = nextPage;
  void loadActivities();
}

function changePageSize(nextSize: number): void {
  pageSize.value = nextSize;
  page.value = 1;
  void loadActivities();
}

function formatDate(value?: string | null): string {
  if (!value) return "时间未知";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function formatDuration(seconds?: number | null): string {
  if (seconds == null) return "--";
  const rounded = Math.max(0, Math.round(seconds));
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  if (hours > 0) return `${hours}小时${minutes}分`;
  const remainingSeconds = rounded % 60;
  return `${minutes}分${remainingSeconds.toString().padStart(2, "0")}秒`;
}

function formatNumber(value?: number | null, suffix = "", digits = 0): string {
  if (value == null || !Number.isFinite(value)) return "--";
  return `${value.toFixed(digits)}${suffix}`;
}

function formatDistance(meters?: number | null): string {
  if (meters == null) return "--";
  return `${(meters / 1000).toFixed(1)} km`;
}

function formatSpeed(metersPerSecond?: number | null): string {
  if (metersPerSecond == null) return "--";
  return `${(metersPerSecond * 3.6).toFixed(1)} km/h`;
}

function displayDuration(activity: ActivitySummary): string {
  return formatDuration(
    activity.movingDurationSeconds ?? activity.durationSeconds,
  );
}

function displayTitle(activity: ActivitySummary): string {
  return (
    activity.activityName?.trim() || activityTypeLabel(activity.activityTypeKey)
  );
}

onMounted(() => {
  void Promise.all([loadTypes(), loadActivities()]);
});
</script>

<template>
  <section class="page-container activities-page">
    <PageHeading
      eyebrow="训练记录"
      title="活动"
      description="按日期和类型浏览已同步的骑行，快速核对功率、心率与训练负荷。"
    >
      <template #actions>
        <el-button :loading="loading" @click="loadActivities">刷新</el-button>
      </template>
    </PageHeading>

    <el-card class="activity-filter-card" shadow="never">
      <div class="activity-filters">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="搜索活动名称"
          class="activity-keyword"
          @keyup.enter="search"
        />
        <el-select
          v-model="filters.typeKey"
          clearable
          placeholder="全部类型"
          class="activity-type-select"
        >
          <el-option
            v-for="typeKey in activityTypes"
            :key="typeKey"
            :label="activityTypeLabel(typeKey)"
            :value="typeKey"
          />
        </el-select>
        <ElDatePicker
          v-model="filters.dateRange"
          type="daterange"
          unlink-panels
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          range-separator="至"
          class="activity-date-range"
        />
        <div class="filter-actions">
          <el-button type="primary" :loading="loading" @click="search"
            >查询</el-button
          >
          <el-button :disabled="loading" @click="resetFilters">重置</el-button>
        </div>
      </div>
    </el-card>

    <div class="activity-result-bar">
      <div>
        <strong>活动列表</strong>
        <span>{{ resultText }}</span>
      </div>
      <span class="result-order">按开始时间倒序</span>
    </div>

    <el-alert
      v-if="loadFailed"
      title="活动加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="activity-load-error"
    />

    <div v-loading="loading" class="activity-list-shell">
      <el-empty
        v-if="!loading && !loadFailed && activities.length === 0"
        description="没有找到符合条件的活动"
      >
        <el-button
          v-if="filters.keyword || filters.typeKey || filters.dateRange"
          @click="resetFilters"
        >
          清除筛选
        </el-button>
      </el-empty>

      <div v-else class="activity-list">
        <router-link
          v-for="activity in activities"
          :key="activity.id"
          :to="{ name: 'activity-detail', params: { id: activity.id } }"
          class="activity-record-link"
        >
          <article class="activity-record">
            <header class="activity-record-header">
              <div class="activity-identity">
                <span class="activity-bike-icon">
                  <svg
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    aria-hidden="true"
                  >
                    <circle cx="6" cy="17.5" r="3" />
                    <circle cx="18" cy="17.5" r="3" />
                    <path d="m6 17.5 3.2-6.2h4.3l4.5 6.2" />
                    <path d="m9.2 11.3 3.4 6.2 3-9.2" />
                    <path d="M13.6 8.3h3M8.6 8.3h2" />
                  </svg>
                </span>
                <div>
                  <h2>{{ displayTitle(activity) }}</h2>
                  <p>
                    {{ formatDate(activity.startTime) }}
                    <span></span>
                    {{ activityTypeLabel(activity.activityTypeKey) }}
                  </p>
                </div>
              </div>
              <div class="activity-primary-stats">
                <div>
                  <strong>{{ formatDistance(activity.distanceMeters) }}</strong>
                  <span>距离</span>
                </div>
                <div>
                  <strong>{{ displayDuration(activity) }}</strong>
                  <span>移动时间</span>
                </div>
              </div>
            </header>

            <div class="activity-metrics">
              <div class="activity-metric">
                <span>累计爬升</span>
                <strong>{{
                  formatNumber(activity.elevationGain, " m")
                }}</strong>
              </div>
              <div class="activity-metric">
                <span>平均速度</span>
                <strong>{{ formatSpeed(activity.averageSpeed) }}</strong>
              </div>
              <div class="activity-metric">
                <span>心率 平均 / 最大</span>
                <strong>
                  {{ formatNumber(activity.averageHr) }} /
                  {{ formatNumber(activity.maxHr, " bpm") }}
                </strong>
              </div>
              <div class="activity-metric power-metric">
                <span>功率 AP / NP</span>
                <strong>
                  {{ formatNumber(activity.avgPower) }} /
                  {{ formatNumber(activity.normPower, " W") }}
                </strong>
              </div>
              <div class="activity-metric">
                <span>TSS / IF</span>
                <strong>
                  {{ formatNumber(activity.trainingStressScore) }} /
                  {{ formatNumber(activity.intensityFactor, "", 2) }}
                </strong>
              </div>
              <div class="activity-metric">
                <span>平均踏频</span>
                <strong>{{ formatNumber(activity.avgCadence, " rpm") }}</strong>
              </div>
            </div>

            <footer class="activity-record-footer">
              <div class="activity-tags">
                <el-tag
                  v-if="activity.trainingEffectLabel"
                  type="success"
                  effect="plain"
                  size="small"
                >
                  {{ activity.trainingEffectLabel }}
                </el-tag>
                <el-tag
                  v-if="activity.max20minPower != null"
                  type="info"
                  effect="plain"
                  size="small"
                >
                  20min {{ formatNumber(activity.max20minPower, " W") }}
                </el-tag>
                <el-tag
                  v-if="activity.vo2maxValue != null"
                  type="info"
                  effect="plain"
                  size="small"
                >
                  VO₂max {{ formatNumber(activity.vo2maxValue, "", 1) }}
                </el-tag>
              </div>
              <div class="activity-record-meta">
                <span
                  v-if="activity.calories != null"
                  class="activity-calories"
                >
                  {{ formatNumber(activity.calories, " kcal") }}
                </span>
                <span class="activity-detail-link">查看详情 →</span>
              </div>
            </footer>
          </article>
        </router-link>
      </div>
    </div>

    <div v-if="total > 0" class="activity-pagination">
      <ElPagination
        background
        layout="total, sizes, prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        @update:current-page="changePage"
        @update:page-size="changePageSize"
      />
    </div>
  </section>
</template>
