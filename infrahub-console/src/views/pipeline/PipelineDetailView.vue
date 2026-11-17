<template>
  <section>
    <div class="page-toolbar">
      <div class="page-title">
        <h2>{{ pipelineTitle }}</h2>
        <p>{{ t('pages.pipelineDetail.desc') }}</p>
      </div>
      <div class="toolbar-actions">
        <el-button @click="router.push('/pipeline/defs')">{{ t('common.back') }}</el-button>
        <el-button @click="loadAll">{{ t('common.refresh') }}</el-button>
        <el-tooltip v-if="authStore.canAccess(['RELEASE_VIEW'])" content="跳转到该应用的当前版本列表" placement="top">
          <el-button @click="navigateToCurrentVersion" :disabled="!pipeline?.appId">查看当前版本</el-button>
        </el-tooltip>
        <el-button v-if="canRun" type="primary" :disabled="!pipeline?.enabled" @click="runDialogVisible = true">
          {{ t('common.runPipeline') }}
        </el-button>
      </div>
    </div>

    <div class="pipeline-meta">
      <span>{{ t('fields.pipelineCode') }}: {{ pipeline?.pipelineCode ?? '-' }}</span>
      <span>{{ t('fields.appName') }}: {{ appDisplayName }}</span>
      <span>{{ t('fields.enabled') }}: <el-tag :type="pipeline?.enabled ? 'success' : 'info'">{{ pipeline?.enabled ? 'true' : 'false' }}</el-tag></span>
    </div>

    <div class="pipeline-layout">
      <section class="content-panel">
        <div class="panel-header">
          <h3>{{ t('fields.stageName') }}</h3>
          <el-button v-if="canEdit" @click="openCreateStage">{{ t('common.createStage') }}</el-button>
        </div>
        <el-table :data="stages" v-loading="loading" highlight-current-row row-key="id" @current-change="selectStage">
          <el-table-column prop="stageOrder" :label="t('fields.stageOrder')" width="120" />
          <el-table-column prop="stageName" :label="t('fields.stageName')" min-width="160" />
          <el-table-column prop="timeoutSeconds" :label="t('fields.timeoutSeconds')" width="140" />
          <el-table-column v-if="canEdit" :label="t('common.actions')" fixed="right" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openEditStage(row)">{{ t('common.edit') }}</el-button>
              <el-button link type="danger" @click.stop="deleteStage(row)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="content-panel">
        <div class="panel-header">
          <h3>{{ t('fields.stepName') }}</h3>
          <el-button v-if="canEdit" :disabled="selectedStageId == null" @click="openCreateStep">{{ t('common.createStep') }}</el-button>
        </div>
        <el-table :data="steps" v-loading="stepLoading" row-key="id">
          <el-table-column prop="stepOrder" :label="t('fields.stepOrder')" width="110" />
          <el-table-column prop="stepName" :label="t('fields.stepName')" min-width="140" />
          <el-table-column prop="executorType" :label="t('fields.executorType')" width="140" />
          <el-table-column prop="timeoutSeconds" :label="t('fields.timeoutSeconds')" width="130" />
          <el-table-column prop="continueOnFail" :label="t('fields.continueOnFail')" width="140" />
          <el-table-column v-if="canEdit" :label="t('common.actions')" fixed="right" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditStep(row)">{{ t('common.edit') }}</el-button>
              <el-button link type="danger" @click="deleteStep(row)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="stageDialogVisible" :title="editingStageId ? t('common.editStage') : t('common.createStage')" width="520px" @closed="resetStageForm">
      <el-form ref="stageFormRef" :model="stageForm" :rules="stageRules" label-width="110px">
        <el-form-item :label="t('fields.stageName')" prop="stageName">
          <el-input v-model="stageForm.stageName" />
        </el-form-item>
        <el-form-item :label="t('fields.stageOrder')" prop="stageOrder">
          <el-input-number v-model="stageForm.stageOrder" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('fields.timeoutSeconds')" prop="timeoutSeconds">
          <el-input-number v-model="stageForm.timeoutSeconds" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stageDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="savingStage" @click="saveStage">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stepDialogVisible" :title="editingStepId ? t('common.editStep') : t('common.createStep')" width="640px" @closed="resetStepForm">
      <el-form ref="stepFormRef" :model="stepForm" :rules="stepRules" label-width="120px">
        <el-form-item :label="t('fields.stepName')" prop="stepName">
          <el-input v-model="stepForm.stepName" />
        </el-form-item>
        <el-form-item :label="t('fields.stepType')" prop="stepType">
          <el-input v-model="stepForm.stepType" />
        </el-form-item>
        <el-form-item :label="t('fields.executorType')" prop="executorType">
          <el-select v-model="stepForm.executorType" filterable style="width: 100%">
            <el-option label="LOCAL_SHELL" value="LOCAL_SHELL" />
            <el-option label="RELEASE_DEPLOY" value="RELEASE_DEPLOY" />
            <el-option :label="t('pipelineDelivery.registerArtifact')" value="REGISTER_ARTIFACT" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('fields.stepOrder')" prop="stepOrder">
          <el-input-number v-model="stepForm.stepOrder" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="stepForm.executorType === 'LOCAL_SHELL'" :label="t('fields.scriptContent')" prop="scriptContent">
          <el-input v-model="stepForm.scriptContent" type="textarea" :rows="4" />
        </el-form-item>
        <el-alert v-if="stepForm.executorType === 'REGISTER_ARTIFACT'" :title="t('pipelineDelivery.descriptorHint')" :closable="false" />
        <el-form-item :label="t('fields.timeoutSeconds')" prop="timeoutSeconds">
          <el-input-number v-model="stepForm.timeoutSeconds" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('fields.retryTimes')" prop="retryTimes">
          <el-input-number v-model="stepForm.retryTimes" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('fields.continueOnFail')" prop="continueOnFail">
          <el-switch v-model="stepForm.continueOnFail" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stepDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="savingStep" @click="saveStep">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <PipelineRunDialog v-model="runDialogVisible" :pipeline="pipeline" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { appApi } from '@/api/resource';
