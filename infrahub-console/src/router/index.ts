import { createRouter, createWebHistory } from 'vue-router';
import { routes } from './routes';
import { useAuthStore } from '@/store/auth';

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  const requiresAuth = to.path !== '/login';

  if (!authStore.initialized) {
    await authStore.init();
  }

  if (!requiresAuth) {
    if (authStore.isLoggedIn) {
      return '/workspace';
    }
    return true;
  }

  if (!authStore.isLoggedIn) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    };
  }

  if (!authStore.canAccess(to.meta.authorities as string[] | undefined)) {
    return '/workspace';
  }

  return true;
});

export default router;
