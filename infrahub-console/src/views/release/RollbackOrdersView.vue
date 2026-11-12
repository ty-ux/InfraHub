<template>
  <CrudPage v-if="config" ref="crudPageRef" :config="config">
    <template #filter-panel>
      <OrderFilterBar type="rollback" :initial-filters="initialFilters" @change="onFilterChange" />
    </template>
    <template #status="{ row }">
      <el-tag :type="getRollbackStatusTagType(String(row.status ?? ''))" size="small">
        {{ getRollbackStatusLabel(String(row.status ?? '')) }}
      </el-tag>
    </template>
    <template #latestDeployStatus="{ row }">
      <el-tag v-if="row.latestDeployStatus" :type="getDeployStatusTagType(String(row.latestDeployStatus))" size="small">
        {{ getDeployStatusLabel(String(row.latestDeployStatus)) }}
      </el-tag>
      <span v-else class="text-muted">{{ t('deployStatus.notStarted') }}</span>
    </template>
    <template #hasDeployRecords="{ row }">
      <el-tooltip v-if="row.hasDeployRecords" :content="t('rollbackOrders.tipHasDeployRecords')" placement="top">
        <el-icon class="record-indicator deploy-icon" @click="showDeployRecords(row)"><List /></el-icon>
      </el-tooltip>
      <span v-else class="text-muted">-</span>
    </template>
    <template #hasPrecheckRecords="{ row }">
      <el-tooltip v-if="row.hasPrecheckRecords" :content="t('rollbackOrders.tipHasPrecheckRecords')" placement="top">
        <el-icon class="record-indicator precheck-icon"><DocumentChecked /></el-icon>
      </el-tooltip>
      <span v-else class="text-muted">-</span>
    </template>
    <template #row-actions="{ row }">
      <div class="row-actions">
        <div class="row-action-buttons">
          <!-- V1.20: 详情始终为第一个入口（稳定入口，不随状态增减） -->
          <el-tooltip :content="t('rollbackOrders.tipDetail')" placement="top">
            <el-button link type="primary" @click="openDetail(row)">{{ t('rollbackOrders.actionDetail') }}</el-button>
          </el-tooltip>
          <!-- CREATED: 候选版本选择（仅明确支持的 SSH 回滚开放，判据与动作入口一致） -->
          <template v-if="canExecute && row.status === 'CREATED' && row.releaseOrderId && isRollbackSupported(row)">
            <el-tooltip :content="t('rollbackOrders.tipCandidate')" placement="top">
              <el-button link type="primary" @click="openCandidateDialog(row)">{{ t('rollbackOrders.actionCandidate') }}</el-button>
            </el-tooltip>
          </template>
          <!-- T-D: 目标保存失败后保留所选目标，行内重试（保存只使用回滚单 ID；仅 CREATED 允许更换目标） -->
          <el-tooltip v-if="canExecute && row.status === 'CREATED' && pendingCandidateByRowId[Number(row.id)]" :content="t('rollbackCandidate.retryHint')" placement="top">
            <el-button link type="warning" @click="retryCandidateTarget(row)">{{ t('rollbackCandidate.retry') }}</el-button>
          </el-tooltip>
          <!-- CREATED/APPROVED: 前置检查 → 执行回滚（当前状态下的唯一主操作） → 部署记录 -->
          <template v-if="canExecute && isExecutable(row)">
            <template v-if="isRollbackSupported(row)">
              <el-tooltip :content="t('rollbackOrders.tipPrecheck')" placement="top">
                <el-button link type="info" @click="precheckRow(row)">{{ t('rollbackOrders.actionPrecheck') }}</el-button>
              </el-tooltip>
              <el-button
                link
                type="primary"
                :class="{ 'is-row-primary-action': primaryActionKey(row) === 'execute' }"
                @click="executeRow(row)"
              >
                {{ t('rollbackOrders.actionExecute') }}
              </el-button>
            </template>
            <!-- 非“明确支持的 SSH 回滚”：区分“不支持”与“无法确定支持状态”并阻止提交，后端仍为最终门禁 -->
            <el-tooltip v-else :content="rollbackSupportHint(row)" placement="top">
              <span class="rollback-unsupported-actions">
                <el-tag :type="isRollbackUnsupported(row) ? 'danger' : 'warning'" size="small">
                  {{ isRollbackUnsupported(row) ? t('rollbackSupport.unsupportedTag') : t('rollbackSupport.unknownTag') }}
                </el-tag>
                <el-button link type="info" disabled>{{ t('rollbackOrders.actionPrecheck') }}</el-button>
                <el-button link disabled>{{ t('rollbackOrders.actionExecute') }}</el-button>
              </span>
            </el-tooltip>
          </template>
          <el-tooltip :content="t('rollbackOrders.tipDeployRecords')" placement="top">
            <el-button
              link
              type="info"
              :class="{ 'is-row-primary-action': primaryActionKey(row) === 'deploy-records' }"
              @click="showDeployRecords(row)"
            >
              {{ t('rollbackOrders.actionDeployRecords') }}
            </el-button>
          </el-tooltip>
          <!-- V-C: navigateToCurrentVersion per Contract Section 7 -->
          <el-tooltip v-if="row.appId && row.envId" :content="t('rollbackOrders.tipCurrentVersion')" placement="top">
            <el-button link type="primary" @click="navigateToCurrentVersion(row)">{{ t('rollbackOrders.actionCurrentVersion') }}</el-button>
          </el-tooltip>
        </div>
        <!-- V1.21/V1.20: 行级提示独立成行，不与按钮挤在一起 -->
        <div v-if="rowTipText(row)" class="row-action-tips">
          <el-tag :type="rowTipType(row)" size="small" class="status-tip">{{ rowTipText(row) }}</el-tag>
        </div>
      </div>
    </template>
  </CrudPage>

  <ReleaseDetailDrawer
    ref="detailDrawerRef"
    v-model="detailVisible"
    :id="selectedRowId"
    type="rollback"
    :recent-action="selectedRowId ? recentActionByRowId[selectedRowId]?.action : undefined"
    @open-precheck="onDetailOpenPrecheck"
    @open-deploy-records="onDetailOpenDeployRecords"
    @open-deploy-records-with-id="onDetailOpenDeployRecordsWithId"
  />
  <DeployRecordsDialog
    v-model="deployRecordsVisible"
    :records="deployRecords"
    :loading="deployRecordsLoading"
    :error="deployRecordsError"
    :selected-record-id="highlightedRecordId"
    @retry="retryDeployRecords"
  />
  <PrecheckDialog
    v-model="precheckVisible"
    :records="precheckRecords"
    :loading="precheckLoading"
    :precheck-summary="precheckSummary"
    :error="precheckError"
    :summary-error="precheckSummaryError"
    @retry-summary="retryPrecheckSummary"
  />

  <!-- T-D: Rollback candidate version selection dialog -->
  <RollbackCandidateDialog
    v-model="candidateDialogVisible"
    :release-order-id="candidateReleaseOrderId"
    :current-version-id="candidateCurrentVersionId"
    :current-version-no="candidateCurrentVersionNo"
    @select="onCandidateSelected"
  />

  <!-- T-D: Rollback execution confirmation dialog per Contract 8.5 -->
  <RollbackExecutionConfirmDialog
    v-model="executionConfirmVisible"
    :rollback-id="executionConfirmRow ? Number(executionConfirmRow.id) : null"
    :rollback-no="executionConfirmRow?.rollbackNo"
    :app-name="executionConfirmRow?.appName"
    :service-name="executionConfirmRow?.serviceName"
    :env-name="executionConfirmRow?.envName"
    :from-version-no="executionConfirmRow?.fromVersionNo || executionConfirmRow?.fromVersionDisplayName"
    :to-version-no="executionConfirmRow?.toVersionNo || executionConfirmRow?.toVersionDisplayName"
    :executor-type="executionConfirmRow?.executorType"
    :rollback-support-state="executionConfirmSupportState"
    :support-hint="executionConfirmSupportHint"
    :submitting="executionSubmitting"
    @confirm="doExecuteRollback"
  />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { DocumentChecked, List } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import CrudPage from '@/components/page/CrudPage.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import OrderFilterBar from '@/components/release/OrderFilterBar.vue';
