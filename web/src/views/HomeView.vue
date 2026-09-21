<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
import { getSystemStatus } from "../api/system";
import type { SystemStatus } from "../types/api";

const router = useRouter();
const loading = ref(false);
const errorMessage = ref("");
const systemStatus = ref<SystemStatus>();

const formattedTime = computed(() => {
  if (!systemStatus.value?.timestamp) return "--";
  return new Date(systemStatus.value.timestamp).toLocaleString("zh-CN");
});

async function loadStatus() {
  if (loading.value) return;
  loading.value = true;
  errorMessage.value = "";
  try {
    systemStatus.value = await getSystemStatus();
  } catch {
    errorMessage.value = "后端服务暂时不可用，请确认 Java 服务已启动。";
  } finally {
    loading.value = false;
  }
}

onMounted(async () => {
  await loadStatus();
});
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="数据工作台"
      title="概览"
      description="查看平台状态和数据能力，进入各模块管理训练数据。"
    >
      <template #actions>
        <el-button type="primary" @click="router.push('/garmin')"
          >管理 Garmin 账号</el-button
        >
      </template>
    </PageHeading>

    <el-card v-loading="loading" class="status-card">
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
      >
        <template #default>
          <el-button type="primary" link @click="loadStatus"
            >重新检查</el-button
          >
        </template>
      </el-alert>

      <div v-else class="status-row">
        <div>
          <h2 class="status-title">Java 后端</h2>
          <p class="status-meta">
            {{ systemStatus?.application || "等待连接" }} · {{ formattedTime }}
          </p>
        </div>
        <el-tag
          :type="systemStatus?.status === 'UP' ? 'success' : 'info'"
          size="large"
        >
          {{ systemStatus?.status || "UNKNOWN" }}
        </el-tag>
      </div>
    </el-card>

    <div class="section-heading">
      <div>
        <h2>数据能力</h2>
        <p>采集链路已经接入，查询与展示页面将逐步开放。</p>
      </div>
    </div>
    <div class="feature-grid">
      <FeaturePanel
        title="每日健康"
        description="步数、距离、静息心率、压力和身体电量。"
        status="已接入"
      />
      <FeaturePanel
        title="睡眠与 HRV"
        description="睡眠阶段、评分、夜间 HRV 与个人基准。"
        status="已接入"
      />
      <FeaturePanel
        title="骑行活动"
        description="距离、爬升、功率、TSS、IF 与功率区间。"
        status="已接入"
      />
      <FeaturePanel
        title="趋势分析"
        description="查询接口与图表仍在开发，当前菜单提供页面骨架。"
        status="开发中"
      />
    </div>
  </section>
</template>
