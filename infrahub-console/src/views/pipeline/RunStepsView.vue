<template>
  <!-- 运行上下文读取失败：不伪装成"未关联发布单"，保留后端原始错误 -->
  <div v-if="runContextFailure" class="run-context-alert">
    <el-alert :title="t('runSteps.runContextFailed')" type="error" show-icon :closable="false">
      <div class="read-failure-hint">{{ t('runSteps.runContextFailedHint') }}</div>
      <div v-if="runContextFailureAdvice" class="read-failure-hint">{{ runContextFailureAdvice }}</div>
      <pre v-if="runContextRawError" class="raw-error-text">{{ runContextRawError }}</pre>
      <div class="alert-actions">
        <el-button size="small" :loading="runContextLoading" @click="refreshRunContext">{{ t('runSteps.refreshRunContext') }}</el-button>
        <span class="read-again-hint">{{ t('runSteps.refreshRunContextTip') }}</span>
      </div>
    </el-alert>
  </div>

  <CrudPage ref="crudPageRef" :config="config">
    <template #filters>
      <!-- 运行 ID 经过校验；无效 ID 明确说明，不显示未经校验的原始参数 -->
      <el-tag v-if="runId" type="info" effect="plain" disable-transitions>{{ t('runSteps.runIdLabel') }}：{{ runId }}</el-tag>
      <el-tag v-else type="danger" effect="plain" disable-transitions>{{ t('runSteps.invalidRunId') }}</el-tag>
      <el-tag v-if="!runContext && runContextLoading" type="info" effect="plain" disable-transitions>{{ t('runSteps.runContextLoading') }}</el-tag>
      <!-- 运行 ID 无效时不存在可确认的运行，不给出任何运行状态结论 -->
      <el-tooltip v-else-if="runId" :content="t('runSteps.statusSeparateHint')" placement="top">
        <el-tag :type="runStatusTagType(runContext?.runStatus)" effect="plain" disable-transitions>
          {{ t('runSteps.runStatusLabel') }}：{{ runStatusText(runContext?.runStatus) }}
        </el-tag>
      </el-tooltip>
      <!-- 关联发布单：只有运行上下文读取成功后才能判断有无关联，读取失败时不做任何推断 -->
      <el-tag v-if="runContext?.versionNo" type="info" effect="plain" disable-transitions>{{ t('fields.version') }} {{ runContext.versionNo }}</el-tag>
      <el-tag v-if="runContext?.artifactId" type="info" effect="plain" disable-transitions>{{ t('fields.artifactId') }} {{ runContext.artifactId }}</el-tag>
      <el-tag v-if="releaseId && !runContext?.serviceId" type="warning" effect="plain" disable-transitions>{{ t('runSteps.releaseOrderLinked', { id: releaseId }) }}</el-tag>
      <el-tag
        v-else-if="runContext && !runContext.serviceId && !runContextFailure && runId"
        type="info"
        effect="plain"
        disable-transitions
      >
        {{ t('runSteps.releaseOrderNone') }}
      </el-tag>
      <!-- 部署记录与查看发布单入口：受现有 RELEASE_VIEW / SYSTEM_ADMIN 权限控制，且必须已知关联发布单 -->
      <template v-if="canViewRelease && releaseId">
        <el-tooltip :content="t('runSteps.viewReleaseOrderTip')" placement="top">
          <el-button v-if="!runContext?.serviceId" link type="primary" @click="navigateToReleaseOrder">{{ t('runSteps.viewReleaseOrder') }}</el-button>
        </el-tooltip>
        <el-tooltip :content="t('runSteps.deployRecordsTip')" placement="top">
          <el-button link type="primary" @click="showDeployRecords">{{ t('runSteps.deployRecordsAction') }}</el-button>
        </el-tooltip>
      </template>
      <el-tooltip :content="t('runSteps.refreshRunContextTip')" placement="top">
        <el-button :disabled="runContextLoading" @click="refreshRunContext">{{ t('runSteps.refreshRunContext') }}</el-button>
      </el-tooltip>
      <el-button v-if="runId" type="primary" @click="openRunLog">{{ t('runSteps.openRunLog') }}</el-button>
      <el-button link type="primary" @click="router.back()">{{ t('common.back') }}</el-button>
    </template>

    <template #before-table>
      <PipelineRunStages
        v-if="runId"
        :key="runId"
        :run-id="runId"
        :pipeline-id="parseRunId(runContext?.pipelineId)"
        :run-status="runStatusKind(runContext?.runStatus)"
        :run-finished="runFinished"
        :current-stage="String(runContext?.currentStage ?? '')"
        :rows="stageRows"
        :loading="runContextLoading"
        :duration-text="durationText"
      />
    </template>
    <template #table-summary>{{ t('runSteps.stepRecordsTable') }}</template>

    <!-- 步骤状态：与整条流水线的运行状态分别显示，不据当前页步骤推断流水线成功 -->
    <template #runStatus="{ row }">
      <span class="run-status-cell">
        <el-tag v-if="runStatusKind(row.runStatus) !== 'UNKNOWN'" :type="runStatusTagType(row.runStatus)" size="small">
          {{ runStatusText(row.runStatus) }}
        </el-tag>
        <template v-else>
          <el-tag type="info" size="small">{{ t('pipelineRuns.statusUnconfirmed') }}</el-tag>
          <span class="run-status-raw">{{ rawRunStatus(row.runStatus) }}</span>
        </template>
      </span>
    </template>
    <template #logExcerpt="{ row }">
      <span class="excerpt-cell">
        <span class="excerpt-preview">{{ excerptPreview(row.logExcerpt) }}</span>
        <el-button link type="primary" size="small" @click="openFullLog(row)">{{ t('runSteps.openFullLog') }}</el-button>
        <el-button link type="primary" size="small" @click="openLogExcerpt(row)">{{ t('runSteps.openLogExcerpt') }}</el-button>
      </span>
    </template>
    <template #duration="{ value }">
      <span>{{ durationText(value) }}</span>
    </template>
  </CrudPage>

  <DeployRecordsDialog
    v-model="deployRecordsVisible"
    :records="deployRecords"
    :loading="deployRecordsLoading"
    :error="deployRecordsError"
    @retry="retryDeployRecords"
  />

  <PipelineLogDialog
    v-if="logTarget && runId"
    :key="logTarget.id ?? 'run'"
    :run-id="runId"
    :step-run-id="logTarget.id"
    :label="logTarget.label"
    :run-finished="runFinished"
    @close="logTarget = null"
  />

  <!-- 日志摘录只读视图：等宽、容器内滚动、可复制原始内容 -->
  <el-dialog v-model="excerptVisible" :title="t('runSteps.logExcerptTitle')" width="min(900px, 94vw)">
    <div class="excerpt-header">
      <span class="excerpt-step">{{ excerptStepLabel }}</span>
      <el-button size="small" @click="copyExcerpt">{{ t('runSteps.logExcerptCopy') }}</el-button>
    </div>
    <div class="excerpt-notice">{{ t('runSteps.logExcerptNotice') }}</div>
    <pre v-if="excerptText" class="excerpt-body">{{ excerptText }}</pre>
    <el-empty v-else :description="t('runSteps.logExcerptEmpty')" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import CrudPage from '@/components/page/CrudPage.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import PipelineLogDialog from './PipelineLogDialog.vue';