import PrecheckDialog from '@/components/release/PrecheckDialog.vue';
import ReleaseDetailDrawer from '@/components/release/ReleaseDetailDrawer.vue';
import RollbackCandidateDialog from '@/components/release/RollbackCandidateDialog.vue';
import RollbackExecutionConfirmDialog from '@/components/release/RollbackExecutionConfirmDialog.vue';
import { useAuthStore } from '@/store/auth';
import {
  executeRollback,
  getRollbackDeployRecords,
  getRollbackPrecheckSummary,
  precheckRollback,
  updateRollbackTarget,
  type ReleasePrecheckInstanceResult,
  type PrecheckSummaryResponse,
  type ExecuteResult,
  type RollbackCandidateItem,
  type RollbackOrderExecutorSupport,
} from '@/api/release';
import { getPageConfig } from '@/views/configs/page-configs';

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const { t } = useI18n();
const crudPageRef = ref<InstanceType<typeof CrudPage>>();
const detailDrawerRef = ref<InstanceType<typeof ReleaseDetailDrawer>>();
const deployRecordsVisible = ref(false);
const deployRecordsLoading = ref(false);
const deployRecords = ref<Record<string, unknown>[]>([]);
const highlightedRecordId = ref<number | null>(null);
const precheckVisible = ref(false);
const precheckLoading = ref(false);
const precheckRecords = ref<ReleasePrecheckInstanceResult[]>([]);
const precheckSummary = ref<PrecheckSummaryResponse | null>(null);
const detailVisible = ref(false);
const selectedRowId = ref<number | null>(null);
const selectedDetailRow = ref<Record<string, unknown> | null>(null);
// V1.21: 行级"最近动作反馈"状态
const recentActionByRowId = ref<Record<number, { action: string; at: number }>>({});
// 部署记录：保留最近一次查询的单据，供只读重试使用；错误信息与"没有记录"区分。
// 每个读取通道用独立请求序号 + 目标身份快照，旧请求不得更新当前展示。
const deployRecordsRowId = ref<number | null>(null);
const deployRecordsFailure = ref<ReadFailure | null>(null);
let deployRecordsRequestSeq = 0;
const deployRecordsError = computed(() => readFailureText(deployRecordsFailure.value, 'deployRecords.readFailedRetry'));
// 前置检查：执行失败与摘要读取失败分别记录，避免被当成"没有记录"
const precheckRowId = ref<number | null>(null);
const precheckFailure = ref<ReadFailure | null>(null);
const precheckSummaryFailure = ref<ReadFailure | null>(null);
let precheckRequestSeq = 0;
let precheckSummaryRequestSeq = 0;
const precheckError = computed(() => readFailureText(precheckFailure.value, 'rollbackOrders.precheckFailedRetry'));
const precheckSummaryError = computed(() => readFailureText(precheckSummaryFailure.value, 'rollbackOrders.summaryReadFailedRetry'));

