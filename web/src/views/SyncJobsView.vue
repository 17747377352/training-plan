<script setup lang="ts">
import { ElPagination } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { getSyncOverview, listSyncJobs, retrySyncJob } from "../api/sync";
import PageHeading from "../components/PageHeading.vue";
import type { SyncJob, SyncJobQuery, SyncOverview } from "../types/api";

const STATUS_LABELS: Record<string, string> = {
  PENDING: "排队中",
  RUNNING: "同步中",
  SUCCESS: "成功",
  FAILED: "失败",
};

const STATUS_TAG_TYPES: Record<string, "success" | "warning" | "danger" | "info"> =
  {
    PENDING: "info",
    RUNNING: "warning",
    SUCCESS: "success",
    FAILED: "danger",
  };

const TYPE_LABELS: Record<string, string> = {
  MANUAL: "手动",
  SCHEDULED: "定时",
};

const AUTH_LABELS: Record<string, string> = {
  ACTIVE: "正常",
  REAUTH_REQUIRED: "需重新认证",
  EXPIRED: "已过期",
};

const loading = ref(false);
const loadFailed = ref(false);
const jobs = ref<SyncJob[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(10);
const overview = ref<SyncOverview | null>(null);
const retryingId = ref<number | null>(null);
const feedback = ref<{ type: "success" | "error"; text: string } | null>(null);

const filters = reactive<{ jobStatus: string; jobType: string }>({
  jobStatus: "",
  jobType: "",
});

const hasAccount = computed(() => overview.value?.hasGarminAccount ?? true);

const resultText = computed(() => {
  if (loading.value) return "正在读取同步任务";
  return `共 ${total.value} 条任务`;
});

function isUnfinished(job: SyncJob): boolean {
  return job.jobStatus === "PENDING" || job.jobStatus === "RUNNING";
}

function buildQuery(): SyncJobQuery {
  const query: SyncJobQuery = { page: page.value, size: pageSize.value };
  if (filters.jobStatus) query.jobStatus = filters.jobStatus;
  if (filters.jobType) query.jobType = filters.jobType;
  return query;
}

async function loadJobs(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const result = await listSyncJobs(buildQuery());
    jobs.value = result.records;
    total.value = result.total;
    page.value = result.page;
    pageSize.value = result.size;
  } catch {
    loadFailed.value = true;
    jobs.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

async function loadOverview(): Promise<void> {
  try {
    overview.value = await getSyncOverview();
  } catch {
    overview.value = null;
  }
}

function refresh(): void {
  void Promise.all([loadOverview(), loadJobs()]);
}

function search(): void {
  page.value = 1;
  void loadJobs();
}

function resetFilters(): void {
  filters.jobStatus = "";
  filters.jobType = "";
  page.value = 1;
  void loadJobs();
}

function changePage(nextPage: number): void {
  page.value = nextPage;
  void loadJobs();
}

function changePageSize(nextSize: number): void {
  pageSize.value = nextSize;
  page.value = 1;
  void loadJobs();
}

async function retry(job: SyncJob): Promise<void> {
  retryingId.value = job.id;
  feedback.value = null;
  try {
    const newJobId = await retrySyncJob(job.id);
    feedback.value = {
      type: "success",
      text: `已按原区间重新排队，新任务 #${newJobId}`,
    };
    page.value = 1;
    await Promise.all([loadOverview(), loadJobs()]);
  } catch (error) {
    feedback.value = {
      type: "error",
      text: error instanceof Error ? error.message : "重试失败，请稍后再试",
    };
  } finally {
    retryingId.value = null;
  }
}

function formatDateTime(value?: string | null): string {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function formatDuration(seconds?: number | null): string {
  if (seconds == null) return "--";
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))} 秒`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return `${minutes} 分 ${rest} 秒`;
}

/** 数据区间；V5 之前的任务没有记录区间，如实说明而不是留空。 */
function formatRange(job: SyncJob): string {
  if (!job.startDate || !job.endDate) return "未记录";
  if (job.startDate === job.endDate) return job.startDate;
  return `${job.startDate} ~ ${job.endDate}`;
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] || status;
}

function statusTagType(status: string) {
  return STATUS_TAG_TYPES[status] || "info";
}

function typeLabel(type: string): string {
  return TYPE_LABELS[type] || type;
}

function failureText(job: SyncJob): string {
  if (job.jobStatus !== "FAILED") return "--";
  return job.errorMessage || job.errorCode || "未记录原因";
}

onMounted(refresh);
</script>

<template>
  <section class="page-container sync-jobs-page">
    <PageHeading
      eyebrow="数据同步"
      title="同步任务"
      description="查看每次同步的数据区间、执行状态与失败原因，失败的任务可以按原区间重跑。"
    >
      <template #actions>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeading>

    <el-alert
      v-if="!hasAccount"
      title="还没有绑定 Garmin 账号"
      description="先在「Garmin 账号」页面完成绑定，之后每次同步都会出现在这里。"
      type="info"
      :closable="false"
      show-icon
      class="sync-hint"
    />

    <div class="sync-summary">
      <el-card class="sync-summary-card" shadow="never">
        <p class="sync-summary-label">上次成功同步</p>
        <p class="sync-summary-value">
          {{ formatDateTime(overview?.lastSuccessTime) }}
        </p>
        <p class="sync-summary-meta">最近一次成功任务的结束时间</p>
      </el-card>
      <el-card class="sync-summary-card" shadow="never">
        <p class="sync-summary-label">上次失败</p>
        <p class="sync-summary-value">
          {{ formatDateTime(overview?.lastFailureTime) }}
        </p>
        <p class="sync-summary-meta">
          近 7 天失败 {{ overview?.failureCount7d ?? 0 }} 次
        </p>
      </el-card>
      <el-card class="sync-summary-card" shadow="never">
        <p class="sync-summary-label">进行中</p>
        <p class="sync-summary-value">{{ overview?.unfinishedCount ?? 0 }}</p>
        <p class="sync-summary-meta">排队中与同步中的任务数</p>
      </el-card>
    </div>

    <el-card
      v-if="overview && overview.accounts.length > 0"
      class="sync-account-card"
      shadow="never"
    >
      <p class="sync-section-title">账号同步状态</p>
      <ul class="sync-account-list">
        <li v-for="account in overview.accounts" :key="account.accountId">
          <div class="sync-account-head">
            <strong>{{ account.accountLabel }}</strong>
            <el-tag
              :type="account.authStatus === 'ACTIVE' ? 'success' : 'danger'"
              size="small"
              effect="light"
            >
              {{ AUTH_LABELS[account.authStatus] || account.authStatus }}
            </el-tag>
            <el-tag
              :type="account.syncEnabled === 1 ? 'success' : 'info'"
              size="small"
              effect="plain"
            >
              {{ account.syncEnabled === 1 ? "自动同步开" : "自动同步关" }}
            </el-tag>
          </div>
          <dl class="sync-account-meta">
            <div>
              <dt>最近成功</dt>
              <dd>{{ formatDateTime(account.lastSuccessTime) }}</dd>
            </div>
            <div>
              <dt>最近数据区间</dt>
              <dd>
                {{
                  account.lastStartDate
                    ? account.lastStartDate === account.lastEndDate
                      ? account.lastStartDate
                      : `${account.lastStartDate} ~ ${account.lastEndDate}`
                    : "未记录"
                }}
              </dd>
            </div>
            <div>
              <dt>最近失败原因</dt>
              <dd>{{ account.lastErrorMessage || "--" }}</dd>
            </div>
          </dl>
        </li>
      </ul>
    </el-card>

    <el-card class="sync-filter-card" shadow="never">
      <div class="sync-filters">
        <el-select
          v-model="filters.jobStatus"
          clearable
          placeholder="全部状态"
          class="sync-status-select"
        >
          <el-option label="排队中" value="PENDING" />
          <el-option label="同步中" value="RUNNING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-select
          v-model="filters.jobType"
          clearable
          placeholder="全部类型"
          class="sync-type-select"
        >
          <el-option label="手动" value="MANUAL" />
          <el-option label="定时" value="SCHEDULED" />
        </el-select>
        <div class="filter-actions">
          <el-button type="primary" :loading="loading" @click="search"
            >查询</el-button
          >
          <el-button
            :disabled="loading"
            @click="resetFilters"
            >重置</el-button
          >
        </div>
      </div>
    </el-card>

    <div class="sync-result-bar">
      <div>
        <strong>同步记录</strong>
        <span>{{ resultText }}</span>
      </div>
      <span class="result-order">按创建时间倒序</span>
    </div>

    <el-alert
      v-if="feedback"
      :title="feedback.text"
      :type="feedback.type === 'success' ? 'success' : 'error'"
      :closable="false"
      show-icon
      class="sync-feedback"
    />

    <el-alert
      v-if="loadFailed"
      title="同步任务加载失败"
      description="请确认后端服务正常，然后重试。"
      type="error"
      :closable="false"
      show-icon
      class="sync-load-error"
    />

    <div v-loading="loading" class="sync-table-shell">
      <el-empty
        v-if="!loading && !loadFailed && jobs.length === 0"
        description="还没有同步记录"
      >
        <el-button
          v-if="filters.jobStatus || filters.jobType"
          @click="resetFilters"
        >
          清除筛选
        </el-button>
      </el-empty>

      <template v-else>
        <el-table :data="jobs" class="sync-table" row-key="id">
          <el-table-column label="任务" min-width="130">
            <template #default="{ row }">
              <div class="sync-job-cell">
                <strong>#{{ row.id }}</strong>
                <span>{{ row.accountLabel }}</span>
                <span v-if="row.retryOfJobId" class="sync-retry-of">
                  重试自 #{{ row.retryOfJobId }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ typeLabel(row.jobType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.jobStatus)" size="small">
                {{ statusLabel(row.jobStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数据区间" min-width="180">
            <template #default="{ row }">{{ formatRange(row) }}</template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="130">
            <template #default="{ row }">{{
              formatDateTime(row.startedTime ?? row.createTime)
            }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">
              {{
                isUnfinished(row)
                  ? "进行中"
                  : formatDuration(row.durationSeconds)
              }}
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="200">
            <template #default="{ row }">
              <span :class="{ 'sync-failure-text': row.jobStatus === 'FAILED' }">
                {{ failureText(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.jobStatus === 'FAILED'"
                link
                type="primary"
                :loading="retryingId === row.id"
                :disabled="retryingId !== null"
                @click="retry(row)"
              >
                重试
              </el-button>
              <span v-else class="sync-no-action">--</span>
            </template>
          </el-table-column>
        </el-table>

        <ElPagination
          v-if="total > pageSize"
          class="sync-pagination"
          layout="total, sizes, prev, pager, next"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </template>
    </div>
  </section>
</template>