import PipelineRunStages from './PipelineRunStages.vue';
import { getReleaseDeployRecords } from '@/api/release';
import { getPipelineRun, listPipelineCatalog, pipelineStepRunApi } from '@/api/pipeline';
import { useAuthStore } from '@/store/auth';
import { formatDateTime } from '@/utils/format';

const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const authStore = useAuthStore();
const crudPageRef = ref<InstanceType<typeof CrudPage>>();
const stageRows = ref<Record<string, unknown>[]>([]);
let stepsSeq = 0;

/** 读取失败：优先保留后端原始原因（不改写、不翻译），取不到原因时按当前语言回退。 */
interface ReadFailure {
  reason: string | null;
  code?: 'SCOPE';
}

/**
 * 判据与流水线列表入口一致：只接受正的安全整数运行 ID。
 * 拒绝小数、非数值、布尔、0、负数、空串与超出安全整数范围的值；无效 ID 不发起任何请求。
 */
function parseRunId(raw: unknown): number | null {
  if (typeof raw !== 'number' && typeof raw !== 'string') return null;
  if (typeof raw === 'string' && !raw.trim()) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

const runId = computed(() => parseRunId(route.params.id));
// 部署记录/发布单入口沿用现有权限定义，不因存在 releaseId 就授予
const canViewRelease = computed(() => authStore.canAccess(['RELEASE_VIEW', 'SYSTEM_ADMIN']));

type RunStatusKind = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PENDING' | 'QUEUED' | 'CANCELED' | 'SKIPPED' | 'UNKNOWN';

const RUN_STATUS_KINDS: RunStatusKind[] = ['RUNNING', 'SUCCESS', 'FAILED', 'PENDING', 'QUEUED', 'CANCELED', 'SKIPPED'];

function runStatusKind(raw: unknown): RunStatusKind {
  if (typeof raw !== 'string') return 'UNKNOWN';
  const normalized = raw.trim().toUpperCase();
  const canonical = normalized === 'CANCELLED' ? 'CANCELED' : normalized;
  return (RUN_STATUS_KINDS as string[]).includes(canonical) ? (canonical as RunStatusKind) : 'UNKNOWN';
}

/** 已知状态按当前语言渲染；未知或缺失保留原始值并显示"无法确认" */
function runStatusText(raw: unknown): string {
  const kind = runStatusKind(raw);
  if (kind === 'UNKNOWN') {
    return rawRunStatus(raw);
  }
  const suffix = kind.charAt(0) + kind.slice(1).toLowerCase();
  return t(`pipelineRuns.status${suffix}`);
}

function rawRunStatus(raw: unknown): string {
  if (typeof raw === 'string' && raw.trim()) return raw;
  return t('pipelineRuns.statusMissing');
}

function runStatusTagType(raw: unknown): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  const kind = runStatusKind(raw);
  if (kind === 'SUCCESS') return 'success';
  if (kind === 'FAILED') return 'danger';
  if (kind === 'RUNNING') return 'primary';
  return 'info';
}

