<template>
  <div class="executor-configs">
    <div class="page-header">
      <h2 class="page-title">{{ t('executorConfig.title') }}</h2>
    </div>

    <!-- Filter Card -->
    <el-card class="filter-card">
      <el-form :inline="true" class="filter-form">
        <el-form-item :label="t('executorConfig.executorType')">
          <el-select
            v-model="filters.executorType"
            clearable
            :placeholder="t('executorConfig.allTypes')"
            style="width: 200px"
          >
            <el-option :label="t('executorConfig.typeEnum.SSH')" value="SSH" />
            <el-option :label="t('executorConfig.typeEnum.DOCKER')" value="DOCKER" />
            <el-option :label="t('executorConfig.typeEnum.KUBERNETES')" value="KUBERNETES" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('executorConfig.env')">
          <el-select
            v-model="filters.envId"
            clearable
            :placeholder="t('executorConfig.allEnvironments')"
            style="width: 200px"
          >
            <el-option v-for="env in envOptions" :key="env.id" :label="env.name" :value="env.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button @click="loadData">{{ t('executorConfig.search') }}</el-button>
          <el-button link class="filter-reset" @click="handleResetFilters">
            {{ t('common.resetFilters') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Table Card -->
    <el-card class="table-card">
      <template #header>
        <div class="table-header">
          <el-button type="primary" @click="openCreateDialog">{{ t('common.create') }}</el-button>
          <el-button class="table-refresh" @click="loadData">{{ t('common.refresh') }}</el-button>
        </div>
      </template>

      <!-- A failed read is a read failure: it never takes the rows away and never reads as no data -->
      <el-alert
        v-if="readFailed"
        class="ih-error-alert ih-error-alert--danger read-error"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>{{ t('common.readFailed') }}</template>
        <template #default>
          <div class="read-error-body">
            <span class="read-error-hint">{{ t('common.readFailedHint') }}</span>
            <el-button size="small" type="primary" plain @click="loadData">{{ t('common.retry') }}</el-button>
          </div>
        </template>
      </el-alert>

      <el-table :data="filteredConfigs" v-loading="loading" style="width: 100%">
        <el-table-column prop="executorName" :label="t('executorConfig.executorName')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="executorType" :label="t('executorConfig.executorType')" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag :type="getTypeTagType(row.executorType)" size="small">
              {{ t(`executorConfig.typeEnum.${row.executorType}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="envName" :label="t('executorConfig.env')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ envDisplay(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="enabled" :label="t('executorConfig.enabled')" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="isWritePending(row.id)"
              :aria-label="t('executorConfig.enabledSwitchAria', { name: row.executorName })"
              @change="handleSwitchChange(row, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('executorConfig.configPayload')" min-width="160">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openPayloadDialog(row)">
              {{ t('executorConfig.viewPayload') }}
            </el-button>
          </template>
        </el-table-column>
        <!-- The id stays queryable without competing with the name, the type and the environment -->
        <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
        <el-table-column :label="t('common.actions')" width="170" fixed="right" align="center">
          <template #default="{ row }">
            <div class="ih-table-actions">
              <el-tooltip
                :disabled="!isWritePending(row.id)"
                :content="t('executorConfig.writePendingHint')"
                placement="top"
              >
                <span>
                  <el-button
                    type="primary"
                    size="small"
                    class="action-primary"
                    :disabled="isWritePending(row.id)"
                    @click="openEditDialog(row)"
                  >
                    {{ t('common.edit') }}
                  </el-button>
                </span>
              </el-tooltip>
              <el-tooltip :disabled="!disableHint(row)" :content="disableHint(row)" placement="top">
                <span>
                  <el-button
                    link
                    type="warning"
                    size="small"
                    :disabled="!row.enabled || isWritePending(row.id)"
                    @click="handleDelete(row)"
                  >
                    {{ t('common.disable') }}
                  </el-button>
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <!-- A failed read says so above; it is never dressed up as an empty list -->
          <div v-if="!readFailed" class="ih-empty-state empty-state">
            <template v-if="hasActiveFilters">
              <p class="empty-title">{{ t('common.noMatch') }}</p>
              <p class="empty-hint">{{ t('common.noMatchHint') }}</p>
              <el-button size="small" @click="handleResetFilters">{{ t('common.clearFilters') }}</el-button>
            </template>
            <template v-else>
              <p class="empty-title">{{ t('common.noData') }}</p>
              <p class="empty-hint">{{ t('common.noDataHint') }}</p>
              <el-button size="small" @click="loadData">{{ t('common.refresh') }}</el-button>
            </template>
          </div>
        </template>
      </el-table>
    </el-card>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('executorConfig.editConfig') : t('executorConfig.createConfig')"
      width="640px"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      :before-close="handleDialogBeforeClose"
      @closed="resetForm"
    >
      <el-form :model="currentConfig" :rules="formRules" ref="formRef" label-width="160px">
        <el-form-item :label="t('executorConfig.executorName')" prop="executorName">
          <el-input v-model="currentConfig.executorName" :disabled="submitting" :placeholder="t('common.pleaseEnter', { label: t('executorConfig.executorName') })" />
        </el-form-item>
        <el-form-item :label="t('executorConfig.executorType')" prop="executorType">
          <el-select :model-value="currentConfig.executorType" :disabled="submitting" style="width: 100%" @change="handleExecutorTypeChange">
            <el-option :label="t('executorConfig.typeEnum.SSH')" value="SSH" />
            <el-option :label="t('executorConfig.typeEnum.DOCKER')" value="DOCKER" />
            <el-option :label="t('executorConfig.typeEnum.KUBERNETES')" value="KUBERNETES" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('executorConfig.env')" prop="envId">
          <el-select v-model="currentConfig.envId" :disabled="submitting" style="width: 100%">
            <el-option v-for="env in envOptions" :key="env.id" :label="env.name" :value="env.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('executorConfig.enabled')">
          <el-switch v-model="currentConfig.enabled" :disabled="submitting" :aria-label="t('executorConfig.enabled')" />
        </el-form-item>

        <div class="payload-section">
          <div class="payload-label">{{ t('executorConfig.configPayload') }}</div>

          <el-alert
            v-if="legacyBanner"
            class="payload-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="legacyBanner"
          />

          <template v-if="isKubernetes">
            <ul class="config-hints">
              <li>{{ t('executorConfig.hintBackendHost') }}</li>
              <li>{{ t('executorConfig.hintPolicy') }}</li>
              <li>{{ t('executorConfig.hintWorkloadName') }}</li>
            </ul>

            <el-tabs v-model="payloadTab" class="payload-tabs" :before-leave="beforeLeavePayloadTab">
              <el-tab-pane :label="t('executorConfig.payloadTabForm')" name="form">
                <el-alert
                  v-if="!payloadObject"
                  type="info"
                  :closable="false"
                  show-icon
                  :title="t('executorConfig.formNeedsJson')"
                />
                <template v-else>
                  <el-form :model="formValues" label-width="160px" class="kube-form">
                    <el-form-item :label="t('executorConfig.kubeconfigPath')" :error="formFieldError('kubeconfigPath')">
                      <el-input v-model="formValues.kubeconfigPath" :disabled="submitting" :placeholder="t('executorConfig.placeholderAbsolutePath')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.kubeContext')" :error="formFieldError('kubeContext')">
                      <el-input v-model="formValues.kubeContext" :disabled="submitting" :placeholder="t('executorConfig.placeholderContext')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.namespace')" :error="formFieldError('namespace')">
                      <el-input v-model="formValues.namespace" :disabled="submitting" :placeholder="t('executorConfig.placeholderNamespace')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.manifestPath')" :error="formFieldError('manifestPath')">
                      <el-input v-model="formValues.manifestPath" :disabled="submitting" :placeholder="t('executorConfig.placeholderAbsolutePath')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.manifestContent')" :error="formFieldError('manifestContent')">
                      <el-input
                        v-model="formValues.manifestContent"
                        type="textarea"
                        :rows="4"
                        :disabled="submitting"
                        :placeholder="t('executorConfig.placeholderManifestContent')"
                      />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.workloadName')" :error="formFieldError('workloadName')">
                      <el-input v-model="formValues.workloadName" :disabled="submitting" :placeholder="t('executorConfig.placeholderWorkloadName')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.rolloutTimeoutSeconds')" :error="formFieldError('rolloutTimeoutSeconds')">
                      <el-input v-model="formValues.rolloutTimeoutSeconds" :disabled="submitting" :placeholder="t('executorConfig.placeholderRolloutTimeoutSeconds')" />
                    </el-form-item>
                    <el-form-item :label="t('executorConfig.logTailLines')" :error="formFieldError('logTailLines')">
                      <el-input v-model="formValues.logTailLines" :disabled="submitting" :placeholder="t('executorConfig.placeholderLogTailLines')" />
                    </el-form-item>
                  </el-form>
                  <div class="form-commit">
                    <el-button size="small" type="primary" plain :disabled="submitting" @click="commitForm">
                      {{ t('executorConfig.commitForm') }}
                    </el-button>
                    <span class="form-commit-hint">
                      {{ formDirty ? t('executorConfig.hintFormNotCommitted') : t('executorConfig.formCommitted') }}
                    </span>
                  </div>
                </template>
              </el-tab-pane>

              <el-tab-pane :label="t('executorConfig.payloadTabJson')" name="json">
                <el-input
                  v-model="configPayloadStr"
                  type="textarea"
                  :rows="12"
                  :disabled="submitting"
                  :placeholder="t('executorConfig.payloadPlaceholder')"
                />
                <div v-if="payloadTextError" class="payload-error">{{ payloadTextError }}</div>
                <ul v-if="jsonIssueLines.length" class="payload-issues">
                  <li v-for="line in jsonIssueLines" :key="line">{{ line }}</li>
                </ul>
              </el-tab-pane>
            </el-tabs>
          </template>

          <template v-else>
            <el-input
              v-model="configPayloadStr"
              type="textarea"
              :rows="8"
              :disabled="submitting"
              :placeholder="t('executorConfig.payloadPlaceholder')"
            />
            <div v-if="payloadTextError" class="payload-error">{{ payloadTextError }}</div>
          </template>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="requestCloseDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- Payload View Dialog -->
    <el-dialog v-model="payloadDialogVisible" :title="t('executorConfig.payloadDetail')" width="600px">
      <div v-if="viewPayloadInvalid" class="payload-error">{{ payloadTextBanner(viewPayloadText) }}</div>
      <pre class="payload-preview">{{ viewPayloadText }}</pre>
      <template #footer>
        <el-button @click="payloadDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import axios from 'axios';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  listExecutorConfigs,
  createExecutorConfig,
  updateExecutorConfig,
  toggleExecutorConfig,
  type NormalizedExecutorConfig,
  type CreateExecutorConfigPayload,
  type UpdateExecutorConfigPayload,
  type ExecutorType,
} from '@/api/release';
import { envApi } from '@/api/resource';
import {
  emptyFormValues,
  extractKubernetesForm,
  isJsonObject,
  mergeFormIntoPayload,
  parsePayloadText,
  serializePayloadObject,
  validateKubernetesStoredForm,
  validateKubernetesPayload,
  type KubernetesFormValues,
  type KubernetesIssue,
  type KubernetesPayloadField,
} from '@/utils/executorConfigPayload';

const { t } = useI18n();

const loading = ref(false);
const submitting = ref(false);
const configs = ref<NormalizedExecutorConfig[]>([]);
/** Whether the last read of the list failed: the rows on screen are then the ones read before it. */
const readFailed = ref(false);
const envOptions = ref<{ id: number; name: string }[]>([]);
const filters = ref({
  executorType: undefined as ExecutorType | undefined,
  envId: undefined as number | undefined,
});

/** Whether any filter is on screen: an empty table is then a filter result, not an empty list. */
const hasActiveFilters = computed(() => !!filters.value.executorType || !!filters.value.envId);

/** Clears the filters alone: no other state of the page - and no edit draft - is touched. */
function handleResetFilters() {
  filters.value.executorType = undefined;
  filters.value.envId = undefined;
}

const dialogVisible = ref(false);
const isEdit = ref(false);
const formRef = ref<FormInstance>();

// One configuration accepts one write at a time. This is the write control the enable switch, the
// disable button and the edit save of a configuration share; a write on another configuration is
// never held back by it.
const pendingWriteIds = ref(new Set<number>());

// The list request that is allowed to write into the page. A response of an older request is
// dropped rather than allowed to overwrite a newer list.
let listRequestSeq = 0;

// The edit session the dialog currently holds. A save only acts on the session it was started from,
// so its continuation can never close a dialog that belongs to another edit.
let editSessionSeq = 0;

const currentConfig = ref<CreateExecutorConfigPayload & { id?: number }>({
  executorName: '',
  executorType: 'SSH',
  envId: 0,
  configPayload: {},
  enabled: true,
});

// The payload text is the single source of truth for the configuration content: the structured form
// reads it and writes it back, and nothing here rewrites it on its own.
const configPayloadStr = ref('{}');
const payloadError = ref('');

// Structured Kubernetes editor, seeded from the payload text
const payloadTab = ref<'form' | 'json'>('form');
const formValues = ref<KubernetesFormValues>(emptyFormValues());
const formSnapshot = ref(JSON.stringify(emptyFormValues()));
const formUnrepresentable = ref<KubernetesPayloadField[]>([]);
const formIssues = ref<KubernetesIssue[]>([]);

// A configuration stored before this iteration may not hold a JSON object; its text is kept as it
// was stored and the dialog says so instead of quietly rewriting it.
const legacyRawInvalid = ref(false);

const payloadDialogVisible = ref(false);
const viewPayloadText = ref('');
const viewPayloadInvalid = ref(false);

const formRules: FormRules = {
  executorName: [{ required: true, message: t('common.required', { label: t('executorConfig.executorName') }) }],
  executorType: [{ required: true, message: t('common.required', { label: t('executorConfig.executorType') }) }],
  envId: [{ required: true, message: t('common.required', { label: t('executorConfig.env') }) }],
};

const filteredConfigs = computed(() => {
  let data = configs.value;
  if (filters.value.executorType) {
    data = data.filter(c => c.executorType === filters.value.executorType);
  }
  if (filters.value.envId) {
    data = data.filter(c => c.envId === filters.value.envId);
  }
  return data;
});

const isKubernetes = computed(() => currentConfig.value.executorType === 'KUBERNETES');

const payloadTextParse = computed(() => parsePayloadText(configPayloadStr.value));

const payloadObject = computed<Record<string, unknown> | null>(() => {
  const parse = payloadTextParse.value;
  return parse.ok && isJsonObject(parse.value) ? parse.value : null;
});

const payloadTextError = computed(() => {
  const parse = payloadTextParse.value;
  if (!parse.ok) return t('executorConfig.payloadInvalid');
  if (isKubernetes.value && !isJsonObject(parse.value)) return t('executorConfig.payloadNotObject');
  return '';
});

const legacyBanner = computed(() => {
  if (!legacyRawInvalid.value) return '';
  const parse = payloadTextParse.value;
  if (parse.ok && isJsonObject(parse.value)) return '';
  return payloadTextBanner(configPayloadStr.value);
});

const jsonIssues = computed<KubernetesIssue[]>(() =>
  isKubernetes.value && payloadObject.value ? validateKubernetesPayload(payloadObject.value) : []);

const jsonIssueLines = computed(() =>
  jsonIssues.value.map(issue => `${t(`executorConfig.${issue.field}`)}: ${t(`executorConfig.issue.${issue.code}`)}`));

const formDirty = computed(() => JSON.stringify(formValues.value) !== formSnapshot.value);

onMounted(async () => {
  await loadEnvs();
  await loadData();
});

/**
 * The environment page arrives already unwrapped, so its records sit on the page itself - one record
 * per environment, identified by id and shown by envName. A record whose name is missing falls back
 * to its code, and an environment this console cannot name at all still stays recognizable as the id
 * it is, so the select never offers an option whose label says nothing.
 */
function envOption(record: Record<string, unknown>): { id: number; name: string } {
  const name =
    textOrEmpty(record.envName)
    || textOrEmpty(record.envCode)
    || envFallbackLabel(record.id);
  return { id: Number(record.id), name };
}

/** The label of an environment this console has no name for: its id, explicitly marked as one. */
function envFallbackLabel(id: unknown): string {
  return t('common.envFallback', { id: id === undefined || id === null ? '?' : String(id) });
}

/**
 * How a configuration's environment is shown: the name the record itself carries, then the name the
 * page lists for that id, and the id itself when neither can name it. No environment is invented for
 * an id nothing lists.
 */
function envDisplay(config: NormalizedExecutorConfig): string {
  return config.envName
    || envOptions.value.find(env => env.id === config.envId)?.name
    || envFallbackLabel(config.envId);
}

/** Why a configuration's disable entry is not available, or nothing when it is. */
function disableHint(config: NormalizedExecutorConfig): string {
  if (isWritePending(config.id)) return t('executorConfig.writePendingHint');
  if (!config.enabled) return t('executorConfig.alreadyDisabledHint');
  return '';
}

function textOrEmpty(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

async function loadEnvs() {
  try {
    const page = await envApi.page({});
    envOptions.value = page.records.map(envOption);
  } catch {
    // The shared interceptor has already reported the failure
  }
}

/**
 * Reads the list, reporting whether what is on screen is up to date. Only the newest request writes
 * into the page, and a failed one keeps the list as it stands: a refresh that failed never empties
 * the page.
 */
async function loadData(): Promise<boolean> {
  const seq = ++listRequestSeq;
  loading.value = true;
  try {
    const res = await listExecutorConfigs();
    if (seq !== listRequestSeq) return true;
    configs.value = res || [];
    readFailed.value = false;
    return true;
  } catch {
    // The shared interceptor has already reported the failure; a request that a newer one superseded
    // has nothing left to report.
    if (seq !== listRequestSeq) return true;
    readFailed.value = true;
    return false;
  } finally {
    if (seq === listRequestSeq) {
      loading.value = false;
    }
  }
}

/**
 * Reloads the list after a write that already succeeded. What happens next can no longer change that
 * outcome: a failed reload is reported as a refresh failure and leaves the confirmed result and the
 * list in place.
 */
async function refreshAfterWrite() {
  if (!(await loadData())) {
    ElMessage.warning(t('executorConfig.saveRefreshFailed'));
  }
}

/** Whether a write for that configuration is still in flight. */
function isWritePending(id: number): boolean {
  return pendingWriteIds.value.has(id);
}

/** Claims the single write slot of a configuration; a second claim while one is pending is refused. */
function beginWrite(id: number): boolean {
  if (pendingWriteIds.value.has(id)) return false;
  pendingWriteIds.value.add(id);
  return true;
}

function endWrite(id: number) {
  pendingWriteIds.value.delete(id);
}

/**
 * Confirms a write against the list as it stands now: the row is found by its id, and a row the
 * current filter no longer shows is not re-inserted.
 */
function applyConfirmedEnabled(id: number, enabled: boolean) {
  const current = configs.value.find(item => item.id === id);
  if (!current) return;
  current.enabled = enabled;
}

function getTypeTagType(type: ExecutorType): string {
  switch (type) {
    case 'SSH': return 'info';
    case 'DOCKER': return 'success';
    case 'KUBERNETES': return 'warning';
    default: return 'info';
  }
}

/**
 * Opening an edit target replaces what the dialog holds, so it is refused while a save is in flight:
 * that save's context must not be taken away from under it.
 */
function openCreateDialog() {
  if (submitting.value) return;
  editSessionSeq += 1;
  isEdit.value = false;
  currentConfig.value = {
    executorName: '',
    executorType: 'SSH',
    envId: envOptions.value[0]?.id || 0,
    configPayload: {},
    enabled: true,
  };
  configPayloadStr.value = '{}';
  legacyRawInvalid.value = false;
  resetPayloadEditor();
  dialogVisible.value = true;
}

function openEditDialog(config: NormalizedExecutorConfig) {
  if (submitting.value) return;
  // The edit entry of a configuration is refused as well while a write on it is unfinished.
  if (isWritePending(config.id)) return;
  editSessionSeq += 1;
  isEdit.value = true;
  currentConfig.value = {
    id: config.id,
    executorName: config.executorName,
    executorType: config.executorType,
    envId: config.envId,
    configPayload: config.configPayload,
    enabled: config.enabled,
  };
  // A payload that no longer reads back as an object is shown exactly as it was stored
  configPayloadStr.value = config.configPayloadValid
    ? serializePayloadObject(config.configPayload)
    : (config.configPayloadRaw ?? '');
  legacyRawInvalid.value = !config.configPayloadValid;
  resetPayloadEditor();
  dialogVisible.value = true;
}

function openPayloadDialog(config: NormalizedExecutorConfig) {
  viewPayloadInvalid.value = !config.configPayloadValid;
  viewPayloadText.value = config.configPayloadValid
    ? serializePayloadObject(config.configPayload)
    : (config.configPayloadRaw ?? '');
  payloadDialogVisible.value = true;
}

/**
 * Closing the dialog is refused while a save is in flight: the dialog is where that save reads its
 * result from, and closing it would leave the request without the edit it belongs to.
 */
function requestCloseDialog() {
  if (submitting.value) return;
  dialogVisible.value = false;
}

/** The close button, the mask and Escape all come through here, and a save in flight refuses them. */
function handleDialogBeforeClose(done: (cancel?: boolean) => void) {
  if (submitting.value) return;
  done();
}

function resetForm() {
  formRef.value?.resetFields();
  currentConfig.value = {
    executorName: '',
    executorType: 'SSH',
    envId: 0,
    configPayload: {},
    enabled: true,
  };
  configPayloadStr.value = '{}';
  legacyRawInvalid.value = false;
  resetPayloadEditor();
}

function resetPayloadEditor() {
  payloadError.value = '';
  formIssues.value = [];
  const parse = payloadTextParse.value;
  payloadTab.value = isKubernetes.value && parse.ok && isJsonObject(parse.value) ? 'form' : 'json';
  seedFormFromPayload();
}

function seedFormFromPayload() {
  const payload = payloadObject.value;
  if (!payload) {
    formValues.value = emptyFormValues();
    formUnrepresentable.value = [];
    formIssues.value = [];
    formSnapshot.value = JSON.stringify(formValues.value);
    return;
  }
  const { values, unrepresentable } = extractKubernetesForm(payload);
  formValues.value = values;
  formUnrepresentable.value = unrepresentable;
  // A stored value the form cannot show stays visible as a blocking issue until the JSON is fixed:
  // committing the form would otherwise drop it.
  formIssues.value = unrepresentable.map(field => ({ field, code: 'NOT_REPRESENTABLE' as const }));
  formSnapshot.value = JSON.stringify(values);
}

/**
 * Switching the executor type never clears the configuration content; it decides which editor is
 * shown. The select only follows the request once it is accepted, so a refused or cancelled switch
 * leaves the type, the form and the JSON text exactly as they were. A form draft that was never
 * written to the configuration content is a decision, not a silent casualty of the switch.
 */
async function handleExecutorTypeChange(nextType: ExecutorType) {
  if (nextType === currentConfig.value.executorType) return;

  if (currentConfig.value.executorType === 'KUBERNETES' && formDirty.value) {
    if ((await resolveFormDraft()) === 'stay') return;
  }

  currentConfig.value.executorType = nextType;
  if (nextType !== 'KUBERNETES' || formDirty.value) return;
  // Leaving the form resolved the draft, so the structured editor opens on the configuration content
  // as it stands now - including an edit made in the JSON tab earlier in this dialog.
  resetPayloadEditor();
}

/**
 * The form can only edit a payload it can represent, so leaving the JSON tab for it is refused until
 * the text is a JSON object. Leaving the form with edits that were never written to the configuration
 * content is a decision rather than a silent move: write them, drop them, or stay. Element Plus
 * awaits this, so the decision is made before the pane changes.
 */
async function beforeLeavePayloadTab(activeName: string | number): Promise<boolean> {
  // A save in flight owns the editor: the pane it is reading must not move under it.
  if (submitting.value) return false;
  if (activeName === 'form') {
    if (!payloadObject.value) {
      ElMessage.warning(t('executorConfig.formNeedsJson'));
      return false;
    }
    if (!formDirty.value) seedFormFromPayload();
    return true;
  }
  if (!formDirty.value) return true;
  return (await resolveFormDraft()) !== 'stay';
}

function formFieldError(field: KubernetesPayloadField): string {
  return formIssues.value
    .filter(issue => issue.field === field)
    .map(issue => t(`executorConfig.issue.${issue.code}`))
    .join('; ');
}

function payloadTextBanner(text: string): string {
  return parsePayloadText(text).ok
    ? t('executorConfig.legacyNotObjectNotice')
    : t('executorConfig.legacyInvalidNotice');
}

function collectFormIssues(): KubernetesIssue[] {
  return validateKubernetesStoredForm(formUnrepresentable.value, formValues.value);
}

/**
 * Settles a form draft before the editor leaves it. Writing it goes through the same validation a
 * save applies - a form that does not validate is never written around it - and dropping it is only
 * ever the operator's answer; closing the dialog keeps the draft.
 */
async function resolveFormDraft(): Promise<'commit' | 'discard' | 'stay'> {
  try {
    await ElMessageBox.confirm(
      t('executorConfig.draftConfirmMessage'),
      t('executorConfig.draftConfirmTitle'),
      {
        distinguishCancelAndClose: true,
        confirmButtonText: t('executorConfig.draftCommit'),
        cancelButtonText: t('executorConfig.draftDiscard'),
      },
    );
  } catch (action) {
    if (action !== 'cancel') return 'stay';
    seedFormFromPayload();
    return 'discard';
  }
  return commitFormDraft() ? 'commit' : 'stay';
}

/** Writes the form into the configuration content, refusing what the save path would refuse. */
function commitFormDraft(): boolean {
  const payload = payloadObject.value;
  if (!payload) {
    ElMessage.warning(t('executorConfig.formNeedsJson'));
    return false;
  }
  const issues = collectFormIssues();
  formIssues.value = issues;
  if (issues.length > 0) {
    ElMessage.warning(t('executorConfig.formInvalid'));
    return false;
  }
  configPayloadStr.value = serializePayloadObject(mergeFormIntoPayload(payload, formValues.value));
  seedFormFromPayload();
  return true;
}

function commitForm() {
  if (commitFormDraft()) {
    ElMessage.success(t('executorConfig.formCommitted'));
  }
}

/**
 * The configuration content to submit: the active editor decides. The form is written into the
 * current JSON text, so fields the form does not own are carried through unchanged.
 */
function resolvePayloadTextForSubmit(): string | null {
  const editingJson = !isKubernetes.value || payloadTab.value === 'json';

  if (!editingJson) {
    if (!commitFormDraft()) return null;
    return configPayloadStr.value;
  }

  const parse = payloadTextParse.value;
  if (!parse.ok) {
    ElMessage.warning(t('executorConfig.payloadInvalid'));
    return null;
  }
  if (isKubernetes.value) {
    if (!isJsonObject(parse.value)) {
      ElMessage.warning(t('executorConfig.payloadNotObject'));
      return null;
    }
    if (validateKubernetesPayload(parse.value).length > 0) {
      ElMessage.warning(t('executorConfig.payloadIssuesFound'));
      return null;
    }
  }
  const text = configPayloadStr.value.trim() === '' ? '{}' : configPayloadStr.value;
  configPayloadStr.value = text;
  return text;
}

/** Report only what the shared interceptor does not, so a failure is never shown twice. */
function reportError(err: unknown, fallbackKey: string) {
  if (axios.isAxiosError(err)) return;
  ElMessage.error(err instanceof Error && err.message ? err.message : t(fallbackKey));
}

/**
 * The update request for a configuration whose id is already known: the id travels in the path and
 * in the payload as the same value, and the request is built from the identified form rather than
 * from the draft the dialog happens to hold.
 */
function toUpdatePayload(id: number, form: CreateExecutorConfigPayload): UpdateExecutorConfigPayload {
  return {
    id,
    executorName: form.executorName,
    executorType: form.executorType,
    envId: form.envId,
    configPayload: form.configPayload,
    enabled: form.enabled,
  };
}

/**
 * Saves the dialog's configuration. The guard is claimed before the form is validated - validation is
 * asynchronous, and a second click during it must not start a second save - and the request is built
 * from a snapshot taken here, so the path id and the payload id carry the same value and nothing
 * typed afterwards can change what is sent.
 */
async function handleSubmit() {
  if (!formRef.value) return;
  if (submitting.value) return;

  const editId = isEdit.value ? currentConfig.value.id : undefined;
  const session = editSessionSeq;
  submitting.value = true;
  // An identified configuration is written under the same control as its switch and disable button.
  if (editId !== undefined && !beginWrite(editId)) {
    submitting.value = false;
    return;
  }

  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;

    const payloadText = resolvePayloadTextForSubmit();
    if (payloadText === null) return;

    currentConfig.value.configPayload = payloadText;
    const snapshot: CreateExecutorConfigPayload = {
      executorName: currentConfig.value.executorName,
      executorType: currentConfig.value.executorType,
      envId: currentConfig.value.envId,
      configPayload: payloadText,
      enabled: currentConfig.value.enabled,
    };

    // An identified configuration is updated; anything else is created, exactly as before.
    if (editId !== undefined) {
      await updateExecutorConfig(editId, toUpdatePayload(editId, snapshot));
      ElMessage.success(t('common.updateSuccess'));
    } else {
      await createExecutorConfig(snapshot);
      ElMessage.success(t('common.createSuccess'));
    }
    // Only the edit session this save was started from is closed by it.
    if (session === editSessionSeq) {
      dialogVisible.value = false;
    }
    await refreshAfterWrite();
  } catch (err) {
    // A rejected configuration keeps the dialog open with everything the operator typed
    reportError(err, 'common.requestFailed');
  } finally {
    if (editId !== undefined) endWrite(editId);
    submitting.value = false;
  }
}

/** The switch asks for a change; the request decides whether it happens. */
function handleSwitchChange(config: NormalizedExecutorConfig, value: boolean | string | number) {
  void handleToggleEnabled(config, value === true);
}

/**
 * Confirms a change only once the backend accepted it. Until then the value the switch shows is the
 * last confirmed one, and a refused request leaves it exactly as it is: nothing here inverts the
 * current value or restores a value from before the request.
 */
async function handleToggleEnabled(config: NormalizedExecutorConfig, nextEnabled: boolean) {
  if (nextEnabled === config.enabled) return;
  if (!beginWrite(config.id)) return;

  try {
    if ((await toggleExecutorConfig(config.id, nextEnabled)) === false) {
      // The request itself was accepted and the backend only declined the change, so nothing is
      // confirmed here and this is not a request failure.
      ElMessage.warning(t('common.operationFailed'));
      return;
    }
    applyConfirmedEnabled(config.id, nextEnabled);
    ElMessage.success(nextEnabled ? t('common.enableSuccess') : t('common.disableSuccess'));
    await refreshAfterWrite();
  } catch (err) {
    // A refused write is reported by the shared interceptor; the confirmed value is left untouched.
    reportError(err, 'common.requestFailed');
  } finally {
    endWrite(config.id);
  }
}

// No delete API in backend - use toggle to disable instead
async function handleDelete(config: NormalizedExecutorConfig) {
  // The entry itself is refused while a write on that configuration is unfinished.
  if (isWritePending(config.id)) return;
  try {
    await ElMessageBox.confirm(
      t('common.deleteConfirm', { name: config.executorName }),
      t('common.deleteConfirmTitle'),
      { type: 'warning' }
    );
  } catch {
    return;
  }
  // The confirmation waits on the operator, so what the write needs is checked again here: the
  // configuration may have been written to while the box was open.
  if (!beginWrite(config.id)) return;
  try {
    if ((await toggleExecutorConfig(config.id, false)) === false) {
      ElMessage.warning(t('common.operationFailed'));
      return;
    }
    applyConfirmedEnabled(config.id, false);
    ElMessage.success(t('common.disableSuccess'));
    await refreshAfterWrite();
  } catch (err) {
    reportError(err, 'common.requestFailed');
  } finally {
    endWrite(config.id);
  }
}
</script>

<style scoped>
.executor-configs {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
  min-width: 0;
  overflow-wrap: break-word;
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.filter-card {
  margin-bottom: var(--space-md);
}

.table-card {
  margin-bottom: var(--space-md);
}

.table-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
}

/* One primary action stands out in the row; the rest stay lightweight entries next to it */
.action-primary {
  font-weight: 500;
}

.read-error {
  margin-bottom: var(--ih-spacing-md);
}

.read-error-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ih-spacing-sm);
}

.read-error-hint {
  font-size: var(--ih-font-size-sm);
  color: var(--ih-text-secondary);
}

.empty-state {
  padding: var(--ih-spacing-xl) 0;
}

.empty-title {
  margin: 0 0 var(--ih-spacing-1);
  font-size: var(--ih-font-size-md);
  color: var(--color-ink);
}

.empty-hint {
  margin: 0 0 var(--ih-spacing-md);
  font-size: var(--ih-font-size-sm);
  color: var(--ih-text-secondary);
}

.payload-section {
  margin-bottom: var(--ih-spacing-lg);
}

.payload-label {
  font-size: var(--ih-font-size-md);
  color: var(--ih-text-secondary);
  margin-bottom: var(--ih-spacing-sm);
}

.payload-alert {
  margin-bottom: var(--ih-spacing-4);
}

.config-hints {
  margin: 0 0 var(--ih-spacing-sm);
  padding-left: var(--ih-spacing-lg);
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-sm);
  line-height: 1.7;
}

.payload-tabs {
  margin-top: var(--ih-spacing-1);
}

.form-commit {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-4);
}

.form-commit-hint {
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-sm);
}

.payload-preview {
  background-color: var(--ih-bg-subtle);
  border: 1px solid var(--ih-border-light);
  padding: var(--ih-spacing-lg);
  border-radius: var(--ih-radius-md);
  color: var(--color-ink);
  overflow: auto;
  max-height: 400px;
  font: var(--ih-font-code);
}

.payload-error {
  color: var(--ih-danger);
  font-size: var(--ih-font-size-sm);
  margin-top: var(--ih-spacing-1);
}

.payload-issues {
  margin: var(--ih-spacing-2) 0 0;
  padding-left: var(--ih-spacing-lg);
  color: var(--ih-danger);
  font-size: var(--ih-font-size-sm);
  line-height: 1.7;
}

:deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
</style>