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
