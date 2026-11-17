<template>
  <AccountFormLayout :title="t('accountEmail.register')">
    <el-alert v-if="!token" :title="t('accountEmail.invitationRequired')" type="warning" :closable="false" />
    <el-form v-else :model="form" :disabled="saving" label-position="top" @submit.prevent="submit">
      <el-form-item :label="t('auth.username')" required>
        <el-input v-model.trim="form.username" autocomplete="username" maxlength="64" />
        <span class="account-help">{{ t('accountEmail.usernameRules') }}</span>
      </el-form-item>
      <el-form-item :label="t('fields.displayName')" required>
        <el-input v-model.trim="form.displayName" maxlength="128" />
      </el-form-item>
      <el-form-item :label="t('auth.newPassword')" required>
        <el-input v-model="form.password" type="password" autocomplete="new-password" show-password />
        <span class="account-help">{{ t('auth.passwordRules') }}</span>
      </el-form-item>
      <el-form-item :label="t('auth.confirmPassword')" :error="confirmationError" required>
        <el-input v-model="confirmation" type="password" autocomplete="new-password" show-password />
      </el-form-item>
      <div class="account-actions">
        <el-button type="primary" native-type="submit" :disabled="!valid" :loading="saving">
          {{ t('accountEmail.activateAccount') }}
        </el-button>
      </div>
    </el-form>
    <div class="account-actions">
      <router-link to="/login">{{ t('accountEmail.backToLogin') }}</router-link>
    </div>
  </AccountFormLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { authApi } from '@/api/auth';
import { validLocalPassword } from '@/utils/local-password';
import AccountFormLayout from './AccountFormLayout.vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const form = reactive({ username: '', displayName: '', password: '' });
const confirmation = ref('');
const saving = ref(false);
const token = computed(() => {
  const value = new URLSearchParams(route.hash.slice(1)).get('token') ?? '';
  return /^[A-Za-z0-9_-]{43}$/.test(value) ? value : '';
});
const confirmationError = computed(() => confirmation.value && form.password !== confirmation.value ? t('auth.passwordsDiffer') : '');
const valid = computed(() => /^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(form.username) && form.displayName
  && validLocalPassword(form.password) && form.password === confirmation.value);

async function submit() {
  if (!token.value || !valid.value || saving.value) return;
  saving.value = true;
  try {
    await authApi.register({ ...form, token: token.value });
    form.password = '';
    confirmation.value = '';
    ElMessage.success(t('accountEmail.accountActivated'));
    await router.replace('/login');
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    saving.value = false;
  }
}
</script>
