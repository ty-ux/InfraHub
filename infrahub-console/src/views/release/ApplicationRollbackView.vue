<template>
  <section class="application-rollback">
    <header class="page-toolbar">
      <div class="page-title"><h2>{{ t('pipelineDelivery.rollbackTitle') }}</h2></div>
      <router-link v-if="canView" :to="historyTarget"><el-button>{{ t('pipelineDelivery.rollbackHistory') }}</el-button></router-link>
    </header>

    <el-card v-loading="loadingCatalog" shadow="never">
      <el-alert v-if="catalogFailed" :title="t('pipelineDelivery.loadFailed')" type="error" :closable="false" />
      <el-form label-position="top" class="scope-form" :disabled="submitting">
        <el-form-item :label="t('fields.appName')">
          <el-select v-model="appId" filterable @change="onAppChange">
            <el-option v-for="row in apps" :key="row.id" :value="row.id" :label="row.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('fields.envName')">
          <el-select v-model="envId" filterable>
            <el-option v-for="row in envs" :key="row.id" :value="row.id" :label="row.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('fields.serviceName')">
          <el-select v-model="serviceId" filterable :disabled="!appId">
            <el-option v-for="row in appServices" :key="row.id" :value="row.id" :label="row.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('pipelineDelivery.targetVersion')">
          <el-select v-model="versionId" filterable :disabled="!serviceId">
            <el-option v-for="row in appVersions" :key="row.id" :value="row.id" :label="row.name" />
          </el-select>
        </el-form-item>
      </el-form>

      <div v-if="envId && serviceId" class="instance-panel" v-loading="loadingInstances">
        <div class="instance-heading">
          <h3>{{ t('pipelineDelivery.rollbackInstances') }}</h3>
          <el-checkbox :model-value="allSelected" :indeterminate="!allSelected && instanceIds.length > 0" :disabled="!instances.length || submitting" @change="selectAll">
            {{ t('pipelineDelivery.selectAll') }}
          </el-checkbox>
        </div>
        <el-alert v-if="instanceLoadFailed" :title="t('pipelineDelivery.loadFailed')" type="error" :closable="false" />
        <p v-else-if="!instances.length && !loadingInstances" class="empty-instances">{{ t('pipelineDelivery.noInstances') }}</p>
        <el-checkbox-group v-else v-model="instanceIds" :disabled="submitting" class="instance-list">
          <div v-for="instance in instances" :key="instance.id" class="instance-row">
            <el-checkbox :value="instance.id" :label="instance.instanceName || `#${instance.id}`" />
            <span class="instance-version">{{ versionNames.get(instance.versionId ?? 0) ?? (instance.versionId ? `#${instance.versionId}` : '—') }}</span>
          </div>
        </el-checkbox-group>
      </div>
      <div class="rollback-actions">
        <el-button v-if="canExecute" type="primary" :loading="submitting" :disabled="!canSubmit" @click="confirmRollback">
          {{ t('pipelineDelivery.rollbackAction') }}
        </el-button>
      </div>
    </el-card>

    <el-card v-if="result" shadow="never" class="result-card">
      <div class="instance-heading">
        <strong>{{ t('pipelineDelivery.rollbackResult', { id: result.rollbackId }) }}</strong>
        <el-tag :type="result.status === 'SUCCESS' ? 'success' : result.status === 'FAILED' ? 'danger' : 'warning'">
          {{ t(`releaseStatus.${result.status.toLowerCase()}`) }}
        </el-tag>
      </div>
      <p v-for="check in failedChecks" :key="check.instanceId">#{{ check.instanceId }} · {{ check.errorMessage }}</p>
      <el-button v-if="canView" link type="primary" @click="openRecords">{{ t('rollbackOrders.actionDeployRecords') }}</el-button>
      <router-link v-if="result.approvalId" to="/system/approvals"><el-button link>{{ t('pipelineDelivery.approvalPending') }}</el-button></router-link>
    </el-card>

    <DeployRecordsDialog v-model="recordsVisible" :records="records" :loading="recordsLoading" :error="recordsError" @retry="openRecords" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { getApplicationRollbackOptions, getApplicationRollbackInstances, getRollbackDeployRecords, rollbackApplication,
  type ApplicationRollbackResult, type RollbackChoice } from '@/api/release';
import type { RuntimeServiceInstance } from '@/api/runtime';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import { useAuthStore } from '@/store/auth';

