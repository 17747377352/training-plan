import { defineStore } from "pinia";
import { computed, ref } from "vue";
import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
  type LoginForm,
  type RegisterForm,
} from "../api/auth";
import type { UserProfile } from "../types/api";
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  saveTokens,
} from "../utils/token";

export const useAuthStore = defineStore("auth", () => {
  const profile = ref<UserProfile>();
  const accessToken = ref(getAccessToken());
  const isAuthenticated = computed(() => Boolean(accessToken.value));

  async function login(form: LoginForm): Promise<void> {
    const tokens = await loginRequest(form);
    saveTokens(tokens);
    accessToken.value = tokens.accessToken;
    profile.value = await getCurrentUser();
  }

  async function register(form: RegisterForm): Promise<void> {
    await registerRequest(form);
    await login({ account: form.username, password: form.password });
  }

  async function loadProfile(): Promise<void> {
    if (!accessToken.value) return;
    profile.value = await getCurrentUser();
  }

  async function logout(): Promise<void> {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) await logoutRequest(refreshToken);
    } finally {
      clearSession();
    }
  }

  function clearSession(): void {
    clearTokens();
    accessToken.value = null;
    profile.value = undefined;
  }

  return {
    profile,
    isAuthenticated,
    login,
    register,
    loadProfile,
    logout,
    clearSession,
  };
});