// T-D: Rollback candidate dialog state
// 发布单 ID 仅用于查询候选版本；回滚单 ID 仅用于保存目标版本，两者不得混用
const candidateDialogVisible = ref(false);
const candidateReleaseOrderId = ref<number | null>(null);
const candidateRollbackOrderId = ref<number | null>(null);
const candidateCurrentVersionId = ref<number | null>(null);
const candidateCurrentVersionNo = ref<string | undefined>();
// 打开对话框时的行快照：候选选择提交前复查回滚支持状态
const candidateRowSnapshot = ref<Record<string, unknown> | null>(null);
// 保存失败后按回滚单保留所选目标（不因列表刷新丢失），供行内重试
const pendingCandidateByRowId = ref<Record<number, RollbackCandidateItem>>({});

// T-D: Execution confirmation dialog state
const executionConfirmVisible = ref(false);
const executionConfirmRow = ref<Record<string, unknown> | null>(null);
const executionSubmitting = ref(false);

const initialFilters = computed<Record<string, unknown>>(() => {
  const keyword = queryStringDefault('keyword');
  return keyword ? { keyword } : {};
});
const activeFilters = ref<Record<string, unknown>>({ ...initialFilters.value });

// V1.26: Focus handling from workspace
const focusedRollbackNo = computed(() => queryStringDefault('focus'));

// V1.19: Check if using default focus sort (no explicit filters)
const isUsingDefaultFocusSort = computed(() => {
  // If any filter is active, don't use default focus sort
  const filters = activeFilters.value;
  const hasActiveFilters =
    filters.keyword ||
    filters.releaseOrderKeyword ||
    filters.status ||
    filters.appId ||
    filters.serviceId ||
    filters.envId ||
    filters.createdAtStart ||
    filters.createdAtEnd ||
    filters.hasFailureHint !== undefined ||
    filters.activeQuickView; // from OrderFilterBar

  return !hasActiveFilters;
});

