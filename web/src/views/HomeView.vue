<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { getSystemStatus } from "../api/system";
import { useAuthStore } from "../stores/auth";
import type { SystemStatus } from "../types/api";

const authStore = useAuthStore();
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

async function handleLogout(): Promise<void> {
  await authStore.logout();
  await router.replace("/login");
}

onMounted(async () => {
  await Promise.all([loadStatus(), authStore.loadProfile()]);
});
</script>

<template>
  <section class="page-container">
    <header class="page-heading dashboard-heading">
      <div>
        <h1>数据管理后台</h1>
        <p>管理 Garmin 账号、同步任务与个人训练数据。</p>
      </div>
      <div class="user-actions">
        <div>
          <strong>{{ authStore.profile?.username || "用户" }}</strong>
          <span>{{ authStore.profile?.email }}</span>
        </div>
        <el-button @click="handleLogout">退出登录</el-button>
      </div>
    </header>

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
  </section>
</template>
