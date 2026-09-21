<script setup lang="ts">
import type { NavIconName } from "../types/navigation";

defineProps<{ name: NavIconName }>();
</script>

<template>
  <svg
    class="nav-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'overview'">
      <rect x="3.5" y="3.5" width="6.5" height="6.5" rx="1.6" />
      <rect x="14" y="3.5" width="6.5" height="6.5" rx="1.6" />
      <rect x="3.5" y="14" width="6.5" height="6.5" rx="1.6" />
      <rect x="14" y="14" width="6.5" height="6.5" rx="1.6" />
    </template>
    <template v-else-if="name === 'trends'">
      <path d="M4 18.5V5.5" />
      <path d="M4 18.5h16" />
      <path d="m7 15 3.4-4 3 2.4L19 7" />
    </template>
    <template v-else-if="name === 'activities'">
      <circle cx="6" cy="17.5" r="3" />
      <circle cx="18" cy="17.5" r="3" />
      <path d="m6 17.5 3.2-6.2h4.3l4.5 6.2" />
      <path d="m9.2 11.3 3.4 6.2 3-9.2" />
      <path d="M13.6 8.3h3" />
      <path d="M8.6 8.3h2" />
    </template>
    <template v-else-if="name === 'garmin'">
      <circle cx="10" cy="8" r="3.2" />
      <path d="M4.5 19c.7-3.5 2.5-5.3 5.5-5.3s4.8 1.8 5.5 5.3" />
      <path d="M18.2 8.7v5" />
      <path d="M15.7 11.2h5" />
    </template>
    <template v-else-if="name === 'load'">
      <path d="M4 19.5h16" />
      <path d="M4 19.5V4.5" />
      <path d="m6.5 15.5 3.4-4.6 3.1 2.6 5-6.5" />
      <circle cx="9.9" cy="10.9" r="1.3" />
      <circle cx="18" cy="7" r="1.3" />
    </template>
    <template v-else-if="name === 'sync'">
      <path d="M20 11.5a8 8 0 0 0-13.8-5.4L3.5 8.6" />
      <path d="M4 12.5a8 8 0 0 0 13.8 5.4l2.7-2.5" />
      <path d="M3.5 4.6v4h4" />
      <path d="M20.5 19.4v-4h-4" />
    </template>
    <template v-else-if="name === 'export'">
      <path d="M12 3.5v11" />
      <path d="m8.2 10.8 3.8 3.8 3.8-3.8" />
      <path d="M5 16v3.5h14V16" />
    </template>
    <template v-else-if="name === 'settings'">
      <path d="M4 7h7" />
      <path d="M15 7h5" />
      <circle cx="13" cy="7" r="2" />
      <path d="M4 17h4" />
      <path d="M12 17h8" />
      <circle cx="10" cy="17" r="2" />
    </template>
    <template v-else>
      <path
        d="M12 3.5 19 6v5.2c0 4.5-2.4 7.5-7 9.3-4.6-1.8-7-4.8-7-9.3V6l7-2.5Z"
      />
      <path d="m9 12 2 2 4-4" />
    </template>
  </svg>
</template>
