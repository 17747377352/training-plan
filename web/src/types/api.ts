export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  total: number;
  page: number;
  size: number;
  records: T[];
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
  "PENDING" | "PENDING_MFA" | "ACTIVE" | "REAUTH_REQUIRED";

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

export interface ActivityQuery {
  page: number;
  size: number;
  startDate?: string;
  endDate?: string;
  typeKey?: string;
  keyword?: string;
}

export interface ActivitySummary {
  id: number;
  activityTypeKey: string;
  activityName?: string | null;
  startTime?: string | null;
  durationSeconds?: number | null;
  movingDurationSeconds?: number | null;
  distanceMeters?: number | null;
  elevationGain?: number | null;
  averageSpeed?: number | null;
  maxSpeed?: number | null;
  averageHr?: number | null;
  maxHr?: number | null;
  calories?: number | null;
  avgPower?: number | null;
  normPower?: number | null;
  max20minPower?: number | null;
  intensityFactor?: number | null;
  trainingStressScore?: number | null;
  avgCadence?: number | null;
  avgLeftBalance?: number | null;
  aerobicTrainingEffect?: number | null;
  anaerobicTrainingEffect?: number | null;
  trainingEffectLabel?: string | null;
  activityTrainingLoad?: number | null;
  vo2maxValue?: number | null;
}
