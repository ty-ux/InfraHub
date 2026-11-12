import type { PageResponse } from '@/types/api';
import { del, get, post, put } from '@/utils/http';

export function createCrudApi<T extends Record<string, unknown>>(baseUrl: string) {
  return {
    page: (params: Record<string, unknown>) => get<PageResponse<T>>(baseUrl, params),
    create: (payload: T) => post<boolean>(baseUrl, payload),
    update: (id: number | string, payload: T) => put<boolean>(`${baseUrl}/${id}`, payload),
    remove: (id: number | string) => del<boolean>(`${baseUrl}/${id}`),
  };
}

export function createReadonlyApi<T extends Record<string, unknown>>(baseUrl: string) {
  return {
    page: (params: Record<string, unknown>) => get<PageResponse<T>>(baseUrl, params),
  };
}
