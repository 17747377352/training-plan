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
