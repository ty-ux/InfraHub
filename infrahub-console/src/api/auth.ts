import type { AuthTokenResponse, CurrentUserResponse } from '@/types/api';
import { get, post } from '@/utils/http';

export interface LoginPayload {
  username: string;
  password: string;
}

export interface EmailSettings {
  email: string | null;
  verified: boolean;
  enabled: boolean;
}

export interface Registration {
  token: string;
  username: string;
  displayName: string;
  password: string;
}

export const authApi = {
  login(payload: LoginPayload) {
    return post<AuthTokenResponse>('/api/auth/login', payload);
  },
  me() {
    return get<CurrentUserResponse>('/api/auth/me');
  },
  changePassword(currentPassword: string, newPassword: string) {
    return post<AuthTokenResponse>('/api/auth/password', { currentPassword, newPassword });
  },
  mailFeatures() {
    return get<{ emailCodes: boolean; invitations: boolean }>('/api/auth/email/features', undefined, { silent: true });
  },
  emailSettings() {
    return get<EmailSettings>('/api/auth/email/settings');
  },
  requestBinding(email: string, password: string) {
    return post<void>('/api/auth/email/bind/request', { email, password });
  },
  confirmBinding(email: string, password: string, code: string) {
    return post<AuthTokenResponse>('/api/auth/email/bind/confirm', { email, password, code });
  },
  requestLoginCode(email: string) {
    return post<void>('/api/auth/email/login/request', { email });
  },
  loginWithCode(email: string, code: string) {
    return post<AuthTokenResponse>('/api/auth/email/login', { email, code });
  },
  invite(email: string, roleIds: number[]) {
    return post<void>('/api/auth/invitations', { email, roleIds });
  },
  register(payload: Registration) {
    return post<void>('/api/auth/register', payload);
  },
};
