<template>
  <CrudPage ref="table" :key="pageKey" :config="config">
    <template v-if="pageKey === 'systemRoles'" #toolbar-extra>
      <el-button type="primary" @click="createVisible = true">{{ t('access.createRole') }}</el-button>
    </template>
    <template #row-actions="{ row }">
      <el-button link type="primary" @click="openEditor(row)">
        {{ t(pageKey === 'systemUsers' ? 'access.assignRoles' : 'access.editGrants') }}
      </el-button>
    </template>
  </CrudPage>

  <el-dialog v-model="editorVisible" :title="editorTitle" width="min(820px, 94vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <div v-if="loading" role="status">{{ t('common.loading') }}</div>
    <el-alert v-else-if="!loaded" :title="t('common.loadFailed')" type="error" :closable="false" show-icon />
    <div v-else-if="managingUsers" class="editor-section">
      <p>{{ t('access.unionHelp') }}</p>
      <el-checkbox-group v-model="userRoleIds" class="option-list" :disabled="saving" :aria-label="t('access.assignRoles')">
        <el-checkbox v-for="role in catalog?.roles" :key="role.id" :value="role.id">
          {{ role.roleName }} <span class="muted">{{ role.roleCode }}</span>
          <span v-if="role.status !== 'ENABLED'" class="muted"> · {{ t('common.disabled') }}</span>
        </el-checkbox>
      </el-checkbox-group>
    </div>
    <template v-else>
      <el-alert v-if="scopeConflict" :title="t('access.scopeConflict')" type="warning" :closable="false" show-icon />
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="t('access.pages')" name="pages">
          <p>{{ t('access.pagesHelp') }}</p>
          <el-checkbox-group v-model="grants.permissionIds" class="option-list" :disabled="saving" :aria-label="t('access.pages')">
            <el-checkbox v-for="permission in pagePermissions" :key="permission.id" :value="permission.id" :disabled="blocked(permission.permCode)">
              <span>{{ permission.permName }}</span>
              <span v-if="!isScoped(permission.permCode)" class="muted"> · {{ t('access.global') }}</span>
              <span v-if="permissionPages(permission.permCode)" class="permission-pages">{{ permissionPages(permission.permCode) }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-tab-pane>
        <el-tab-pane :label="t('access.actions')" name="actions">
          <p>{{ t('access.actionsHelp') }}</p>
          <el-checkbox-group v-model="grants.permissionIds" class="option-list" :disabled="saving" :aria-label="t('access.actions')">
            <el-checkbox v-for="permission in actionPermissions" :key="permission.id" :value="permission.id" :disabled="blocked(permission.permCode)">
              <span>{{ permission.permName }}</span>
              <span v-if="!isScoped(permission.permCode)" class="muted"> · {{ t('access.global') }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-tab-pane>
        <el-tab-pane :label="t('access.applications')" name="applications">
          <div class="editor-section">
            <el-radio-group v-model="grants.appScope" :disabled="saving" :aria-label="t('access.applications')">
              <el-radio-button value="ALL">{{ t('access.allApps') }}</el-radio-button>
              <el-radio-button value="SELECTED">{{ t('access.selectedApps') }}</el-radio-button>
            </el-radio-group>
            <p v-if="grants.appScope === 'SELECTED'">{{ t('access.scopeHelp') }}</p>
            <el-select v-if="grants.appScope === 'SELECTED'" v-model="grants.appIds" multiple filterable
              :disabled="saving" :placeholder="t('access.chooseApps')" :aria-label="t('access.chooseApps')">
              <el-option v-for="app in catalog?.apps" :key="app.id" :value="app.id" :label="`${app.appName} (${app.appCode})`" />
            </el-select>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
    <template #footer>
      <el-button :disabled="saving" @click="editorVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="!loaded || (!managingUsers && scopeConflict)" @click="save">{{ t('common.save') }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="createVisible" :title="t('access.createRole')" width="min(480px, 94vw)"
    :close-on-click-modal="!creating" :close-on-press-escape="!creating" :show-close="!creating">
    <el-form label-position="top" :disabled="creating" @submit.prevent="create">
      <el-form-item :label="t('fields.roleCode')" required>
        <el-input v-model.trim="newRoleCode" maxlength="64" placeholder="PROJECT_OPERATOR" />
      </el-form-item>
      <el-form-item :label="t('fields.roleName')" required>
        <el-input v-model.trim="newRoleName" maxlength="128" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="creating" @click="createVisible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="creating" :disabled="!validRole" @click="create">{{ t('common.create') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { accessApi, type AccessCatalog, type RoleGrants } from '@/api/system';
import CrudPage from '@/components/page/CrudPage.vue';
import { useAuthStore } from '@/store/auth';
import { getPageConfig } from '@/views/configs/page-configs';
import { menuRoutes } from '@/router/routes';

const route = useRoute();
const { t } = useI18n();
const auth = useAuthStore();
const table = ref<InstanceType<typeof CrudPage>>();
const pageKey = computed(() => route.path === '/system/users' ? 'systemUsers' : 'systemRoles');
const config = computed(() => ({ ...getPageConfig(pageKey.value), description: t(pageKey.value === 'systemUsers' ? 'access.usersHelp' : 'access.rolesHelp') }));
const managingUsers = ref(false);
const editorVisible = ref(false);
const editorTitle = ref('');
const entityId = ref(0);
const loading = ref(false);
const loaded = ref(false);
const saving = ref(false);
const catalog = ref<AccessCatalog>();
const grants = ref<RoleGrants>({ appScope: 'SELECTED', permissionIds: [], appIds: [] });
const userRoleIds = ref<number[]>([]);
const activeTab = ref('pages');
const createVisible = ref(false);
const creating = ref(false);
const newRoleCode = ref('');
const newRoleName = ref('');
const validRole = computed(() => /^[A-Z][A-Z0-9_]*$/.test(newRoleCode.value) && newRoleName.value.length > 0);

const supportedPermissions = computed(() => catalog.value?.permissions.filter((permission) => permission.permCode !== 'RESOURCE_ALLOCATE'
  || grants.value.permissionIds.includes(permission.id)) ?? []);
const pagePermissions = computed(() => supportedPermissions.value.filter((permission) => permission.action === 'VIEW'));
const actionPermissions = computed(() => supportedPermissions.value.filter((permission) => permission.action !== 'VIEW'));
const scopeConflict = computed(() => grants.value.appScope === 'SELECTED' && (catalog.value?.permissions ?? [])
  .some((permission) => grants.value.permissionIds.includes(permission.id) && !isScoped(permission.permCode)));

function isScoped(code: string) {
  return catalog.value?.scopedPermissions.includes(code) ?? false;
}

function permissionPages(code: string) {
  return [...menuRoutes.values()]
    .filter(({ meta }) => meta.authorities?.includes(code))
    .map(({ meta }) => t(meta.title)).join('、');
}

function blocked(code: string) {
  const item = catalog.value?.permissions.find((permission) => permission.permCode === code);
  return grants.value.appScope === 'SELECTED' && !isScoped(code) && !grants.value.permissionIds.includes(item?.id ?? 0);
}

async function openEditor(row: Record<string, unknown>) {
  if (loading.value || saving.value) return;
  managingUsers.value = pageKey.value === 'systemUsers';
  entityId.value = Number(row.id);
  const title = t(managingUsers.value ? 'access.assignRoles' : 'access.editGrants');
  editorTitle.value = `${title} · ${row.displayName ?? row.username ?? row.roleName}`;
  editorVisible.value = true;
  loaded.value = false;
  loading.value = true;
  activeTab.value = 'pages';
  try {
    if (managingUsers.value) {
      [catalog.value, userRoleIds.value] = await Promise.all([accessApi.catalog(), accessApi.userRoles(entityId.value)]);
    } else {
      [catalog.value, grants.value] = await Promise.all([accessApi.catalog(), accessApi.roleGrants(entityId.value)]);
    }
    loaded.value = true;
  } catch {
    loaded.value = false;
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!loaded.value || saving.value || (!managingUsers.value && scopeConflict.value)) return;
  saving.value = true;
  try {
    if (managingUsers.value) {
      await accessApi.saveUserRoles(entityId.value, userRoleIds.value);
    } else {
      await accessApi.saveRoleGrants(entityId.value, grants.value);
    }
    editorVisible.value = false;
    ElMessage.success(t('access.saved'));
    void auth.fetchCurrentUser().catch(() => {});
  } catch {
    // The shared HTTP client displays the server's validation message.
  } finally {
    saving.value = false;
  }
}

async function create() {
  if (!validRole.value || creating.value) return;
  creating.value = true;
  try {
    const roleName = newRoleName.value;
    const id = await accessApi.createRole(newRoleCode.value, roleName);
    createVisible.value = false;
    newRoleCode.value = newRoleName.value = '';
    void table.value?.fetchData().catch(() => {});
    if (pageKey.value === 'systemRoles') await openEditor({ id, roleName });
  } catch {
    // Keep the form open so the administrator can correct the request.
  } finally {
    creating.value = false;
  }
}
</script>

<style scoped>
.editor-section {
  display: grid;
  gap: var(--space-sm);
}

.editor-section p {
  margin: 0;
}

.option-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 275px), 1fr));
  gap: var(--space-xs) var(--space-md);
  max-height: 45vh;
  overflow: auto;
}

.option-list :deep(.el-checkbox) {
  margin: 0;
  height: auto;
  min-height: 32px;
  white-space: normal;
}

.muted {
  color: var(--color-ink-2);
}

.permission-pages {
  display: block;
  margin-top: var(--space-2xs);
  color: var(--color-ink-2);
  font-size: var(--text-xs);
}
</style>
