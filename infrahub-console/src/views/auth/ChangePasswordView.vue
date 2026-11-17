<template>
  <main class="password-page">
    <el-card shadow="never">
      <h1>{{ t('auth.changePassword') }}</h1>
      <p v-if="required">{{ t('auth.firstPasswordHelp') }}</p>
      <el-form :model="form" label-position="top" :disabled="saving" @submit.prevent="submit">
        <el-form-item :label="t('auth.currentPassword')" required>
          <el-input v-model="form.current" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item :label="t('auth.newPassword')" required>
          <el-input v-model="form.next" type="password" autocomplete="new-password" show-password />
          <span class="password-help">{{ t('auth.passwordRules') }}</span>
        </el-form-item>
        <el-form-item :label="t('auth.confirmPassword')" :error="confirmationError" required>
          <el-input v-model="form.confirm" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <div class="password-actions">
          <el-button :disabled="saving" @click="leave">{{ t(required ? 'common.logout' : 'common.cancel') }}</el-button>
          <el-button type="primary" native-type="submit" :loading="saving" :disabled="!valid">{{ t('common.save') }}</el-button>
        </div>
      </el-form>
    </el-card>
  </main>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/auth';
import { validLocalPassword } from '@/utils/local-password';

const { t } = useI18n();
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const saving = ref(false);
const form = reactive({ current: '', next: '', confirm: '' });
const required = computed(() => auth.currentUser?.mustChangePassword === true);
const confirmationError = computed(() => form.confirm && form.next !== form.confirm ? t('auth.passwordsDiffer') : '');
const valid = computed(() => form.current && validLocalPassword(form.next) && form.next !== form.current && form.next === form.confirm);
const destination = computed(() => {
  const path = route.query.redirect;
  return typeof path === 'string' && /^\/(?![/\\])/.test(path)
    && !['/login', '/account/password'].includes(router.resolve(path).path) ? path : '/workspace';
});

async function submit() {
  if (!valid.value || saving.value) return;
  saving.value = true;
  try {
    await auth.changePassword(form.current, form.next);
    ElMessage.success(t('auth.passwordChanged'));
    await router.replace(destination.value);
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    saving.value = false;
  }
}

function leave() {
  if (required.value) {
    auth.logout();
    void router.replace('/login');
  } else {
    void router.replace(destination.value);
  }
}
</script>

<style scoped>
.password-page {
  box-sizing: border-box;
  display: grid;
  min-height: 100dvh;
  align-content: center;
  justify-items: center;
  padding: var(--space-lg);
}
.password-page :deep(.el-card) {
  width: min(100%, 450px);
}
h1 {
  margin: 0 0 var(--space-md);
}
p,
.password-help {
  color: var(--el-text-color-secondary);
}
.password-help {
  font-size: var(--text-xs);
}
.password-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xs);
}
</style>
