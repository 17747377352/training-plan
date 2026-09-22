<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import WorkspaceAccount from "../components/WorkspaceAccount.vue";
import WorkspaceNavigation from "../components/WorkspaceNavigation.vue";
import SiteLegalFooter from "../components/SiteLegalFooter.vue";
import { useAuthStore } from "../stores/auth";

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const mobileMenuVisible = ref(false);
const loadingProfile = ref(false);

const activePath = computed(() => route.path);
const pageTitle = computed(() => String(route.meta.title || "Training Plan"));
const isAdmin = computed(
  () => authStore.profile?.roles.includes("ADMIN") ?? false,
);

async function ensureProfile(): Promise<void> {
  if (authStore.profile || loadingProfile.value) return;
  loadingProfile.value = true;
  try {
    await authStore.loadProfile();
  } catch {
    // 请求层会统一提示错误，布局仍保留可用的降级状态。
  } finally {
    loadingProfile.value = false;
  }
}

async function navigate(path: string, disabled: boolean): Promise<void> {
  if (disabled) return;
  mobileMenuVisible.value = false;
  if (path !== route.path) await router.push(path);
}

async function handleLogout(): Promise<void> {
  await authStore.logout();
  await router.replace("/login");
}

onMounted(ensureProfile);
</script>

<template>
  <el-container class="workspace-shell">
    <el-aside class="workspace-sidebar" width="252px">
      <div class="workspace-brand">
        <span class="brand-mark">TP</span>
        <div>
          <strong>Training Plan</strong>
          <span>训练数据工作台</span>
        </div>
      </div>

      <WorkspaceNavigation
        :active-path="activePath"
        :is-admin="isAdmin"
        @navigate="navigate"
      />

      <WorkspaceAccount
        :username="authStore.profile?.username"
        :email="authStore.profile?.email"
        @logout="handleLogout"
      />
    </el-aside>

    <el-container class="workspace-main">
      <el-header class="workspace-header">
        <div class="mobile-brand">
          <button
            type="button"
            class="menu-trigger"
            aria-label="打开主菜单"
            @click="mobileMenuVisible = true"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 7h16M4 12h16M4 17h16" />
            </svg>
          </button>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="desktop-page-title">
          <span>TRAINING PLAN</span>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="header-user">
          <span class="header-status-dot"></span>
          <span>{{ authStore.profile?.username || "个人工作台" }}</span>
        </div>
      </el-header>
      <el-main class="workspace-content">
        <router-view />
      </el-main>
      <SiteLegalFooter />
    </el-container>

    <el-drawer
      v-model="mobileMenuVisible"
      direction="ltr"
      size="280px"
      :with-header="false"
      class="mobile-navigation"
    >
      <div class="mobile-drawer-body">
        <div class="workspace-brand drawer-brand">
          <span class="brand-mark">TP</span>
          <div>
            <strong>Training Plan</strong>
            <span>训练数据工作台</span>
          </div>
        </div>
        <WorkspaceNavigation
          :active-path="activePath"
          :is-admin="isAdmin"
          @navigate="navigate"
        />
        <WorkspaceAccount
          :username="authStore.profile?.username"
          :email="authStore.profile?.email"
          @logout="handleLogout"
        />
      </div>
    </el-drawer>
  </el-container>
</template>