import { getPipeline, pipelineStageApi, pipelineStepApi } from '@/api/pipeline';
import PipelineRunDialog from './PipelineRunDialog.vue';
import { useAuthStore } from '@/store/auth';

interface StageForm {
  stageName: string;
  stageOrder?: number;
  timeoutSeconds?: number;
}

interface StepForm {
  stepName: string;
  stepType: string;
  executorType: string;
  stepOrder?: number;
  scriptContent: string;
  timeoutSeconds?: number;
  retryTimes?: number;
  continueOnFail: boolean;
}

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const pipelineId = computed(() => Number(route.params.id));
const pipeline = ref<Record<string, unknown> | null>(null);
const authStore = useAuthStore();
const canEdit = computed(() => authStore.canAccess(['SYSTEM_ADMIN']));
const canRun = computed(() => pipeline.value && authStore.canAccessApp('PIPELINE_EXECUTE', Number(pipeline.value.appId)));
const appDisplayName = ref('-');
const stages = ref<Record<string, unknown>[]>([]);
const steps = ref<Record<string, unknown>[]>([]);
const selectedStageId = ref<number | null>(null);
const loading = ref(false);
const stepLoading = ref(false);
const stageDialogVisible = ref(false);
const stepDialogVisible = ref(false);
const runDialogVisible = ref(false);
const savingStage = ref(false);
const savingStep = ref(false);
const editingStageId = ref<number | null>(null);
const editingStepId = ref<number | null>(null);
const stageFormRef = ref<FormInstance>();
const stepFormRef = ref<FormInstance>();
const stageForm = reactive<StageForm>({ stageName: '', stageOrder: 1, timeoutSeconds: 30 });
const stepForm = reactive<StepForm>({
  stepName: '',
  stepType: 'SCRIPT',
  executorType: 'LOCAL_SHELL',
  stepOrder: 1,
  scriptContent: '',
  timeoutSeconds: 30,
  retryTimes: 0,
  continueOnFail: false,
});

