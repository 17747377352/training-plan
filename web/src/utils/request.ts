import axios, {
  AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from "axios";
import { ElMessage } from "element-plus";
import type { ApiResult, AuthTokens } from "../types/api";
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  saveTokens,
} from "./token";

const baseURL = import.meta.env.VITE_API_BASE_URL;
const loginPath = `${import.meta.env.BASE_URL}login`;

const client = axios.create({
  baseURL,
  timeout: 15_000,
});

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

let refreshPromise: Promise<AuthTokens> | undefined;

client.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult<unknown>>) => {
    const original = error.config as RetryableRequestConfig | undefined;
    const refreshToken = getRefreshToken();
    if (
      error.response?.status === 401 &&
      original &&
      !original._retry &&
      refreshToken &&
      !original.url?.includes("/api/auth/refresh")
    ) {
      original._retry = true;
      try {
        refreshPromise ??= refreshAccessToken(refreshToken);
        const tokens = await refreshPromise;
        saveTokens(tokens);
        original.headers.Authorization = `Bearer ${tokens.accessToken}`;
        return await client.request(original);
      } catch (refreshError) {
        clearTokens();
        if (window.location.pathname !== loginPath) {
          window.location.assign(loginPath);
        }
        return Promise.reject(refreshError);
      } finally {
        refreshPromise = undefined;
      }
    }

    const message =
      error.response?.data?.message ||
      (error.code === "ECONNABORTED"
        ? "请求超时，请稍后重试"
        : error.message) ||
      "网络请求失败";
    ElMessage.error(message);
    return Promise.reject(error);
  },
);

async function refreshAccessToken(refreshToken: string): Promise<AuthTokens> {
  const response = await axios.post<ApiResult<AuthTokens>>(
    `${baseURL}/api/auth/refresh`,
    { refreshToken },
    { timeout: 15_000 },
  );
  if (response.data.code !== 200) {
    throw new Error(response.data.message || "登录已失效");
  }
  return response.data.data;
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await client.request<ApiResult<T>>(config);
  const result = response.data;
  if (result.code !== 200) {
    ElMessage.error(result.message || "业务处理失败");
    throw new Error(result.message);
  }
  return result.data;
}
