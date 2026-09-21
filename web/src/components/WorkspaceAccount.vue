<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  username?: string;
  email?: string;
}>();

const emit = defineEmits<{
  logout: [];
}>();

const initial = computed(() => (props.username || "U").trim().slice(0, 1));
</script>

<template>
  <div class="workspace-account">
    <span class="account-avatar">{{ initial }}</span>
    <div class="account-details">
      <strong>{{ username || "个人用户" }}</strong>
      <span>{{ email || "账号信息加载中" }}</span>
    </div>
    <button
      type="button"
      class="logout-button"
      title="退出登录"
      aria-label="退出登录"
      @click="emit('logout')"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M10 5H5v14h5" />
        <path d="M13 8.5 16.5 12 13 15.5" />
        <path d="M8.5 12h8" />
      </svg>
    </button>
  </div>
</template>