const pipelineTitle = computed(() => String(pipeline.value?.pipelineName ?? t('pages.pipelineDetail.title')));
const stageRules = computed<FormRules>(() => ({
  stageName: [{ required: true, message: t('common.required', { label: t('fields.stageName') }), trigger: 'blur' }],
  stageOrder: [{ required: true, message: t('common.required', { label: t('fields.stageOrder') }), trigger: 'blur' }],
}));
const stepRules = computed<FormRules>(() => ({
  stepName: [{ required: true, message: t('common.required', { label: t('fields.stepName') }), trigger: 'blur' }],
  stepType: [{ required: true, message: t('common.required', { label: t('fields.stepType') }), trigger: 'blur' }],
  executorType: [{ required: true, message: t('common.required', { label: t('fields.executorType') }), trigger: 'change' }],
  stepOrder: [{ required: true, message: t('common.required', { label: t('fields.stepOrder') }), trigger: 'blur' }],
  scriptContent:
    stepForm.executorType === 'LOCAL_SHELL'
      ? [{ required: true, message: t('common.required', { label: t('fields.scriptContent') }), trigger: 'blur' }]
      : [],
}));

async function loadPipeline() {
  pipeline.value = await getPipeline(pipelineId.value);
  const appPage = await appApi.page({ pageNum: 1, pageSize: 200 });
  const app = appPage.records.find((record) => String(record.id) === String(pipeline.value?.appId));
  appDisplayName.value = String(app?.appName ?? pipeline.value?.appId ?? '-');
}

async function loadStages() {
  loading.value = true;
  try {
    stages.value = await pipelineStageApi.listByPipeline(pipelineId.value);
    if (stages.value.length && selectedStageId.value == null) {
      selectedStageId.value = Number(stages.value[0].id);
    }
    if (selectedStageId.value != null) {
      await loadSteps(selectedStageId.value);
    } else {
      steps.value = [];
    }
  } finally {
    loading.value = false;
  }
}

async function loadSteps(stageId: number) {
  stepLoading.value = true;
  try {
    steps.value = await pipelineStageApi.listSteps(stageId);
  } finally {
    stepLoading.value = false;
  }
}

async function loadAll() {
  await loadPipeline();
  await loadStages();
}

async function selectStage(row?: Record<string, unknown>) {
  selectedStageId.value = row?.id == null ? null : Number(row.id);
  if (selectedStageId.value == null) {
    steps.value = [];
    return;
  }
  await loadSteps(selectedStageId.value);
}

function resetStageForm() {
  editingStageId.value = null;
  stageForm.stageName = '';
  stageForm.stageOrder = stages.value.length + 1;
  stageForm.timeoutSeconds = 30;
  stageFormRef.value?.clearValidate();
}

function openCreateStage() {
  resetStageForm();
  stageDialogVisible.value = true;
}

function openEditStage(row: Record<string, unknown>) {
  editingStageId.value = Number(row.id);
  stageForm.stageName = String(row.stageName ?? '');
  stageForm.stageOrder = Number(row.stageOrder ?? 1);
  stageForm.timeoutSeconds = row.timeoutSeconds == null ? undefined : Number(row.timeoutSeconds);
  stageDialogVisible.value = true;
}

async function saveStage() {
  await stageFormRef.value?.validate();
  savingStage.value = true;
  try {
    if (editingStageId.value == null) {
      await pipelineStageApi.create(pipelineId.value, { ...stageForm });
      ElMessage.success(t('common.successCreate'));
    } else {
      await pipelineStageApi.update(editingStageId.value, { ...stageForm });
      ElMessage.success(t('common.successUpdate'));
    }
    stageDialogVisible.value = false;
    await loadStages();
  } finally {
    savingStage.value = false;
  }
}

