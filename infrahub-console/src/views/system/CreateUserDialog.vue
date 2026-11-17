<template>
  <el-dialog v-model="visible" :title="t('auth.createUser')" width="min(560px, 94vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form :model="form" label-position="top" :disabled="saving" @submit.prevent="submit">
      <el-form-item :label="t('auth.username')" required>
        <el-input v-model.trim="form.username" autocomplete="off" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('fields.displayName')" required>
        <el-input v-model.trim="form.displayName" maxlength="128" />
      </el-form-item>
      <el-form-item :label="t('auth.initialPassword')" required>
        <el-input v-model="form.initialPassword" type="password" autocomplete="new-password" show-password />
        <div class="field-help">{{ t('auth.passwordRules') }} {{ t('auth.initialPasswordHelp') }}</div>
      </el-form-item>
      <el-form-item :label="t('access.assignRoles')">
        <el-select v-model="form.roleIds" :loading="loading" :disabled="failed" multiple filterable
          :aria-label="t('access.assignRoles')">
          <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id" />
        </el-select>
        <el-button v-if="failed" link type="primary" @click="loadRoles">{{ t('common.refresh') }}</el-button>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!valid || loading || failed" @click="submit">{{ t('common.create') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from 'vue-i18n';
import { accessApi, type NewUser, type RoleOption } from '@/api/system';
import { validLocalPassword } from '@/utils/local-password';

const visible = defineModel<boolean>({ required: true });
const emit = defineEmits<{ created: [] }>();
const { t } = useI18n();
const roles = ref<RoleOption[]>([]);
const saving = ref(false);
const loading = ref(false);
const failed = ref(false);
const form = reactive<NewUser>({ username: '', displayName: '', initialPassword: '', roleIds: [] });
const valid = computed(() => /^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(form.username)
  && form.displayName && validLocalPassword(form.initialPassword));

async function loadRoles() {
  loading.value = true;
  failed.value = false;
  try {
    roles.value = (await accessApi.catalog()).roles.filter((role) => role.status === 'ENABLED');
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (!valid.value || saving.value || loading.value || failed.value) return;
  saving.value = true;
  try {
    await accessApi.createUser(form);
    form.initialPassword = '';
    visible.value = false;
    ElMessage.success(t('auth.userCreated'));
    emit('created');
  } catch {
    // Request errors are shown by the HTTP client.
  } finally {
    saving.value = false;
  }
}

onMounted(loadRoles);
</script>

<style scoped>
.field-help {
  color: var(--el-text-color-secondary);
  font-size: var(--text-xs);
}
</style>