function durationText(raw: unknown): string {
  if (raw === null || raw === undefined || raw === '') {
    return t('pipelineRuns.durationUnconfirmed');
  }
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    return t('pipelineRuns.durationUnconfirmed');
  }
  if (value < 1000) return t('pipelineRuns.durationMilliseconds', { ms: Math.round(value) });
  if (value < 60000) return t('pipelineRuns.durationSeconds', { seconds: Math.round(value / 1000) });
  if (value < 3600000) {
    return t('pipelineRuns.durationMinutesSeconds', { minutes: Math.floor(value / 60000), seconds: Math.round((value % 60000) / 1000) });
  }
  return t('pipelineRuns.durationHoursMinutes', { hours: Math.floor(value / 3600000), minutes: Math.round((value % 3600000) / 60000) });
}

function readFailureReason(error: unknown): string | null {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  const responseMessage = (error as { response?: { data?: { message?: unknown } } } | null)?.response?.data?.message;
  if (typeof responseMessage === 'string' && responseMessage.trim()) {
    return responseMessage;
  }
  return null;
}

function toReadFailure(error: unknown): ReadFailure {
  return { reason: readFailureReason(error) };
}

function readFailureText(failure: ReadFailure | null, fallbackKey: string): string | null {
  if (!failure) return null;
  return failure.reason ?? t(fallbackKey);
}

function friendlyDiagnosticError(reason: string): string {
  if (reason.includes('运行记录不存在')) {
    return t('runSteps.runNotFound');
  }
  return '';
}

// —— 运行上下文通道：目标 = 当前运行 ID ——
const runContext = ref<Record<string, unknown> | null>(null);
const runContextLoading = ref(false);
const runContextFailure = ref<ReadFailure | null>(null);
const runFinished = computed(() => ['SUCCESS', 'FAILED', 'CANCELED', 'SKIPPED'].includes(runStatusKind(runContext.value?.runStatus)));
let runContextSeq = 0;