async function deleteStage(row: Record<string, unknown>) {
  await ElMessageBox.confirm(t('common.deleteConfirmText'), t('common.deleteConfirmTitle'), {
    type: 'warning',
    confirmButtonText: t('common.deleteConfirmButton'),
    cancelButtonText: t('common.cancel'),
  });
  await pipelineStageApi.remove(Number(row.id));
  if (selectedStageId.value === Number(row.id)) {
    selectedStageId.value = null;
  }
  ElMessage.success(t('common.successDelete'));
  await loadStages();
}

function resetStepForm() {
  editingStepId.value = null;
  stepForm.stepName = '';
  stepForm.stepType = 'SCRIPT';
  stepForm.executorType = 'LOCAL_SHELL';
  stepForm.stepOrder = steps.value.length + 1;
  stepForm.scriptContent = '';
  stepForm.timeoutSeconds = 30;
  stepForm.retryTimes = 0;
  stepForm.continueOnFail = false;
  stepFormRef.value?.clearValidate();
}

function openCreateStep() {
  resetStepForm();
  stepDialogVisible.value = true;
}

function openEditStep(row: Record<string, unknown>) {
  editingStepId.value = Number(row.id);
  stepForm.stepName = String(row.stepName ?? '');
  stepForm.stepType = String(row.stepType ?? 'SCRIPT');
  stepForm.executorType = String(row.executorType ?? 'LOCAL_SHELL');
  stepForm.stepOrder = Number(row.stepOrder ?? 1);
  stepForm.scriptContent = String(row.scriptContent ?? '');
  stepForm.timeoutSeconds = row.timeoutSeconds == null ? undefined : Number(row.timeoutSeconds);
  stepForm.retryTimes = row.retryTimes == null ? 0 : Number(row.retryTimes);
  stepForm.continueOnFail = Boolean(row.continueOnFail);
  stepDialogVisible.value = true;
}

async function saveStep() {
  if (selectedStageId.value == null) {
    return;
  }
  await stepFormRef.value?.validate();
  savingStep.value = true;
  try {
    const payload = {
      ...stepForm,
      scriptContent: stepForm.executorType === 'LOCAL_SHELL' ? stepForm.scriptContent : '',
    };
    if (editingStepId.value == null) {
      await pipelineStageApi.createStep(selectedStageId.value, payload);
      ElMessage.success(t('common.successCreate'));
    } else {
      await pipelineStepApi.update(editingStepId.value, payload);
      ElMessage.success(t('common.successUpdate'));
    }
    stepDialogVisible.value = false;
    await loadSteps(selectedStageId.value);
  } finally {
    savingStep.value = false;
  }
}

async function deleteStep(row: Record<string, unknown>) {
  await ElMessageBox.confirm(t('common.deleteConfirmText'), t('common.deleteConfirmTitle'), {
    type: 'warning',
    confirmButtonText: t('common.deleteConfirmButton'),
    cancelButtonText: t('common.cancel'),
  });
  await pipelineStepApi.remove(Number(row.id));
  ElMessage.success(t('common.successDelete'));
  if (selectedStageId.value != null) {
    await loadSteps(selectedStageId.value);
  }
}

// V-D: navigateToCurrentVersion — jump to current versions filtered by appId
function navigateToCurrentVersion() {
  const appId = pipeline.value?.appId;
  if (!appId) return;
  router.push({ path: '/release/current-versions', query: { appId: String(appId) } });
}

onMounted(loadAll);
</script>

<style scoped lang="scss">
.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--ih-spacing-sm);
}

.pipeline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ih-spacing-lg);
  margin-bottom: var(--ih-section-gap);
  color: var(--ih-text-secondary);
}

.pipeline-layout {
  display: grid;
  grid-template-columns: minmax(320px, 0.9fr) minmax(420px, 1.4fr);
  gap: var(--ih-section-gap);
}

.content-panel {
  min-width: 0;
  padding: var(--ih-spacing-xl);
  background: var(--ih-bg-content);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-lg);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ih-spacing-5);
  margin-bottom: var(--ih-spacing-5);

  h3 {
    margin: 0;
    font: var(--ih-font-section-title);
  }
}

@media (max-width: 1100px) {
  .pipeline-layout {
    grid-template-columns: 1fr;
  }
}
</style>
