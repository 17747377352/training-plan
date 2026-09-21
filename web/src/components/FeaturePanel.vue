<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
  status?: string;
}>();
</script>

<template>
  <el-card class="feature-panel" shadow="never">
    <div class="feature-panel-heading">
      <strong>{{ title }}</strong>
      <el-tag v-if="status" type="info" effect="plain">{{ status }}</el-tag>
    </div>
    <p>{{ description }}</p>
    <slot />
  </el-card>
</template>
