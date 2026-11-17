<template>
  <el-dialog v-model="visible" :title="t('accountEmail.inviteUser')" width="min(540px, 94vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <p v-if="loading" role="status">{{ t('common.loading') }}</p>
    <el-alert v-else-if="failed" :title="t('common.loadFailed')" type="error" :closable="false" />
    <el-alert v-else-if="!enabled" :title="t('accountEmail.inviteUnavailable')" type="info" :closable="false" />
    <el-form v-else :model="form" label-position="top" :disabled="saving" @submit.prevent="submit">
      <el-form-item :label="t('accountEmail.email')" required>
        <el-input v-model.trim="form.email" inputmode="email" autocomplete="off" maxlength="254" />
      </el-form-item>
      <el-form-item :label="t('access.assignRoles')">
        <el-select v-model="form.roleIds" multiple filterable :aria-label="t('access.assignRoles')">
          <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
        </el-select>
      </el-form-item>
      <div class="invite-help">{{ t('accountEmail.inviteHelp') }}</div>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button v-if="failed" @click="load">{{ t('common.refresh') }}</el-button>
      <el-button v-if="enabled && !loading && !failed" type="primary" :loading="saving"
        :disabled="!emailPattern.test(form.email)" @click="submit">{{ t('accountEmail.sendInvitation') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { authApi } from '@/api/auth';
import { accessApi, type RoleOption } from '@/api/system';
import { emailPattern } from '@/utils/email';

const visible = defineModel<boolean>({ required: true });
const { t } = useI18n();
const roles = ref<RoleOption[]>([]);
const loading = ref(true);
const failed = ref(false);
const enabled = ref(false);
const saving = ref(false);
const form = reactive<{ email: string; roleIds: number[] }>({ email: '', roleIds: [] });

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    enabled.value = (await authApi.mailFeatures()).invitations;
    if (enabled.value) roles.value = (await accessApi.catalog()).roles.filter((role) => role.status === 'ENABLED');
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (!enabled.value || loading.value || failed.value || saving.value || !emailPattern.test(form.email)) return;
  saving.value = true;
  try {
    await authApi.invite(form.email, form.roleIds);
    ElMessage.success(t('accountEmail.invitationSent'));
    visible.value = false;
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.invite-help {
  color: var(--el-text-color-secondary);
}
</style>
