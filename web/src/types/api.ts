export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface SystemStatus {
  application: string;
  status: string;
  timestamp: string;
}

export interface AuthTokens {
  tokenType: string;
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresIn: number;
}

export interface UserProfile {
  id: number;
  username: string;
  email: string;
  roles: string[];
}

/** Garmin 站点区域：国际站或中国区。 */
export type GarminRegion = "GLOBAL" | "CN";

/** Garmin 账号认证状态。 */
export type GarminAuthStatus =
  | "PENDING"
  | "PENDING_MFA"
  | "ACTIVE"
  | "REAUTH_REQUIRED";

export interface GarminAccount {
  id: number;
  region: GarminRegion;
  emailMasked: string;
  authStatus: GarminAuthStatus;
  syncEnabled: number;
  lastSyncTime?: string | null;
  createTime?: string | null;
}

export interface GarminConnectResult {
  status: "CONNECTED" | "MFA_REQUIRED";
  loginSessionId?: string | null;
  account?: GarminAccount | null;
}