const runContextRawError = computed(() => runContextFailure.value?.reason ?? null);
const runContextFailureAdvice = computed(() => (runContextFailure.value?.reason ? friendlyDiagnosticError(runContextFailure.value.reason) : ''));

/** 只有运行上下文读取成功且确有关联发布单时才有 releaseId；读取失败不得推出"未关联" */
const releaseId = computed(() => {
  const value = Number(runContext.value?.releaseId);
  return Number.isFinite(value) && value > 0 ? value : null;
});

async function loadRunContext(silent: boolean) {
  const targetId = runId.value;
  const requestId = ++runContextSeq;
  const isCurrent = () => requestId === runContextSeq && runId.value === targetId;
  if (!targetId) {
    runContext.value = null;
    runContextFailure.value = null;
    runContextLoading.value = false;
    return;
  }
  if (!silent) runContextLoading.value = true;
  try {
    const context = await getPipelineRun(targetId);
    if (!isCurrent()) return;
    // 拒绝作用域不符的返回：运行记录的 ID 必须与当前目标一致
    if (Number(context.id) !== targetId) {
      runContext.value = null;
      runContextFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    runContext.value = context;
    runContextFailure.value = null;
  } catch (error) {
    if (!isCurrent()) return;
    runContextFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      runContextLoading.value = false;
    }
  }
}

/** 刷新运行信息：同时更新运行上下文与步骤列表（只读，不重跑流水线） */
async function refreshRunContext() {
  await Promise.all([loadRunContext(false), crudPageRef.value?.fetchData()]);
}

watch(
  () => runId.value != null && runStatusKind(runContext.value?.runStatus) === 'RUNNING' && !runContextFailure.value,
  (running, _, onCleanup) => {
    if (!running) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        if (!runContextLoading.value) {
          await Promise.all([loadRunContext(true), crudPageRef.value?.fetchData({ silent: true })]);
        }
      } catch {
        // Keep manual refresh available after a failed step list request.
        return;
      }
      if (active) timer = setTimeout(refresh, 2000);
    }
    timer = setTimeout(refresh, 2000);
    onCleanup(() => {
      active = false;
      clearTimeout(timer);
    });
  },
);

// —— 部署记录通道：目标 = 当前运行 ID + 该运行的 releaseId ——
const deployRecordsVisible = ref(false);
const deployRecordsLoading = ref(false);
const deployRecords = ref<Record<string, unknown>[]>([]);
const deployRecordsFailure = ref<ReadFailure | null>(null);
const deployRecordsTargetRunId = ref<number | null>(null);
const deployRecordsTargetReleaseId = ref<number | null>(null);
let recordsSeq = 0;
const deployRecordsError = computed(() => readFailureText(deployRecordsFailure.value, 'runSteps.deployRecordsReadFailed'));

async function loadDeployRecords() {
  const targetRunId = deployRecordsTargetRunId.value;
  const targetReleaseId = deployRecordsTargetReleaseId.value;
  if (!targetRunId || !targetReleaseId) return;
  const requestId = ++recordsSeq;
  const isCurrent = () => requestId === recordsSeq
    && deployRecordsTargetRunId.value === targetRunId
    && deployRecordsTargetReleaseId.value === targetReleaseId;
  deployRecordsLoading.value = true;
  try {
    const list = await getReleaseDeployRecords(targetReleaseId);
    if (!isCurrent()) return;
    deployRecords.value = list;
    deployRecordsFailure.value = null;
  } catch (error) {
    if (!isCurrent()) return;
    // 读取失败不等于没有部署记录：保留已读到的内容并显式标注本次读取失败
    deployRecordsFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      deployRecordsLoading.value = false;
    }
  }
}

