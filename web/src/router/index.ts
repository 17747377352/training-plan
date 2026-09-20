import { createRouter, createWebHistory } from "vue-router";
import { getAccessToken } from "../utils/token";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/",
      name: "home",
      component: () => import("../views/HomeView.vue"),
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
    {
      path: "/garmin",
      name: "garmin",
      component: () => import("../views/GarminAccountsView.vue"),
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

export default router;
