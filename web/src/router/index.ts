import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "../utils/token";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
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
      meta: { public: true },
    },
    {
      path: "/register",
      name: "register",
      component: () => import("../views/RegisterView.vue"),
      meta: { public: true },
    },
  ],
});

router.beforeEach((to) => {
  const authenticated = Boolean(getAccessToken());
  if (!to.meta.public && !authenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta.public && authenticated) {
    return { name: "home" };
  }
  return true;
});

router.afterEach((to) => {
  const title = typeof to.meta.title === "string" ? `${to.meta.title} · ` : "";
  document.title = `${title}Training Plan`;
});

export default router;
