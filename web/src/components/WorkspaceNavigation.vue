<script setup lang="ts">
import type { NavIconName } from "../types/navigation";
import NavIcon from "./NavIcon.vue";

interface MenuItem {
  path: string;
  label: string;
  icon: NavIconName;
  adminOnly?: boolean;
}

interface MenuGroup {
  label: string;
  items: MenuItem[];
}

defineProps<{
  activePath: string;
  isAdmin: boolean;
}>();

const emit = defineEmits<{
  navigate: [path: string, disabled: boolean];
}>();

const menuGroups: MenuGroup[] = [
  {
    label: "工作台",
    items: [
      { path: "/", label: "概览", icon: "overview" },
      { path: "/trends", label: "趋势", icon: "trends" },
      { path: "/activities", label: "活动", icon: "activities" },
    ],
  },
  {
    label: "数据管理",
    items: [
      { path: "/garmin", label: "Garmin 账号", icon: "garmin" },
      { path: "/sync-jobs", label: "同步任务", icon: "sync" },
      { path: "/export", label: "数据导出", icon: "export" },
    ],
  },
  {
    label: "系统",
    items: [
      { path: "/settings", label: "设置", icon: "settings" },
      {
        path: "/admin",
        label: "管理",
        icon: "admin",
        adminOnly: true,
      },
    ],
  },
];

function isActive(path: string, activePath: string): boolean {
  return (
    activePath === path || (path !== "/" && activePath.startsWith(`${path}/`))
  );
}
</script>

<template>
  <nav class="workspace-navigation" aria-label="主导航">
    <section v-for="group in menuGroups" :key="group.label" class="nav-group">
      <p class="nav-group-label">{{ group.label }}</p>
      <button
        v-for="item in group.items"
        :key="item.path"
        type="button"
        class="nav-item"
        :class="{ active: isActive(item.path, activePath) }"
        :disabled="Boolean(item.adminOnly && !isAdmin)"
        @click="
          emit('navigate', item.path, Boolean(item.adminOnly && !isAdmin))
        "
      >
        <NavIcon :name="item.icon" />
        <span class="nav-item-label">{{ item.label }}</span>
        <span v-if="item.adminOnly && !isAdmin" class="permission-badge"
          >ADMIN</span
        >
      </button>
    </section>
  </nav>
</template>