async function showDeployRecords() {
  if (!canViewRelease.value) {
    ElMessage.warning(t('runSteps.deployRecordsDenied'));
    return;
  }
  const targetRunId = runId.value;
  const targetReleaseId = releaseId.value;
  if (!targetRunId) {
    ElMessage.warning(t('runSteps.invalidRunId'));
    return;
  }
  if (runContextFailure.value) {
    // 运行信息未知时不猜测发布单目标：先修复运行信息读取
    ElMessage.warning(t('runSteps.runContextFailed'));
    return;
  }
  if (!targetReleaseId) {
    ElMessage.warning(t('runSteps.recordsMissingTarget'));
    return;
  }
  const identityChanged = deployRecordsTargetRunId.value !== targetRunId
    || deployRecordsTargetReleaseId.value !== targetReleaseId;
  deployRecordsTargetRunId.value = targetRunId;
  deployRecordsTargetReleaseId.value = targetReleaseId;
  if (identityChanged) {
    deployRecords.value = [];
    deployRecordsFailure.value = null;
  }
  deployRecordsVisible.value = true;
  await loadDeployRecords();
}

/** 只读重试：仍然绑定当前的运行 ID 与 releaseId */
async function retryDeployRecords() {
  await loadDeployRecords();
}

/** 查看发布单：只携带发布单列表已支持的筛选参数，不宣称未实现的筛选 */
function navigateToReleaseOrder() {
  const query: Record<string, string> = {};
  const appId = runContext.value?.appId;
  const versionId = runContext.value?.versionId;
  if (appId) query.appId = String(appId);
  if (versionId) query.versionId = String(versionId);
  router.push({ path: '/release/orders', query });
}

// —— 日志摘录只读视图 ——
const excerptVisible = ref(false);
const excerptText = ref('');
const excerptStepLabel = ref('');
const logTarget = ref<{ id?: number; label: string } | null>(null);

function openRunLog() {
  if (runId.value) logTarget.value = { label: `${t('runSteps.runIdLabel')} #${runId.value}` };
}

function openFullLog(row: Record<string, unknown>) {
  const id = parseRunId(row.id);
  if (id && runId.value) {
    const label = [row.stageName, row.stepName].filter(Boolean).join(' / ') || `#${id}`;
    logTarget.value = { id, label };
  }
}

function excerptPreview(raw: unknown): string {
  if (typeof raw !== 'string' || !raw) return '-';
  const single = raw.replace(/\s+/g, ' ').trim();
  return single.length > 60 ? `${single.slice(0, 60)}…` : single;
}

function openLogExcerpt(row: Record<string, unknown>) {
  const name = typeof row.stepName === 'string' && row.stepName ? row.stepName : `#${row.id ?? '-'}`;
  excerptStepLabel.value = name;
  excerptText.value = typeof row.logExcerpt === 'string' ? row.logExcerpt : '';
  excerptVisible.value = true;
}

/** 原样复制摘录内容，不做任何改写或截断提示以外处理 */
async function copyExcerpt() {
  if (!excerptText.value) {
    ElMessage.warning(t('runSteps.logExcerptCopyEmpty'));
    return;
  }
  try {
    await navigator.clipboard.writeText(excerptText.value);
    ElMessage.success(t('runSteps.logExcerptCopied'));
  } catch {
    ElMessage.error(t('runSteps.logExcerptCopyFailed'));
  }
}

async function loadStepPage(params: Record<string, unknown>) {
  const target = runId.value;
  const requestId = ++stepsSeq;
  if (!target) return {
    pageNum: Number(params.pageNum ?? 1), pageSize: Number(params.pageSize ?? 10), total: 0, records: [],
  };
  const read = (query: Record<string, unknown>) => pipelineStepRunApi.page({ ...query, pipelineRunId: target });
  const isCurrent = () => runId.value === target && requestId === stepsSeq;
  const page = await read(params);
  if (isCurrent()) {
    const rows = Number(page.pageNum) === 1 && Number(page.total) <= page.records.length
      ? page.records
      : await listPipelineCatalog(read);
    if (isCurrent()) stageRows.value = rows;
  }
  return page;
}

