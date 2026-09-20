import type {
  GarminAccount,
  GarminConnectResult,
  GarminRegion,
} from "../types/api";
import { request } from "../utils/request";

export interface ConnectGarminForm {
  email: string;
  password: string;
  region: GarminRegion;
}

/**
 * Garmin 登录链路的超时时间。
 *
 * 官方库会依次尝试多段登录策略，实测国际站一次登录约 30 秒，
 * 因此这里必须明显大于后端调用采集器的超时（当前 120 秒），
 * 否则最外层先超时，会把"Garmin 慢"显示成前端请求超时。
 */
const GARMIN_TIMEOUT = 150_000;

export function listAccounts(): Promise<GarminAccount[]> {
  return request({ method: "GET", url: "/api/garmin/accounts" });
}

/** 提交 Garmin 凭据，可能直接连接成功，也可能需要继续输入验证码。 */
export function connectAccount(
  form: ConnectGarminForm,
): Promise<GarminConnectResult> {
  return request({
    method: "POST",
    url: "/api/garmin/accounts/connect",
    data: form,
    timeout: GARMIN_TIMEOUT,
  });
}

export function submitMfa(
  loginSessionId: string,
  mfaCode: string,
): Promise<GarminConnectResult> {
  return request({
    method: "POST",
    url: "/api/garmin/accounts/connect/mfa",
    data: { loginSessionId, mfaCode },
    timeout: GARMIN_TIMEOUT,
  });
}

/** 用已存令牌校验账号是否仍然可用。 */
export function verifyAccount(id: number): Promise<GarminAccount> {
  return request({
    method: "POST",
    url: `/api/garmin/accounts/${id}/verify`,
    timeout: GARMIN_TIMEOUT,
  });
}

/**
 * 导入已有 Garmin 令牌完成绑定。
 *
 * 用于 Garmin 对登录端点限流或要求人机验证、程序登录走不通时：
 * 用户在浏览器侧登录后取得令牌，交给平台存储与自动刷新。
 */
export function importToken(form: {
  email: string;
  tokenJson: string;
  region: GarminRegion;
}): Promise<GarminAccount> {
  return request({
    method: "POST",
    url: "/api/garmin/accounts/import-token",
    data: form,
    timeout: GARMIN_TIMEOUT,
  });
}

export function updateAutoSync(id: number, syncEnabled: number): Promise<void> {
  return request({
    method: "PUT",
    url: `/api/garmin/accounts/${id}/auto-sync`,
    data: { syncEnabled },
  });
}

export function deleteAccount(id: number): Promise<void> {
  return request({ method: "DELETE", url: `/api/garmin/accounts/${id}` });
}
