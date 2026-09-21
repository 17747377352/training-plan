<script setup lang="ts">
import FeaturePanel from "../components/FeaturePanel.vue";
import PageHeading from "../components/PageHeading.vue";
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="数据自主"
      title="数据导出"
      description="下载个人健康和训练数据，便于分析与备份。"
    />
    <div class="feature-grid">
      <FeaturePanel
        title="CSV 导出"
        description="适合在 Excel、Numbers 或数据分析工具中使用。"
        status="计划中"
      />
      <FeaturePanel
        title="JSON 导出"
        description="保留字段结构，适合程序处理和完整备份。"
        status="计划中"
      />
    </div>
  </section>
</template>