const config = computed(() => ({
  title: t('pages.stepRuns.title'),
  description: t('pages.stepRuns.desc'),
  api: {
    ...pipelineStepRunApi,
    // 无效运行 ID 时短路为空页：不构造 undefined/NaN 参数，也不发起步骤请求。
    // 有效 ID 时以当前校验后的运行 ID 覆盖 pipelineRunId：目标切换当帧 props.config 尚未更新，
    // 不能让步骤请求沿用上一个运行 ID。
    page: loadStepPage,
  },
  columns: [
    { prop: 'id', label: t('fields.id'), width: 90 },
    { prop: 'stepId', label: t('fields.stepId'), width: 100 },
    { prop: 'stageName', label: t('fields.stageName'), minWidth: 140 },
    { prop: 'stepName', label: t('fields.stepName'), minWidth: 160 },
    {
      prop: 'runStatus',
      label: t('runSteps.stepsStatusLabel'),
      width: 140,
      slot: 'runStatus',
      headerTooltip: t('runSteps.statusSeparateHint'),
    },
    { prop: 'executorNode', label: t('fields.executorNode'), minWidth: 140 },
    { prop: 'startTime', label: t('fields.startTime'), minWidth: 180, formatter: formatDateTime },
    { prop: 'endTime', label: t('fields.endTime'), minWidth: 180, formatter: formatDateTime },
    { prop: 'durationMs', label: t('fields.durationMs'), width: 120, slot: 'duration' },
    { prop: 'logExcerpt', label: t('runSteps.logColumn'), minWidth: 300, slot: 'logExcerpt', overflowTooltip: false },
    { prop: 'errorMessage', label: t('runSteps.errorColumn'), minWidth: 220 },
  ],
  extraQuery: runId.value ? { pipelineRunId: runId.value } : {},
  createAuthority: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'],
  editAuthority: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'],
  deleteAuthority: ['PIPELINE_EXECUTE', 'SYSTEM_ADMIN'],
}));

/** 目标（运行 ID）切换：立即失效旧读取、清除旧上下文、关闭旧证据对话框 */
watch(
  () => route.params.id,
  () => {
    runContextSeq += 1;
    recordsSeq += 1;
    runContext.value = null;
    runContextFailure.value = null;
    runContextLoading.value = false;
    deployRecordsVisible.value = false;
    deployRecordsLoading.value = false;
    deployRecords.value = [];
    deployRecordsFailure.value = null;
    deployRecordsTargetRunId.value = null;
    deployRecordsTargetReleaseId.value = null;
    excerptVisible.value = false;
    excerptText.value = '';
    excerptStepLabel.value = '';
    logTarget.value = null;
    stepsSeq += 1;
    stageRows.value = [];
    // 运行上下文与步骤列表都必须跟随新目标重新读取
    void refreshRunContext();
  },
  { immediate: true },
);

watch(deployRecordsVisible, (visible) => {
  if (visible) return;
  recordsSeq += 1;
  deployRecordsLoading.value = false;
}, { flush: 'sync' });
</script>

<style scoped lang="scss">
.run-context-alert {
  margin-bottom: var(--ih-spacing-lg);
}

.alert-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ih-spacing-3);
  margin-top: var(--ih-spacing-3);
}

.read-failure-hint {
  margin-top: var(--ih-spacing-1);
  font-size: 12px;
  line-height: 1.6;
}

.raw-error-text {
  margin: var(--ih-spacing-3) 0 0;
  padding: var(--ih-spacing-3);
  max-height: 200px;
  overflow: auto;
  border-radius: var(--ih-radius-sm);
  background: var(--ih-bg-row-hover);
  color: var(--ih-text-primary);
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.read-again-hint {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.run-status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.run-status-raw {
  color: var(--ih-text-secondary);
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
}

.excerpt-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  max-width: 100%;
}

.excerpt-preview {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
}

.excerpt-cell :deep(.el-button + .el-button) {
  margin-left: 0;
}

.excerpt-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-3);
}

.excerpt-step {
  font-weight: 500;
}

.excerpt-notice {
  margin-bottom: var(--ih-spacing-3);
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.excerpt-body {
  min-height: 120px;
  max-height: 520px;
  overflow: auto;
  padding: var(--ih-spacing-5);
  margin: 0;
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-row-hover);
  color: var(--ih-text-primary);
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
