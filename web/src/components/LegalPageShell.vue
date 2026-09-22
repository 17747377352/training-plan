<script setup lang="ts">
defineProps<{
  eyebrow: string;
  title: string;
  description: string;
}>();
</script>

<template>
  <main class="legal-page">
    <article class="legal-document">
      <RouterLink class="legal-back" to="/">← 返回 Training Plan</RouterLink>
      <header class="legal-heading">
        <p>{{ eyebrow }}</p>
        <h1>{{ title }}</h1>
        <span>更新日期：2026 年 9 月 22 日</span>
        <strong>{{ description }}</strong>
      </header>
      <div class="legal-body">
        <slot />
      </div>
    </article>
  </main>
</template>
