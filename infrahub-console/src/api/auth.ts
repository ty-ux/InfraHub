import type { AuthTokenResponse, CurrentUserResponse } from '@/types/api';
import { get, post } from '@/utils/http';

export interface LoginPayload {
  username: string;
  password: string;
}

export const authApi = {
  login(payload: LoginPayload) {
    return post<AuthTokenResponse>('/api/auth/login', payload);
  },
  me() {
    return get<CurrentUserResponse>('/api/auth/me');
  },
};
