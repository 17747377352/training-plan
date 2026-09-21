<script setup lang="ts">
import PageHeading from "../components/PageHeading.vue";
import TrainingAdvicePanel from "../components/TrainingAdvicePanel.vue";
</script>

<template>
  <section class="page-container">
    <PageHeading
      eyebrow="训练工作台"
      title="今天，怎么练"
      description="把训练负荷、恢复信号与主观感受放在一起，安排今天的骑行。"
    >
      <template #actions>
        <RouterLink to="/trends"
          ><el-button>查看恢复趋势</el-button></RouterLink
        >
      </template>
    </PageHeading>
    <TrainingAdvicePanel />
  </section>
</template>
