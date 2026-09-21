<script setup lang="ts">
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="恢复与表现"
      title="趋势"
      description="集中查看健康、睡眠和训练指标的变化。"
    />
    <el-alert
      title="趋势查询接口正在开发，当前页面已完成导航和布局接入。"
      type="info"
      :closable="false"
      show-icon
    />
    <div class="feature-grid page-section">
      <FeaturePanel
        title="睡眠与 HRV"
        description="计划展示夜间 HRV、7 日均值、个人基准和睡眠时长。"
        status="待接入"
      />
      <FeaturePanel
        title="心率与恢复"
        description="计划展示静息心率、最低心率和训练后的恢复变化。"
        status="待接入"
      />
      <FeaturePanel
        title="体重与负荷"
        description="计划组合体重趋势、活动能量和训练负荷。"
        status="待接入"
      />
    </div>
  </section>
</template>