const config = computed(() => {
  const base = getPageConfig('releaseRollbacks');
  if (!base) {
    return base;
  }

  // Build extraQuery with filters and optional sort params
  const extraQuery: Record<string, unknown> = { ...activeFilters.value };

  // V1.19: When using default focus sort (no explicit filters), add sortMode=focus
  // When filters are active, let backend use default createdAt DESC ordering
  if (isUsingDefaultFocusSort.value) {
    extraQuery.sortMode = 'focus';
  }

  return {
    ...base,
    api: historyOnly.value ? { ...base.api, create: undefined } : base.api,
    extraQuery,
    emptyText: isUsingDefaultFocusSort.value ? t('rollbackOrders.emptyDefault') : t('rollbackOrders.emptyFiltered'),
    // V1.26: Highlight row focused from workspace
    rowClassName: focusedRollbackNo.value
      ? ({ row }: { row: Record<string, unknown>; rowIndex: number }) =>
          String(row.rollbackNo) === focusedRollbackNo.value ? 'is-focused-from-workspace' : ''
      : undefined,
  };
});
const historyOnly = computed(() => route.query.history === '1');
const canExecute = computed(() => !historyOnly.value && authStore.canAccess(['ROLLBACK_EXECUTE', 'SYSTEM_ADMIN']));

function isExecutable(row: Record<string, unknown>) {
  return ['CREATED', 'APPROVED'].includes(String(row.status ?? ''));
}

// 读取失败信息统一取自异常原因；取不到原因时在渲染时回落到当前语言的固定说明
// （不把失败说成"没有数据"，也不把后端原因翻成别的语言）
interface ReadFailure {
  reason: string | null;
}

