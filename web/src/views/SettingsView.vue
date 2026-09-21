<script setup lang="ts">
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="个人偏好"
      title="设置"
      description="管理训练目标、显示偏好和同步策略。"
    />
    <div class="feature-grid">
      <FeaturePanel
        title="训练目标"
        description="计划支持体重、FTP、功体比和每周训练目标。"
        status="计划中"
      />
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
