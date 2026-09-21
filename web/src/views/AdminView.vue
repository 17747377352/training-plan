<script setup lang="ts">
import { computed } from "vue";
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
import { useAuthStore } from "../stores/auth";

const authStore = useAuthStore();
const isAdmin = computed(
  () => authStore.profile?.roles.includes("ADMIN") ?? false,
);
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="平台运维"
      title="管理"
      description="管理平台用户和运行状态。"
    />
    <el-alert
      v-if="!isAdmin"
      title="当前账号没有管理员权限。"
      description="管理员菜单仅对 ADMIN 角色开放。"
      type="warning"
      :closable="false"
      show-icon
    />
    <div v-else class="feature-grid">
      <FeaturePanel
        title="用户管理"
        description="后端已经支持用户查询、启用和禁用，前端页面待接入。"
        status="待接入"
      />
      <FeaturePanel
        title="同步任务"
        description="计划提供任务历史、失败原因和重试操作。"
        status="计划中"
      />
    </div>
  </section>
</template>