function readFailureReason(error: unknown): string | null {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  if (typeof error === 'string' && error.trim()) {
    return error;
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
  if (!failure) {
    return null;
  }
  return failure.reason ?? t(fallbackKey);
}

// V1.27: 每行按当前状态突出唯一主操作（同一行最多一个高亮）
type RowPrimaryAction = 'execute' | 'deploy-records' | '';

function primaryActionKey(row: Record<string, unknown>): RowPrimaryAction {
  const status = String(row.status ?? '');
  if (canExecute.value && isExecutable(row) && isRollbackSupported(row)) {
    return 'execute';
  }
  if (status === 'RUNNING' || status === 'FAILED') {
    return 'deploy-records';
  }
  return '';
}

// V1.21/V1.20: 行级提示（最近动作反馈优先于状态提示）
function rowTipText(row: Record<string, unknown>): string {
  const recent = recentActionByRowId.value[Number(row.id)];
  if (recent) {
    return recent.action;
  }
  const status = String(row.status ?? '');
  if (status === 'FAILED') {
    return t('rollbackOrders.tipViewDeployRecords');
  }
  if (status === 'RUNNING') {
    return t('rollbackOrders.tipRunningViewDeployRecords');
  }
  return '';
}

function rowTipType(row: Record<string, unknown>): 'success' | 'warning' | 'info' {
  if (recentActionByRowId.value[Number(row.id)]) {
    return 'success';
  }
  return String(row.status ?? '') === 'FAILED' ? 'warning' : 'info';
}

// 回滚边界：后端按原发布单的执行器类型判定并下发以下字段。
// 统一判据：仅 rollbackSupported 显式为 true 且执行器类型确定为 SSH 才允许提交；
// false、null、undefined 或类型无法确定一律 fail-closed，不得按缺失字段猜成 SSH（后端仍为最终门禁）。
type RollbackSupportState = 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN';

function rollbackSupport(row: Record<string, unknown>): RollbackOrderExecutorSupport {
  return {
    executorType: typeof row.executorType === 'string' ? row.executorType : null,
    rollbackSupported: typeof row.rollbackSupported === 'boolean' ? row.rollbackSupported : null,
    rollbackUnsupportedReason:
      typeof row.rollbackUnsupportedReason === 'string' ? row.rollbackUnsupportedReason : null,
  };
}

function rollbackExecutorType(row: Record<string, unknown>): string {
  return (rollbackSupport(row).executorType ?? '').trim().toUpperCase();
}

function rollbackSupportState(row: Record<string, unknown>): RollbackSupportState {
  const support = rollbackSupport(row);
  const type = rollbackExecutorType(row);
  if (support.rollbackSupported === true && type === 'SSH') {
    return 'SUPPORTED';
  }
  if (support.rollbackSupported === false || (type !== '' && type !== 'SSH')) {
    return 'UNSUPPORTED';
  }
  return 'UNKNOWN';
}

function isRollbackSupported(row: Record<string, unknown>) {
  return rollbackSupportState(row) === 'SUPPORTED';
}

function isRollbackUnsupported(row: Record<string, unknown>) {
  return rollbackSupportState(row) === 'UNSUPPORTED';
}

// 阻断提示：后端拒绝原因优先，其次按判据区分“不支持”与“无法确定支持状态”
function rollbackSupportHint(row: Record<string, unknown>) {
  const support = rollbackSupport(row);
  const reason = (support.rollbackUnsupportedReason ?? '').trim();
  if (reason) {
    return reason;
  }
  const type = rollbackExecutorType(row);
  if (isRollbackUnsupported(row)) {
    return type
      ? t('rollbackSupport.unsupportedByType', { type })
      : t('rollbackSupport.unsupportedUnknownType');
  }
  return type ? t('rollbackSupport.unknownState') : t('rollbackSupport.unknownType');
}

// 动作入口统一门禁：非“明确支持的 SSH 回滚”不调用任何回滚 API
function ensureRollbackSupported(row: Record<string, unknown>) {
  if (isRollbackSupported(row)) {
    return true;
  }
  ElMessage.error(t('rollbackSupport.blockedSubmit', { reason: rollbackSupportHint(row) }));
  return false;
}

// 确认执行弹窗与动作入口共用同一判据与提示
const executionConfirmSupportState = computed<RollbackSupportState | null>(() =>
  executionConfirmRow.value ? rollbackSupportState(executionConfirmRow.value) : null,
);
const executionConfirmSupportHint = computed(() =>
  executionConfirmRow.value ? rollbackSupportHint(executionConfirmRow.value) : '',
);

// V1.21: 统一动作成功反馈与刷新协同
interface ActionSuccessResult {
  message: string;
  recentAction?: string;
}

function getActionSuccessFeedback(action: string): ActionSuccessResult {
  const feedbackMap: Record<string, ActionSuccessResult> = {
    precheck: { message: t('rollbackOrders.feedbackPrecheck'), recentAction: t('rollbackOrders.feedbackPrecheckRecent') },
    execute: { message: t('rollbackOrders.feedbackExecute'), recentAction: t('rollbackOrders.feedbackExecuteRecent') },
  };
  return feedbackMap[action] || { message: t('rollbackOrders.feedbackFallback') };
}

function setRecentAction(rowId: number, action: string) {
  const timestamp = Date.now();
  recentActionByRowId.value[rowId] = { action, at: timestamp };
  // 8秒后清除提示
  setTimeout(() => {
    const current = recentActionByRowId.value[rowId];
    if (current && current.at === timestamp) {
      delete recentActionByRowId.value[rowId];
    }
  }, 8000);
}

async function refreshWithDetailSync(rowId: number | null) {
  await crudPageRef.value?.fetchData();
  // 如果详情抽屉正打开且对应同一条记录，刷新详情数据
  if (detailVisible.value && selectedRowId.value === rowId && rowId) {
    await detailDrawerRef.value?.loadDetail();
  }
}

async function executeRow(row: Record<string, unknown>) {
  if (!ensureRollbackSupported(row)) return;
  // T-D: Use enhanced execution confirmation dialog per Contract 8.5
  executionConfirmRow.value = row;
  executionConfirmVisible.value = true;
}

async function doExecuteRollback() {
  if (!executionConfirmRow.value) return;
  if (!ensureRollbackSupported(executionConfirmRow.value)) return;
  const id = Number(executionConfirmRow.value.id);
  executionSubmitting.value = true;
  try {
    const result = await executeRollback(id);
    if (result.approvalId) {
      ElMessage.success(t('rollbackOrders.approvalSubmitted'));
    } else {
      const feedback = getActionSuccessFeedback('execute');
      ElMessage.success(feedback.message);
      setRecentAction(id, feedback.recentAction!);
      await refreshWithDetailSync(id);
    }
    executionConfirmVisible.value = false;
  } finally {
    executionSubmitting.value = false;
  }
}

// T-D: Open candidate dialog for rollback order creation
function openCandidateDialog(row: Record<string, unknown>) {
  if (!ensureRollbackSupported(row)) return;
  // 发布单 ID 仅用于查询候选版本；缺失时不得回退成回滚单 ID
  const releaseOrderId = Number(row.releaseOrderId);
  if (!Number.isFinite(releaseOrderId) || releaseOrderId <= 0) {
    ElMessage.error(t('rollbackCandidate.missingReleaseOrder'));
    return;
  }
  // 回滚单 ID 仅用于保存目标版本
  const rollbackOrderId = Number(row.id);
  if (!Number.isFinite(rollbackOrderId) || rollbackOrderId <= 0) {
    ElMessage.error(t('rollbackCandidate.missingRollbackOrder'));
    return;
  }
  candidateReleaseOrderId.value = releaseOrderId;
  candidateRollbackOrderId.value = rollbackOrderId;
  candidateRowSnapshot.value = row;
  candidateCurrentVersionId.value = row.currentVersionId ? Number(row.currentVersionId) : null;
  candidateCurrentVersionNo.value = String(row.currentVersionNo || row.fromVersionNo || '');
  candidateDialogVisible.value = true;
}

// T-D: 保存所选回滚目标：只使用回滚单 ID 调用 PUT /api/rollbacks/{id}，发布单 ID 不参与保存
async function saveCandidateTarget(
  rollbackOrderId: number,
  candidate: RollbackCandidateItem,
  supportRow: Record<string, unknown>,
) {
  // 提交前继续检查回滚支持状态：不支持或无法确定时不调用保存接口
  if (!ensureRollbackSupported(supportRow)) return;
  try {
    await updateRollbackTarget(rollbackOrderId, { toVersionId: candidate.versionId });
  } catch {
    // 保存失败：保留所选目标，行内出现重试入口；拒绝原因由全局响应处理提示
    pendingCandidateByRowId.value[rollbackOrderId] = candidate;
    return;
  }
  // 保存已被后端确认：先清除待保存状态，之后任何刷新问题都不得把候选放回待保存
  delete pendingCandidateByRowId.value[rollbackOrderId];
  ElMessage.success(t('rollbackCandidate.saved', { versionNo: candidate.versionNo }));
  try {
    // 成功刷新目标展示（列表 + 已打开的详情抽屉）
    await refreshWithDetailSync(rollbackOrderId);
  } catch {
    // 刷新失败与保存结果无关：目标已落库，只提示展示未更新，可手动刷新
    ElMessage.warning(t('rollbackCandidate.savedRefreshFailed', { versionNo: candidate.versionNo }));
  }
}

// T-D: Handle candidate selection - save the selected target on the rollback order
async function onCandidateSelected(candidate: RollbackCandidateItem) {
  const rollbackOrderId = candidateRollbackOrderId.value;
  const supportRow = candidateRowSnapshot.value;
  if (!rollbackOrderId || !supportRow) {
    ElMessage.error(t('rollbackCandidate.missingRollbackOrder'));
    return;
  }
  await saveCandidateTarget(rollbackOrderId, candidate, supportRow);
}

// T-D: 保存失败后重试：用当前行再次复查回滚支持状态后重提
async function retryCandidateTarget(row: Record<string, unknown>) {
  const rollbackOrderId = Number(row.id);
  const candidate = pendingCandidateByRowId.value[rollbackOrderId];
  if (!Number.isFinite(rollbackOrderId) || rollbackOrderId <= 0 || !candidate) return;
  await saveCandidateTarget(rollbackOrderId, candidate, row);
}

async function showDeployRecords(row: Record<string, unknown>, highlightRecordId: number | null = null) {
  const targetId = Number(row.id);
  if (!targetId) return;
  const identityChanged = deployRecordsRowId.value !== targetId;
  deployRecordsRowId.value = targetId;
  highlightedRecordId.value = highlightRecordId;
  if (identityChanged) {
    // 切换对象：立即清空上一个对象的证据，绝不把别条单据的部署记录留在当前展示里
    deployRecords.value = [];
    deployRecordsFailure.value = null;
  }
  deployRecordsVisible.value = true;
  await loadDeployRecords();
}

// 部署记录读取：失败时给出原因，绝不被当成"没有部署记录"。
// 发起前固定"请求序号 + 目标身份"，返回时两者都还成立才允许更新当前展示；
// 旧请求的成功、失败与 finally 都不能覆盖当前单据的 records / error / loading / 高亮。
async function loadDeployRecords() {
  const targetId = deployRecordsRowId.value;
  if (!targetId) return;
  const requestId = ++deployRecordsRequestSeq;
  const isCurrentRequest = () => requestId === deployRecordsRequestSeq && deployRecordsRowId.value === targetId;
  deployRecordsVisible.value = true;
  deployRecordsLoading.value = true;
  try {
    const records = await getRollbackDeployRecords(targetId);
    if (!isCurrentRequest()) return;
    deployRecords.value = records;
    deployRecordsFailure.value = null;
  } catch (error) {
    if (!isCurrentRequest()) return;
    // 同一对象的读取失败保留已读到的证据，只显式标注本次读取失败
    deployRecordsFailure.value = toReadFailure(error);
  } finally {
    if (isCurrentRequest()) {
      deployRecordsLoading.value = false;
    }
  }
}

/** 只读重试：绑定当前对象的部署记录重新读取（仅 GET），不会重新执行回滚动作 */
async function retryDeployRecords() {
  await loadDeployRecords();
}

async function precheckRow(row: Record<string, unknown>) {
  if (!ensureRollbackSupported(row)) return;
  const id = Number(row.id);
  const requestId = ++precheckRequestSeq;
  const isCurrentRequest = () => requestId === precheckRequestSeq && precheckRowId.value === id;
  precheckRowId.value = id;
  precheckVisible.value = true;
  precheckLoading.value = true;
  precheckRecords.value = [];
  precheckSummary.value = null;
  precheckFailure.value = null;
  precheckSummaryFailure.value = null;
  try {
    let records: ReleasePrecheckInstanceResult[];
    try {
      // 前置检查是真实执行动作：失败时只展示原因，不提供重试入口
      records = await precheckRollback(id);
    } catch (error) {
      // 归属已经被切换对象或关闭弹窗带走时，旧执行结果不再更新当前展示
      if (isCurrentRequest()) {
        precheckFailure.value = toReadFailure(error);
      }
      return;
    }
    if (!isCurrentRequest()) return;
    precheckRecords.value = records;
    const feedback = getActionSuccessFeedback('precheck');
    ElMessage.success(feedback.message);
    setRecentAction(id, feedback.recentAction!);
    await refreshWithDetailSync(id);
    // 列表刷新期间可能已关闭弹窗或切换到别的对象：本次执行若已失效立即结束，
    // 绝不能带着旧身份进入摘要读取（那会作废新对象正在进行的摘要读取）
    if (!isCurrentRequest()) return;
    // V-C: Fetch structured precheck summary (Contract 3.2)
    await loadPrecheckSummary(id);
  } finally {
    if (isCurrentRequest()) {
      precheckLoading.value = false;
    }
  }
}

// 结构化摘要读取（GET）：失败与"没有摘要"分开表达，只提供只读重试。
// 摘要读取同样使用独立请求序号 + 目标身份快照，旧请求不得覆盖当前展示。
async function loadPrecheckSummary(id: number) {
  // 入口先拒绝失效目标：不递增摘要请求序号、也不发出 GET，
  // 旧流程因此无法作废当前对象正在进行的摘要读取
  if (precheckRowId.value !== id) return;
  const requestId = ++precheckSummaryRequestSeq;
  const isCurrentRequest = () => requestId === precheckSummaryRequestSeq && precheckRowId.value === id;
  try {
    const summary = await getRollbackPrecheckSummary(id);
    if (!isCurrentRequest()) return;
    precheckSummary.value = summary;
    precheckSummaryFailure.value = null;
  } catch (error) {
    if (!isCurrentRequest()) return;
    // 同一对象的摘要读取失败保留已读到的证据，只显式标注本次读取失败
    precheckSummaryFailure.value = toReadFailure(error);
  }
}

/** 只读重试结构化摘要：绑定当前对象仅重新读取（GET），不会重新执行前置检查 */
async function retryPrecheckSummary() {
  const id = precheckRowId.value;
  if (!id) return;
  const precheckSeqAtStart = precheckRequestSeq;
  precheckLoading.value = true;
  try {
    await loadPrecheckSummary(id);
  } finally {
    // 期间若已发起新的前置检查执行（或弹窗已关闭），加载态归新请求所有
    if (precheckSeqAtStart === precheckRequestSeq) {
      precheckLoading.value = false;
    }
  }
}

// 关闭弹窗即让在途读取失去更新当前展示的资格（flush: 'sync' 保证关闭当帧立刻失效）
watch(deployRecordsVisible, (visible) => {
  if (visible) return;
  deployRecordsRequestSeq += 1;
  deployRecordsLoading.value = false;
  highlightedRecordId.value = null;
}, { flush: 'sync' });

watch(precheckVisible, (visible) => {
  if (visible) return;
  precheckRequestSeq += 1;
  precheckSummaryRequestSeq += 1;
  precheckLoading.value = false;
}, { flush: 'sync' });

function openDetail(row: Record<string, unknown>) {
  selectedRowId.value = Number(row.id);
  selectedDetailRow.value = row;
  detailVisible.value = true;
}

// V-C: navigateToCurrentVersion per Contract Section 7
function navigateToCurrentVersion(row: Record<string, unknown>) {
  const appId = Number(row.appId);
  const envId = Number(row.envId);
  router.push({ path: '/release/current-versions', query: { appId: String(appId), envId: String(envId) } });
}

// 详情抽屉的预检入口：与列表入口共用 precheckRow 内的同一门禁
async function onDetailOpenPrecheck() {
  if (!selectedDetailRow.value) return;
  await precheckRow(selectedDetailRow.value);
}

async function onDetailOpenDeployRecords() {
  if (!selectedDetailRow.value) return;
  await showDeployRecords(selectedDetailRow.value, null);
}

async function onDetailOpenDeployRecordsWithId(recordId: number) {
  if (!selectedDetailRow.value) return;
  await showDeployRecords(selectedDetailRow.value, recordId);
}

function onFilterChange(filters: Record<string, unknown>) {
  activeFilters.value = { ...filters };
}

// T-D: Status tag helpers per Contract 8.2 color coding
// T-D: Status tag colors per Contract 8.2 (CREATED=蓝, APPROVING=橙, APPROVED=绿, REJECTED=红, RUNNING=蓝, SUCCESS=绿, FAILED=红)
// 蓝色用合法的 'primary'（与空字符串渲染结果一致），避免 el-tag 的 type 取值告警
function getRollbackStatusTagType(status: string): string {
  switch (status) {
    case 'CREATED': return 'primary';
    case 'APPROVING': return 'warning';
    case 'APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'RUNNING': return 'primary';
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    default: return 'info';
  }
}

// 回滚单与发布单共用同一套单据状态词表（releaseStatus），不另建重复文案
const ROLLBACK_STATUS_KEYS: Record<string, string> = {
  CREATED: 'releaseStatus.created',
  APPROVING: 'releaseStatus.approving',
  APPROVED: 'releaseStatus.approved',
  REJECTED: 'releaseStatus.rejected',
  RUNNING: 'releaseStatus.running',
  SUCCESS: 'releaseStatus.success',
  FAILED: 'releaseStatus.failed',
};

function getRollbackStatusLabel(status: string): string {
  const key = ROLLBACK_STATUS_KEYS[status];
  if (key) {
    return t(key);
  }
  // 未知状态保留后端原值并显式标注未知，绝不默认成成功
  return status ? t('releaseStatus.unknownRaw', { status }) : status;
}

function getDeployStatusTagType(status: string): string {
  switch (status) {
    case 'RUNNING': return 'warning';
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    default: return 'info';
  }
}

const DEPLOY_STATUS_KEYS: Record<string, string> = {
  RUNNING: 'deployStatus.running',
  SUCCESS: 'deployStatus.success',
  FAILED: 'deployStatus.failed',
  UNKNOWN: 'deployStatus.unknown',
};

function getDeployStatusLabel(status: string): string {
  const key = DEPLOY_STATUS_KEYS[status];
  if (key) {
    return t(key);
  }
  // 未知状态保留后端原值并显式标注未知，绝不默认成成功
  return status ? t('deployStatus.unknownRaw', { status }) : status;
}

function queryStringDefault(key: string) {
  const value = route.query[key];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}
</script>

<style scoped lang="scss">
// 行内动作：按钮一行，提示另起一行，避免提示与按钮挤在一起
.row-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ih-spacing-1);
}

.row-action-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--ih-spacing-1) var(--ih-spacing-3);
}

.row-action-tips {
  display: flex;
  justify-content: center;
}

// 每行唯一的主操作高亮（执行回滚 / 部署记录），不改变按钮自身的语义与权限
.is-row-primary-action {
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: var(--color-accent);
  font-weight: 500;
}

.status-tip {
  margin-left: 0;
}

.text-muted {
  color: var(--color-placeholder);
  font-size: var(--text-sm);
}

.record-indicator {
  font-size: var(--text-lg);
  cursor: pointer;
  transition: color var(--dur-short) var(--ease-out);

  &:hover {
    color: var(--color-accent);
  }
}

// 非 SSH 回滚单：禁用动作的 tooltip 承载层（禁用按钮不触发 el-tooltip）
.rollback-unsupported-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
}

.deploy-icon {
  color: var(--color-ok);
}

.precheck-icon {
  color: var(--color-cool);
}
</style>

<style lang="scss">
// V1.26: Workspace focus highlight (unscoped so el-table row class works)
.el-table .is-focused-from-workspace {
  background-color: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
</style>
