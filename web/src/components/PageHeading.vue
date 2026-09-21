<script setup lang="ts">
defineProps<{
  title: string;
  description: string;
  eyebrow?: string;
}>();
</script>

<template>
  <header class="page-heading">
    <div>
      <span v-if="eyebrow" class="page-eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
