<template>
  <AccountFormLayout :title="t('accountEmail.settings')">
    <p v-if="loading" role="status">{{ t('common.loading') }}</p>
    <el-alert v-else-if="!settings" :title="t('common.loadFailed')" type="error" :closable="false" />
    <template v-else>
      <div class="current-email">
        <span class="account-help">{{ t('accountEmail.currentEmail') }}</span>
        <strong v-if="settings.email">{{ settings.email }}</strong>
        <el-tag :type="settings.verified ? 'success' : 'info'" size="small">
          {{ t(settings.verified ? 'accountEmail.verified' : settings.email ? 'accountEmail.unverified' : 'accountEmail.unbound') }}
        </el-tag>
      </div>
      <el-alert v-if="!settings.enabled" :title="t('accountEmail.mailUnavailable')" type="info" :closable="false" />
      <el-form v-else :model="form" :disabled="saving || sending" label-position="top" @submit.prevent="confirm">
        <el-form-item :label="t('accountEmail.newEmail')" required>
          <el-input v-model.trim="form.email" inputmode="email" autocomplete="email" maxlength="254" @input="form.code = ''" />
        </el-form-item>
        <el-form-item :label="t('auth.currentPassword')" required>
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item :label="t('accountEmail.code')" required>
          <div class="code-field">
            <el-input v-model.trim="form.code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" />
            <el-button :loading="sending" :disabled="!validTarget || form.email === sentTo" @click="sendCode">
              {{ t('accountEmail.sendCode') }}
            </el-button>
          </div>
        </el-form-item>
        <div class="account-actions">
          <el-button @click="leave">{{ t('common.cancel') }}</el-button>
          <el-button type="primary" native-type="submit" :loading="saving" :disabled="!valid">
            {{ t('accountEmail.verifyAndBind') }}
          </el-button>
        </div>
      </el-form>
    </template>
    <div v-if="!loading && !settings?.enabled" class="account-actions">
      <el-button @click="leave">{{ t('common.cancel') }}</el-button>
    </div>
  </AccountFormLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { authApi, type EmailSettings } from '@/api/auth';
import { useAuthStore } from '@/store/auth';
import { emailCodePattern, emailPattern } from '@/utils/email';
import AccountFormLayout from './AccountFormLayout.vue';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const settings = ref<EmailSettings>();
const loading = ref(true);
const saving = ref(false);
const sending = ref(false);
const sentTo = ref('');
let resendTimer: ReturnType<typeof setTimeout> | undefined;
const form = reactive({ email: '', password: '', code: '' });
const validTarget = computed(() => emailPattern.test(form.email) && Boolean(form.password)
  && (!settings.value?.verified || form.email !== settings.value.email));
const valid = computed(() => validTarget.value && emailCodePattern.test(form.code));

onMounted(async () => {
  try {
    settings.value = await authApi.emailSettings();
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    loading.value = false;
  }
});
onUnmounted(() => clearTimeout(resendTimer));

async function sendCode() {
  if (!validTarget.value || sending.value || saving.value || form.email === sentTo.value) return;
  const email = form.email;
  sending.value = true;
  try {
    await authApi.requestBinding(email, form.password);
    sentTo.value = email;
    clearTimeout(resendTimer);
    resendTimer = setTimeout(() => { sentTo.value = ''; }, 60_000);
    ElMessage.success(t('accountEmail.bindingCodeSent'));
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    sending.value = false;
  }
}

async function confirm() {
  if (!valid.value || saving.value || sending.value) return;
  saving.value = true;
  try {
    await auth.confirmBinding(form.email, form.password, form.code);
    form.password = '';
    form.code = '';
    ElMessage.success(t('accountEmail.bound'));
    leave();
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    saving.value = false;
  }
}

function leave() {
  const path = route.query.redirect;
  const destination = typeof path === 'string' && /^\/(?![/\\])/.test(path) && router.resolve(path).path !== '/account/email'
    ? path : '/workspace';
  void router.replace(destination);
}
</script>

<style scoped>
.current-email {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
  overflow-wrap: anywhere;
}
.code-field {
  display: flex;
  width: 100%;
  gap: var(--space-xs);
}
</style>
