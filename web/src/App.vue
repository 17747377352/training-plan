<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import SiteLegalFooter from "./components/SiteLegalFooter.vue";

const route = useRoute();
const isPublicPage = computed(() => Boolean(route.meta.public));
</script>

<template>
  <div v-if="isPublicPage" class="public-shell">
    <header class="public-header">
      <div class="brand-lockup">
        <span class="brand-mark">TP</span>
        <div>
          <strong>Training Plan</strong>
          <span>Garmin 数据管理平台</span>
        </div>
      </div>
    </header>
    <router-view />
    <SiteLegalFooter />
  </div>
  <router-view v-else />
</template>
