import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "../utils/token";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(to) {
    return to.hash ? { el: to.hash, top: 24, behavior: "smooth" } : { top: 0 };
  },
  routes: [
    {
      path: "/",
      component: () => import("../layouts/MainLayout.vue"),
      children: [
        {
          path: "",
          name: "home",
          component: () => import("../views/HomeView.vue"),
          meta: { title: "概览" },
        },
        {
          path: "trends",
          name: "trends",
          component: () => import("../views/TrendsView.vue"),
          meta: { title: "趋势" },
        },
        {
          path: "training-load",
          name: "training-load",
          component: () => import("../views/TrainingLoadView.vue"),
          meta: { title: "训练负荷" },
        },
        {
          path: "activities",
          name: "activities",
          component: () => import("../views/ActivitiesView.vue"),
          meta: { title: "活动" },
        },
        {
          path: "activities/:id",
          name: "activity-detail",
          component: () => import("../views/ActivityDetailView.vue"),
          meta: { title: "活动详情" },
        },
        {
          path: "garmin",
          name: "garmin",
          component: () => import("../views/GarminAccountsView.vue"),
          meta: { title: "Garmin 账号" },
        },
        {
          path: "sync-jobs",
          name: "sync-jobs",
          component: () => import("../views/SyncJobsView.vue"),
          meta: { title: "同步任务" },
        },
        {
          path: "export",
          name: "export",
          component: () => import("../views/DataExportView.vue"),
          meta: { title: "数据导出" },
        },
        {
          path: "settings",
          name: "settings",
          component: () => import("../views/SettingsView.vue"),
          meta: { title: "设置" },
        },
        {
          path: "admin",
          name: "admin",
          component: () => import("../views/AdminView.vue"),
          meta: { title: "管理", adminOnly: true },
        },
      ],
    },
    {
      path: "/login",
      name: "login",
      component: () => import("../views/LoginView.vue"),
      meta: { public: true, guestOnly: true, title: "登录" },
    },
    {
      path: "/register",
      name: "register",
      component: () => import("../views/RegisterView.vue"),
      meta: { public: true, guestOnly: true, title: "注册" },
    },
    {
      path: "/legal/terms",
      name: "terms",
      component: () => import("../views/TermsView.vue"),
      meta: { public: true, title: "用户协议" },
    },
    {
      path: "/legal/privacy",
      name: "privacy",
      component: () => import("../views/PrivacyPolicyView.vue"),
      meta: { public: true, title: "隐私政策" },
    },
    {
      path: "/legal/disclaimer",
      name: "disclaimer",
      component: () => import("../views/DisclaimerView.vue"),
      meta: { public: true, title: "免责声明" },
    },
  ],
});

router.beforeEach((to) => {
  const authenticated = Boolean(getAccessToken());
  if (!to.meta.public && !authenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta.guestOnly && authenticated) {
    return { name: "home" };
  }
  return true;
});

router.afterEach((to) => {
  const title = typeof to.meta.title === "string" ? `${to.meta.title} · ` : "";
  document.title = `${title}Training Plan`;
});

export default router;
