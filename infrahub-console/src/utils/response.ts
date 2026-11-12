import type { ApiResponse } from '@/types/api';

export function unwrapApiResponse<T>(payload: ApiResponse<T>) {
  if (payload.code !== 'SUCCESS') {
    throw new Error(payload.message || '请求失败');
  }
  return payload.data;
}
