import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { CurrentUserResponse } from '@/types/api';
import { authApi } from '@/api/auth';
import { clearAuthStorage, getStoredUser, getToken, setStoredUser, setToken } from '@/utils/token';
import { hasAuthority } from '@/utils/permission';

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken());
  const currentUser = ref<CurrentUserResponse | null>(getStoredUser());
  const initialized = ref(false);

  const authorities = computed(() => currentUser.value?.authorities || []);
  const isLoggedIn = computed(() => Boolean(token.value));

  async function login(username: string, password: string) {
    const result = await authApi.login({ username, password });
    token.value = result.accessToken;
    setToken(result.accessToken);
    await fetchCurrentUser();
  }

  async function fetchCurrentUser() {
    const user = await authApi.me();
    currentUser.value = user;
    setStoredUser(user);
    initialized.value = true;
    return user;
  }

  async function init() {
    if (initialized.value) {
      return;
    }
    if (!token.value) {
      initialized.value = true;
      return;
    }
    try {
      await fetchCurrentUser();
    } catch {
      logout();
      initialized.value = true;
    }
  }

  function logout() {
    token.value = null;
    currentUser.value = null;
    clearAuthStorage();
  }

  function canAccess(required?: string[]) {
    return hasAuthority(authorities.value, required);
  }

  return {
    token,
    currentUser,
    authorities,
    initialized,
    isLoggedIn,
    login,
    init,
    logout,
    canAccess,
  };
});
