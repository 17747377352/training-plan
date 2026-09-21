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

/** activity 表中已入库的全部字段。 */
export interface ActivityDetail {
  id: number;
  garminAccountId: number;
  garminActivityId: string;
  activityTypeKey?: string | null;
  activityTypeId?: number | null;
  parentTypeId?: number | null;
  activityName?: string | null;
  startTimeGmt?: string | null;
  startTimeLocal?: string | null;
  durationSeconds?: number | null;
  movingDurationSeconds?: number | null;
  elapsedDurationSeconds?: number | null;
  distanceMeters?: number | null;
  elevationGain?: number | null;
  elevationLoss?: number | null;
  avgElevation?: number | null;
  maxElevation?: number | null;
  minElevation?: number | null;
  averageSpeed?: number | null;
  maxSpeed?: number | null;
  averageHr?: number | null;
  maxHr?: number | null;
  calories?: number | null;
  bmrCalories?: number | null;
  avgPower?: number | null;
  maxPower?: number | null;
  normPower?: number | null;
  max20minPower?: number | null;
  intensityFactor?: number | null;
  trainingStressScore?: number | null;
  avgCadence?: number | null;
  maxCadence?: number | null;
  avgLeftBalance?: number | null;
  aerobicTrainingEffect?: number | null;
  anaerobicTrainingEffect?: number | null;
  trainingEffectLabel?: string | null;
  activityTrainingLoad?: number | null;
  powerZone1Seconds?: number | null;
  powerZone2Seconds?: number | null;
  powerZone3Seconds?: number | null;
  powerZone4Seconds?: number | null;
  powerZone5Seconds?: number | null;
  powerZone6Seconds?: number | null;
  powerZone7Seconds?: number | null;
  lapCount?: number | null;
  strokes?: number | null;
  avgRespirationRate?: number | null;
  minTemperature?: number | null;
  maxTemperature?: number | null;
  vo2maxValue?: number | null;
  deviceId?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface TrendQuery {
  startDate: string;
  endDate: string;
}

export interface DailyHealthTrend {
  calendarDate: string;
  steps?: number | null;
  distanceMeters?: number | null;
  totalKilocalories?: number | null;
  activeKilocalories?: number | null;
  restingHeartRate?: number | null;
  minHeartRate?: number | null;
  maxHeartRate?: number | null;
  averageStressLevel?: number | null;
  bodyBatteryHighest?: number | null;
  bodyBatteryLowest?: number | null;
}

export interface HrvTrend {
  calendarDate: string;
  lastNightAvg?: number | null;
  weeklyAvg?: number | null;
  hrvStatus?: string | null;
  baselineLowUpper?: number | null;
  baselineBalancedLow?: number | null;
  baselineBalancedUpper?: number | null;
}

export interface SleepTrend {
  calendarDate: string;
  sleepStartGmt?: string | null;
  sleepEndGmt?: string | null;
  sleepTimeSeconds?: number | null;
  deepSleepSeconds?: number | null;
  lightSleepSeconds?: number | null;
  remSleepSeconds?: number | null;
  awakeSleepSeconds?: number | null;
  sleepScore?: number | null;
  avgSleepHrv?: number | null;
  avgSpo2?: number | null;
  avgRespiration?: number | null;
}

export interface SyncJobQuery {
  page?: number;
  size?: number;
  jobStatus?: string;
  jobType?: string;
  garminAccountId?: number;
}

export interface SyncJob {
  id: number;
  garminAccountId: number;
  accountLabel: string;
  jobType: string;
  jobStatus: string;
  startDate?: string | null;
  endDate?: string | null;
  requestedBy?: number | null;
  startedTime?: string | null;
  finishedTime?: string | null;
  durationSeconds?: number | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  retryOfJobId?: number | null;
  createTime?: string | null;
}

export interface AccountSyncState {
  accountId: number;
  accountLabel: string;
  authStatus: string;
  syncEnabled?: number | null;
  lastSyncTime?: string | null;
  lastSuccessTime?: string | null;
  lastFailureTime?: string | null;
  lastErrorMessage?: string | null;
  lastStartDate?: string | null;
  lastEndDate?: string | null;
}

export interface SyncOverview {
  lastSuccessTime?: string | null;
  lastFailureTime?: string | null;
  unfinishedCount: number;
  failureCount7d: number;
  hasGarminAccount: boolean;
  accounts: AccountSyncState[];
}

export interface TrainingLoad {
  calendarDate: string;
  trainingStatusPhrase?: string | null;
  acwrPercent?: number | null;
  acwrStatus?: string | null;
  acwrRatio?: number | null;
  acuteLoad?: number | null;
  chronicLoad?: number | null;
  chronicLoadMin?: number | null;
  chronicLoadMax?: number | null;
  loadAerobicLow?: number | null;
  loadAerobicLowTargetMin?: number | null;
  loadAerobicLowTargetMax?: number | null;
  loadAerobicHigh?: number | null;
  loadAerobicHighTargetMin?: number | null;
  loadAerobicHighTargetMax?: number | null;
  loadAnaerobic?: number | null;
  loadAnaerobicTargetMin?: number | null;
  loadAnaerobicTargetMax?: number | null;
  balanceFeedbackPhrase?: string | null;
  vo2maxValue?: number | null;
  fitnessAge?: number | null;
}

export interface FtpRecord {
  effectiveDate: string;
  ftpWatts: number;
  source: string;
}

export interface DailyCheckin {
  calendarDate: string;
  weightKg?: number | null;
  rpe?: number | null;
  note?: string | null;
}

export interface CheckinForm {
  weightKg?: number | null;
  rpe?: number | null;
  note?: string | null;
}

export interface ActivityHrZone {
  zoneNumber: number;
  zoneLowBoundary?: number | null;
  secondsInZone: number;
}

export type GoalType = "POWER" | "MUSCLE" | "ENDURANCE" | "GENERAL" | "OTHER";

export interface TrainingGoal {
  goalType: GoalType;
  goalLabel: string;
  targetDate?: string | null;
  daysToTarget?: number | null;
  weeklySessions?: number | null;
  weeklyMinutes?: number | null;
  description?: string | null;
}

export interface TrainingGoalForm {
  goalType: GoalType;
  targetDate?: string | null;
  weeklySessions?: number | null;
  weeklyMinutes?: number | null;
  description?: string | null;
}
