export interface ApiResponse<T> {
  code: string;
  message: string;
  traceId: string;
  data: T;
}

export interface PageQuery {
  pageNum: number;
  pageSize: number;
  [key: string]: unknown;
}

export interface PageResponse<T> {
  pageNum: number;
  pageSize: number;
  total: number;
  records: T[];
}

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  expireSeconds: number;
}

export interface CurrentUserResponse {
  userId: number;
  username: string;
  displayName: string;
  authorities: string[];
  allAppPermissions?: string[];
  applicationPermissions?: Record<string, number[]>;
}
