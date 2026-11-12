import axios from 'axios';
import { ElMessage } from 'element-plus';
import { i18n } from '@/i18n';
import type { ApiResponse } from '@/types/api';
import { clearAuthStorage, getToken } from './token';
import { unwrapApiResponse } from './response';

const http = axios.create({
  baseURL: '/',
  timeout: 30000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const { t } = i18n.global;
    const { response } = error;
    const data = response?.data as ApiResponse<unknown> | undefined;

    if (response?.status === 401) {
      clearAuthStorage();
      ElMessage.error(data?.message || t('common.authExpired'));
      if (window.location.pathname !== '/login') {
        window.location.replace('/login');
      }
      return Promise.reject(error);
    }

    if (response?.status === 403) {
      ElMessage.error(data?.message || t('common.forbidden'));
      return Promise.reject(error);
    }

    const message = data?.message || t('common.requestFailed');
    const traceId = data?.traceId;
    ElMessage.error(traceId ? `${message} (${t('common.traceId')}: ${traceId})` : message);
    return Promise.reject(error);
  },
);

export async function get<T>(url: string, params?: Record<string, unknown>) {
  const response = await http.get<ApiResponse<T>>(url, { params });
  return unwrapApiResponse(response.data);
}

export async function post<T>(url: string, data?: unknown, config?: { timeout?: number }) {
  const response = await http.post<ApiResponse<T>>(url, data, config);
  return unwrapApiResponse(response.data);
}

export async function postForm<T>(url: string, data: FormData, timeout = 120000) {
  const response = await http.post<ApiResponse<T>>(url, data, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout,
  });
  return unwrapApiResponse(response.data);
}

export async function put<T>(url: string, data?: unknown, config?: { params?: Record<string, unknown> }) {
  const response = await http.put<ApiResponse<T>>(url, data, config);
  return unwrapApiResponse(response.data);
}

export async function del<T>(url: string) {
  const response = await http.delete<ApiResponse<T>>(url);
  return unwrapApiResponse(response.data);
}
