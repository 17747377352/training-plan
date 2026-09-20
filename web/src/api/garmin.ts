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

export function listAccounts(): Promise<GarminAccount[]> {
  return request({ method: "GET", url: "/api/garmin/accounts" });
}

/** 提交 Garmin 凭据，可能直接连接成功，也可能需要继续输入验证码。 */
export function connectAccount(
  form: ConnectGarminForm,
): Promise<GarminConnectResult> {
  return request({ method: "POST", url: "/api/garmin/accounts/connect", data: form });
}

export function submitMfa(
  loginSessionId: string,
  mfaCode: string,
): Promise<GarminConnectResult> {
  return request({
    method: "POST",
    url: "/api/garmin/accounts/connect/mfa",
    data: { loginSessionId, mfaCode },
  });
}

/** 用已存令牌校验账号是否仍然可用。 */
export function verifyAccount(id: number): Promise<GarminAccount> {
  return request({ method: "POST", url: `/api/garmin/accounts/${id}/verify` });
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