const { t } = useI18n();
const auth = useAuthStore();
const route = useRoute();
const canView = computed(() => auth.canAccess(['ROLLBACK_VIEW', 'SYSTEM_ADMIN']));
const canExecute = computed(() => auth.canAccess(['ROLLBACK_EXECUTE', 'SYSTEM_ADMIN']));
const apps = ref<RollbackChoice[]>([]);
const envs = ref<RollbackChoice[]>([]);
const services = ref<RollbackChoice[]>([]);
const versions = ref<RollbackChoice[]>([]);
const appId = ref<number>();
const envId = ref<number>();
const serviceId = ref<number>();
const versionId = ref<number>();
const instances = ref<RuntimeServiceInstance[]>([]);
const instanceIds = ref<number[]>([]);
const loadingCatalog = ref(false);
const catalogFailed = ref(false);
const loadingInstances = ref(false);
const instanceLoadFailed = ref(false);
const submitting = ref(false);
const result = ref<ApplicationRollbackResult>();
const recordsVisible = ref(false);
const records = ref<Record<string, unknown>[]>([]);
const recordsLoading = ref(false);
const recordsError = ref('');
const appServices = computed(() => services.value.filter(row => row.appId === appId.value));
const appVersions = computed(() => versions.value.filter(row => row.appId === appId.value
  && (row.serviceId == null || row.serviceId === serviceId.value)));
const versionNames = computed(() => new Map(versions.value.map(row => [row.id, row.name])));
const allSelected = computed(() => instances.value.length > 0 && instanceIds.value.length === instances.value.length);
const canSubmit = computed(() => !loadingInstances.value && !!(appId.value && envId.value && serviceId.value && versionId.value && instanceIds.value.length));
const failedChecks = computed(() => result.value?.prechecks.filter(check => !check.success) ?? []);
const historyTarget = computed(() => ({ path: '/release/rollbacks', query: {
  history: '1',
  appId: appId.value, envId: envId.value, serviceId: serviceId.value,
} }));

function onAppChange() {
  versionId.value = undefined;
  serviceId.value = appServices.value.length === 1 ? appServices.value[0].id : undefined;
}

function selectAll(value: unknown) {
  instanceIds.value = value ? instances.value.map(instance => instance.id) : [];
}

onMounted(async () => {
  loadingCatalog.value = true;
  try {
    const options = await getApplicationRollbackOptions();
    apps.value = options.apps;
    envs.value = options.envs;
    services.value = options.services;
    versions.value = options.versions;
    appId.value = apps.value.find(row => String(row.id) === route.query.appId)?.id;
    envId.value = envs.value.find(row => String(row.id) === route.query.envId)?.id;
    const selected = appServices.value.find(row => String(row.id) === route.query.serviceId);
    if (selected) serviceId.value = selected.id;
    else onAppChange();
  } catch {
    catalogFailed.value = true;
  } finally {
    loadingCatalog.value = false;
  }
});

watch([envId, serviceId], async ([env, service], _previous, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  instances.value = [];
  instanceIds.value = [];
  instanceLoadFailed.value = false;
  loadingInstances.value = !!(env && service);
  if (!env || !service) return;
  try {
    const rows = await getApplicationRollbackInstances(service, env);
    if (active) instances.value = rows;
  } catch {
    if (active) instanceLoadFailed.value = true;
  } finally {
    if (active) loadingInstances.value = false;
  }
});

watch(serviceId, () => { versionId.value = undefined; });

async function confirmRollback() {
  if (!appId.value || !envId.value || !serviceId.value || !versionId.value || !canSubmit.value || submitting.value) return;
  const payload = { appId: appId.value, envId: envId.value, serviceId: serviceId.value, versionId: versionId.value, instanceIds: [...instanceIds.value] };
  try {
    await ElMessageBox.confirm(t('pipelineDelivery.rollbackConfirm', {
      count: payload.instanceIds.length, version: versionNames.value.get(payload.versionId),
    }), t('pipelineDelivery.rollbackAction'), { type: 'warning', confirmButtonText: t('pipelineDelivery.rollbackAction'), cancelButtonText: t('common.cancel') });
  } catch { return; }
  submitting.value = true;
  result.value = undefined;
  try {
    result.value = await rollbackApplication(payload);
    if (envId.value === payload.envId && serviceId.value === payload.serviceId) {
      instances.value = await getApplicationRollbackInstances(payload.serviceId, payload.envId);
    }
  } catch {
    // The HTTP client displays request errors.
  } finally { submitting.value = false; }
}

async function openRecords() {
  if (!result.value) return;
  recordsVisible.value = true;
  recordsLoading.value = true;
  records.value = [];
  recordsError.value = '';
  try { records.value = await getRollbackDeployRecords(result.value.rollbackId); }
  catch { recordsError.value = t('pipelineDelivery.loadFailed'); }
  finally { recordsLoading.value = false; }
}
</script>

<style scoped>
.scope-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 0 16px; }
.instance-panel { border-top: 1px solid var(--el-border-color-light); padding-top: 12px; }
.instance-heading, .instance-row { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.instance-heading h3 { margin: 0; font-size: 14px; }
.instance-list { display: grid; }
.instance-row { border-bottom: 1px solid var(--el-border-color-lighter); }
.instance-version, .empty-instances { color: var(--el-text-color-secondary); }
.rollback-actions { display: flex; justify-content: flex-end; margin-top: 16px; }
.result-card { margin-top: 16px; }
</style>
