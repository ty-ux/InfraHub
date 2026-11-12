<template>
  <CrudPage v-if="config" ref="crudPageRef" :config="config">
    <template #filter-panel>
      <OrderFilterBar type="release" :initial-filters="initialFilters" @change="onFilterChange" />
    </template>
    <!-- T-C: Status colored tag slot (Contract 8.2) -->
    <template #status="{ row }">
      <el-tag :type="getReleaseStatusTagType(String(row.status ?? ''))" size="small">
        {{ getReleaseStatusLabel(String(row.status ?? '')) }}
      </el-tag>
    </template>
    <!-- T-C: Deploy status colored tag slot -->
    <template #deployStatus="{ row }">
      <template v-if="row.latestDeployStatus">
        <el-tag :type="getDeployStatusTagType(String(row.latestDeployStatus))" size="small">
          {{ getDeployStatusLabel(String(row.latestDeployStatus)) }}
        </el-tag>
      </template>
      <span v-else class="text-secondary">{{ t('deployStatus.notStarted') }}</span>
    </template>
    <!-- T-C: Pipeline indicator slot -->
    <template #pipelineIndicator="{ row }">
      <el-icon v-if="row.hasPipelineRun" class="indicator-icon has-records" @click="openPipelineRun(row)"><Promotion /></el-icon>
      <span v-else class="text-secondary">-</span>
    </template>
    <!-- T-C: Deploy record indicator slot (Contract 8.2) -->
    <template #deployRecordIndicator="{ row }">
      <el-icon v-if="row.hasDeployRecords" class="indicator-icon has-records" @click="showDeployRecords(row)"><List /></el-icon>
      <span v-else class="text-secondary">-</span>
    </template>

    <template #row-actions="{ row }">
      <div class="row-actions">
        <div class="row-action-buttons">
          <!-- V1.20: 详情始终为第一个入口（稳定入口，不随状态增减） -->
          <el-tooltip :content="t('releaseOrders.tipDetail')" placement="top">
            <el-button link type="primary" @click="openDetail(row)">{{ t('releaseOrders.actionDetail') }}</el-button>
          </el-tooltip>
          <!-- CREATED/APPROVED: 前置检查 → 执行发布（当前状态下的唯一主操作） → 部署记录 -->
          <template v-if="canExecute && isExecutable(row)">
            <el-tooltip :content="t('releaseOrders.tipPrecheck')" placement="top">
              <el-button link type="info" @click="precheckRow(row)">{{ t('releaseOrders.actionPrecheck') }}</el-button>
            </el-tooltip>
            <!-- T-C: Execute uses enhanced confirmation dialog -->
            <el-button
              link
              type="primary"
              :class="{ 'is-row-primary-action': primaryActionKey(row) === 'execute' }"
              @click="openExecuteConfirm(row)"
            >
              {{ t('releaseOrders.actionExecute') }}
            </el-button>
          </template>
          <el-tooltip :content="t('releaseOrders.tipDeployRecords')" placement="top">
            <el-button
              link
              type="info"
              :class="{ 'is-row-primary-action': primaryActionKey(row) === 'deploy-records' }"
              @click="showDeployRecords(row)"
            >
              {{ t('releaseOrders.actionDeployRecords') }}
            </el-button>
          </el-tooltip>
          <!-- 次要动作（配置检查、流水线发布）收进「更多」：显示条件、权限判据与处理函数保持原样 -->
          <el-dropdown
            v-if="hasMoreActions(row)"
            trigger="click"
            placement="bottom-end"
            class="row-more"
          >
            <el-button link type="info" class="row-more-trigger">{{ t('releaseOrders.actionMore') }}</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="row.appId && row.envId" @click="checkRowDeployConfig(row)">
                  {{ t('releaseOrders.actionConfigCheck') }}
                </el-dropdown-item>
                <el-dropdown-item v-if="canExecute && isExecutable(row)" @click="openPipelineDialog(row)">
                  {{ t('releaseOrders.actionPipelineRelease') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <!-- E1: 灰度发布操作按钮 (仅 CANARY 类型显示) -->
          <template v-if="row.releaseType === 'CANARY' && canExecute">
            <el-button
              v-if="!row.canaryPlanStatus || row.canaryPlanStatus === 'ROLLED_BACK'"
              link
              type="primary"
              @click="openCanaryPlanDialog(row)"
            >
              {{ t('releaseOrders.actionCanaryPlan') }}
            </el-button>
            <template v-else-if="row.canaryPlanStatus === 'CREATED' || row.canaryPlanStatus === 'PAUSED'">
              <el-button link type="primary" @click="startCanaryRelease(row)">{{ t('releaseOrders.actionCanaryStart') }}</el-button>
            </template>
            <template v-else-if="row.canaryPlanStatus === 'RUNNING'">
              <el-button link type="warning" @click="pauseCanaryRelease(row)">{{ t('releaseOrders.actionCanaryPause') }}</el-button>
            </template>
            <template v-else-if="row.canaryPlanStatus === 'WAITING_NEXT'">
              <el-button
                link
                type="primary"
                :class="{ 'is-row-primary-action': primaryActionKey(row) === 'next-batch' }"
                @click="continueCanaryRelease(row)"
              >
                {{ t('releaseOrders.actionCanaryNextBatch') }}
              </el-button>
            </template>
            <!-- E2: 门禁阻断态 -->
            <template v-else-if="row.canaryPlanStatus === 'BLOCKED'">
              <el-tag type="danger" size="small">{{ t('releaseOrders.actionCanaryBlocked') }}</el-tag>
              <el-button link type="primary" @click="openDetail(row)">{{ t('releaseOrders.actionCanaryViewReason') }}</el-button>
            </template>
          </template>
          <el-button v-if="canCancel && isCancelable(row)" link type="warning" @click="cancelRow(row)">
            {{ t('releaseOrders.actionCancel') }}
          </el-button>
          <!-- RUNNING/FAILED/SUCCESS: 流水线日志 -->
          <el-tooltip v-if="['RUNNING', 'FAILED', 'SUCCESS'].includes(String(row.status ?? ''))" :content="t('releaseOrders.tipPipelineLog')" placement="top">
            <el-button
              link
              type="info"
              :class="{ 'is-row-primary-action': primaryActionKey(row) === 'pipeline-log' }"
              @click="openPipelineRun(row)"
            >
              {{ t('releaseOrders.actionPipelineLog') }}
            </el-button>
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
    type="release"
    :recent-action="selectedRowId ? recentActionByRowId[selectedRowId]?.action : undefined"
    @open-precheck="onDetailOpenPrecheck"
    @open-deploy-records="onDetailOpenDeployRecords"
    @open-deploy-records-with-id="onDetailOpenDeployRecordsWithId"
    @check-deploy-config="onDetailCheckDeployConfig"
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
  <!-- T-C: Deploy config check dialog -->
  <DeployConfigCheckDialog v-model="configCheckVisible" :loading="configCheckLoading" :result="configCheckResult" />
  <!-- T-C: Execution confirmation dialog (Contract 8.5) -->
  <ExecuteConfirmDialog v-model="executeConfirmVisible" :row="executeConfirmRow" :loading="executeLoading" @confirm="confirmExecute" />

  <el-dialog v-model="pipelineDialogVisible" :title="t('releaseOrders.pipelineDialogTitle')" width="520px" @closed="resetPipelineDialog">
    <el-form :model="pipelineForm" label-width="110px">
      <el-form-item :label="t('releaseOrders.pipelineDialogRelease')">
        <el-input :model-value="String(selectedRelease?.orderNo ?? selectedRelease?.id ?? '-')" disabled />
      </el-form-item>
      <el-form-item :label="t('releaseOrders.pipelineDialogPipeline')" required>
        <el-select v-model="pipelineForm.pipelineId" filterable clearable style="width: 100%">
          <el-option
            v-for="option in pipelineOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="selectedPipelineOption?.realDeploy"
        :title="t('releaseOrders.pipelineRealDeployHint')"
        type="warning"
        show-icon
        :closable="false"
      />
    </el-form>
    <template #footer>
      <el-button @click="pipelineDialogVisible = false">{{ t('releaseOrders.actionBack') }}</el-button>
      <el-button type="primary" :loading="pipelineSubmitting" :disabled="pipelineForm.pipelineId == null" @click="submitPipelineRelease">
        {{ t('releaseOrders.actionPipelineRelease') }}
      </el-button>
    </template>
  </el-dialog>

  <!-- E1: 灰度计划创建对话框 -->
  <el-dialog v-model="canaryDialogVisible" :title="t('releaseOrders.canaryDialogTitle')" width="480px" @closed="resetCanaryDialog">
    <el-form :model="canaryForm" label-width="120px">
      <el-form-item :label="t('releaseOrders.pipelineDialogRelease')">
        <el-input :model-value="String(selectedRelease?.orderNo ?? selectedRelease?.id ?? '-')" disabled />
      </el-form-item>
      <el-form-item :label="t('releaseOrders.canaryBatchCount')" required>
        <el-input-number v-model="canaryForm.batchCount" :min="1" :max="10" />
        <div class="form-tip">{{ t('releaseOrders.canaryBatchCountHint') }}</div>
      </el-form-item>
      <el-form-item :label="t('releaseOrders.canaryBatchInterval')">
        <el-input-number v-model="canaryForm.batchIntervalSeconds" :min="0" :max="3600" />
        <div class="form-tip">{{ t('releaseOrders.canaryBatchIntervalHint') }}</div>
      </el-form-item>
      <el-form-item :label="t('releaseOrders.canaryAutoRollback')">
        <el-switch v-model="canaryForm.autoRollbackEnabled" />
        <div class="form-tip">{{ t('releaseOrders.canaryAutoRollbackHint') }}</div>
      </el-form-item>
      <!-- E2: 质量门禁配置 -->
      <el-form-item :label="t('releaseOrders.canaryQualityGate')">
        <el-switch v-model="canaryForm.qualityGateEnabled" />
        <div class="form-tip">{{ t('releaseOrders.canaryQualityGateHint') }}</div>
      </el-form-item>
      <el-form-item v-if="canaryForm.qualityGateEnabled" :label="t('releaseOrders.canaryMaxFiringAlerts')">
        <el-input-number v-model="canaryForm.maxFiringAlerts" :min="0" :max="100" />
        <div class="form-tip">{{ t('releaseOrders.canaryMaxFiringAlertsHint') }}</div>
      </el-form-item>
      <el-form-item v-if="canaryForm.qualityGateEnabled" :label="t('releaseOrders.canaryGateFailureAction')">
        <el-radio-group v-model="canaryForm.gateFailureAction">
          <el-radio value="BLOCK_AND_ROLLBACK">{{ t('releaseOrders.canaryGateBlockAndRollback') }}</el-radio>
          <el-radio value="BLOCK">{{ t('releaseOrders.canaryGateBlock') }}</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="canaryDialogVisible = false">{{ t('releaseOrders.actionBack') }}</el-button>
      <el-button type="primary" :loading="canarySubmitting" :disabled="canaryForm.batchCount < 1" @click="submitCanaryPlan">
        {{ t('releaseOrders.canarySubmit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { List, Promotion } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import CrudPage from '@/components/page/CrudPage.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import DeployConfigCheckDialog from '@/components/release/DeployConfigCheckDialog.vue';
import ExecuteConfirmDialog from '@/components/release/ExecuteConfirmDialog.vue';
import OrderFilterBar from '@/components/release/OrderFilterBar.vue';
import PrecheckDialog from '@/components/release/PrecheckDialog.vue';
import ReleaseDetailDrawer from '@/components/release/ReleaseDetailDrawer.vue';
import { useAuthStore } from '@/store/auth';
import {
  cancelRelease,
  executeRelease,
  executeReleasePipeline,
  getReleaseDeployRecords,
  precheckRelease,
  checkDeployConfig,
  getPrecheckSummary,
  createCanaryPlan,
  startCanary,
  continueCanary,
  pauseCanary,
  type ReleasePrecheckInstanceResult,
  type ExecuteResult,
  type CreateCanaryPlanPayload,
  type DeployConfigCheckResponse,
  type PrecheckSummaryResponse,
} from '@/api/release';
import { pipelineApi, pipelineHasReleaseDeployStep, pipelineRunApi } from '@/api/pipeline';
import { getPageConfig } from '@/views/configs/page-configs';

interface PipelineSelectOption {
  label: string;
  value: number;
  realDeploy: boolean;
}

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
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
// 该读取通道持有独立请求序号：切换单据或关闭弹窗后，旧请求的成功、失败、finally 一律失去更新当前展示的资格。
const deployRecordsRowId = ref<number | null>(null);
const deployRecordsFailure = ref<ReadFailure | null>(null);
let deployRecordsRequestSeq = 0;
const deployRecordsError = computed(() => readFailureText(deployRecordsFailure.value, 'deployRecords.readFailedRetry'));
// 前置检查：执行失败与摘要读取失败分别记录，避免被当成"没有记录"。
// 执行通道与摘要读取通道各自持有请求序号，互不干扰。
const precheckRowId = ref<number | null>(null);
const precheckFailure = ref<ReadFailure | null>(null);
const precheckSummaryFailure = ref<ReadFailure | null>(null);
let precheckRequestSeq = 0;
let precheckSummaryRequestSeq = 0;
const precheckError = computed(() => readFailureText(precheckFailure.value, 'releaseOrders.precheckFailedRetry'));
const precheckSummaryError = computed(() => readFailureText(precheckSummaryFailure.value, 'releaseOrders.summaryReadFailedRetry'));
const pipelineDialogVisible = ref(false);
const pipelineSubmitting = ref(false);
const selectedRelease = ref<Record<string, unknown> | null>(null);
const pipelineOptions = ref<PipelineSelectOption[]>([]);
const pipelineForm = ref<{ pipelineId?: number }>({});
const selectedPipelineOption = computed(() => pipelineOptions.value.find((option) => option.value === pipelineForm.value.pipelineId));

// T-C: Config check state
const configCheckVisible = ref(false);
const configCheckLoading = ref(false);
const configCheckResult = ref<DeployConfigCheckResponse | null>(null);

// T-C: Execute confirm dialog state
const executeConfirmVisible = ref(false);
const executeConfirmRow = ref<Record<string, unknown> | null>(null);
const executeLoading = ref(false);

// E1: 灰度发布相关状态
const canaryDialogVisible = ref(false);
const canarySubmitting = ref(false);
const canaryForm = ref<CreateCanaryPlanPayload>({
  batchCount: 2,
  batchIntervalSeconds: 0,
  autoRollbackEnabled: true,
  // E2: 质量门禁默认配置
  qualityGateEnabled: true,
  maxFiringAlerts: 0,
  gateFailureAction: 'BLOCK_AND_ROLLBACK',
});

const initialFilters = computed<Record<string, unknown>>(() => {
  const keyword = queryStringDefault('keyword');
  return keyword ? { keyword } : {};
});
const activeFilters = ref<Record<string, unknown>>({ ...initialFilters.value });

// V1.26: Focus handling from workspace
const focusedOrderNo = computed(() => queryStringDefault('focus'));

// V1.19: Check if using default focus sort (no explicit filters)
const isUsingDefaultFocusSort = computed(() => {
  // If any filter is active, don't use default focus sort
  const filters = activeFilters.value;
  const hasActiveFilters =
    filters.keyword ||
    filters.status ||
    filters.appId ||
    filters.serviceId ||
    filters.envId ||
    filters.createdAtStart ||
    filters.createdAtEnd ||
    filters.hasFailureHint !== undefined ||
    filters.hasPipelineRun !== undefined ||
    filters.activeQuickView; // from OrderFilterBar

  return !hasActiveFilters;
});

const config = computed(() => {
  const base = getPageConfig('releaseOrders');
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
    extraQuery,
    emptyText: isUsingDefaultFocusSort.value ? t('releaseOrders.emptyDefault') : t('releaseOrders.emptyFiltered'),
    defaultForm: {
      ...(base.defaultForm || {}),
      ...queryNumberDefault('appId'),
      ...queryNumberDefault('versionId'),
    },
    // V1.26: Highlight row focused from workspace
    rowClassName: focusedOrderNo.value
      ? ({ row }: { row: Record<string, unknown>; rowIndex: number }) =>
          String(row.orderNo) === focusedOrderNo.value ? 'is-focused-from-workspace' : ''
      : undefined,
  };
});
const canExecute = computed(() => authStore.canAccess(['RELEASE_EXECUTE', 'SYSTEM_ADMIN']));
const canCancel = computed(() => authStore.canAccess(['RELEASE_CANCEL', 'SYSTEM_ADMIN']));

function isExecutable(row: Record<string, unknown>) {
  return ['CREATED', 'APPROVED'].includes(String(row.status ?? ''));
}

function isCancelable(row: Record<string, unknown>) {
  return String(row.status ?? '') === 'CREATED';
}

/**
 * 读取/执行失败信息：优先保留异常或后端给出的原始原因（原始原因不改写、不翻译）；
 * 取不到原因时只记录"本次失败没有原因"，回退文案在渲染时按当前语言取值，
 * 不会被冻结成发生失败那一刻的语言。
 */
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
  if (!failure) return null;
  return failure.reason ?? t(fallbackKey);
}

// V1.27: 每行按当前状态突出唯一主操作（同一行最多一个高亮）
type RowPrimaryAction = 'execute' | 'deploy-records' | 'pipeline-log' | 'next-batch' | '';

function primaryActionKey(row: Record<string, unknown>): RowPrimaryAction {
  const status = String(row.status ?? '');
  if (canExecute.value && isExecutable(row)) {
    return 'execute';
  }
  if (canExecute.value && row.releaseType === 'CANARY' && row.canaryPlanStatus === 'WAITING_NEXT') {
    return 'next-batch';
  }
  if (status === 'RUNNING') {
    return row.hasPipelineRun ? 'pipeline-log' : 'deploy-records';
  }
  if (status === 'FAILED') {
    return 'deploy-records';
  }
  return '';
}

// V1.27: 「更多」菜单仅在确有次要动作时出现；判据与菜单项显示条件保持一致
function hasMoreActions(row: Record<string, unknown>) {
  return Boolean(row.appId && row.envId) || (canExecute.value && isExecutable(row));
}

// V1.21/V1.20: 行级提示（最近动作反馈优先于状态提示）
function rowTipText(row: Record<string, unknown>): string {
  const recent = recentActionByRowId.value[Number(row.id)];
  if (recent) {
    return recent.action;
  }
  const status = String(row.status ?? '');
  if (status === 'FAILED') {
    return t('releaseOrders.tipViewDeployRecords');
  }
  if (status === 'RUNNING' && row.hasPipelineRun) {
    return t('releaseOrders.tipViewPipelineLog');
  }
  if (status === 'RUNNING') {
    return t('releaseOrders.tipRunningViewDeployRecords');
  }
  return '';
}

function rowTipType(row: Record<string, unknown>): 'success' | 'warning' | 'info' {
  if (recentActionByRowId.value[Number(row.id)]) {
    return 'success';
  }
  return String(row.status ?? '') === 'FAILED' ? 'warning' : 'info';
}

// T-C: Release status tag type mapping (Contract 8.2)
// CREATED=蓝, APPROVING=橙, APPROVED=绿, REJECTED=红, RUNNING=蓝(动画), SUCCESS=绿, FAILED=红, ROLLED_BACK=灰
// 蓝色用合法的 'primary'（与空字符串渲染结果一致），避免 el-tag 的 type 取值告警
function getReleaseStatusTagType(status: string): string {
  switch (status) {
    case 'CREATED': return 'primary';
    case 'APPROVING': return 'warning';
    case 'APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'RUNNING': return 'primary';
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    case 'ROLLED_BACK': return 'info';
    default: return 'info';
  }
}

const RELEASE_STATUS_KEYS: Record<string, string> = {
  CREATED: 'releaseStatus.created',
  APPROVING: 'releaseStatus.approving',
  APPROVED: 'releaseStatus.approved',
  REJECTED: 'releaseStatus.rejected',
  RUNNING: 'releaseStatus.running',
  SUCCESS: 'releaseStatus.success',
  FAILED: 'releaseStatus.failed',
  ROLLED_BACK: 'releaseStatus.rolledBack',
};

function getReleaseStatusLabel(status: string): string {
  const key = RELEASE_STATUS_KEYS[status];
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

// V1.21: 统一动作成功反馈与刷新协同
interface ActionSuccessResult {
  message: string;
  recentAction?: string;
}

function getActionSuccessFeedback(action: string): ActionSuccessResult {
  const feedbackMap: Record<string, ActionSuccessResult> = {
    precheck: { message: t('releaseOrders.feedbackPrecheck'), recentAction: t('releaseOrders.feedbackPrecheckRecent') },
    execute: { message: t('releaseOrders.feedbackExecute'), recentAction: t('releaseOrders.feedbackExecuteRecent') },
    pipeline: { message: t('releaseOrders.feedbackPipeline'), recentAction: t('releaseOrders.feedbackPipelineRecent') },
    cancel: { message: t('releaseOrders.feedbackCancel'), recentAction: undefined },
  };
  return feedbackMap[action] || { message: t('releaseOrders.feedbackFallback') };
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

// T-C: Open execute confirm dialog (Contract 8.5)
function openExecuteConfirm(row: Record<string, unknown>) {
  executeConfirmRow.value = row;
  executeConfirmVisible.value = true;
}

async function confirmExecute() {
  if (!executeConfirmRow.value) return;
  const row = executeConfirmRow.value;
  const id = Number(row.id);
  executeLoading.value = true;
  try {
    const result = await executeRelease(id);
    if (result.approvalId) {
      ElMessage.success(t('releaseOrders.approvalSubmitted'));
    } else {
      const feedback = getActionSuccessFeedback('execute');
      ElMessage.success(feedback.message);
      setRecentAction(id, feedback.recentAction!);
      await refreshWithDetailSync(id);
    }
    executeConfirmVisible.value = false;
  } finally {
    executeLoading.value = false;
  }
}

async function cancelRow(row: Record<string, unknown>) {
  const id = Number(row.id);
  await ElMessageBox.confirm(
    t('releaseOrders.cancelConfirm', { orderNo: String(row.orderNo ?? id) }),
    t('releaseOrders.cancelTitle'),
    { type: 'warning' },
  );
  await cancelRelease(id);
  const feedback = getActionSuccessFeedback('cancel');
  ElMessage.success(feedback.message);
  await refreshWithDetailSync(id);
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
    const records = await getReleaseDeployRecords(targetId);
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

/** 只读重试：绑定当前对象的部署记录重新读取（仅 GET），不会重新执行发布动作 */
async function retryDeployRecords() {
  await loadDeployRecords();
}

async function precheckRow(row: Record<string, unknown>) {
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
      records = await precheckRelease(id);
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
    // V-B: Fetch structured precheck summary (Contract 3.2)
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
    const summary = await getPrecheckSummary(id);
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

// T-C: Deploy config check (Contract 2.1, 8.1)
async function checkRowDeployConfig(row: Record<string, unknown>) {
  const appId = Number(row.appId);
  const envId = Number(row.envId);
  if (!appId || !envId) {
    ElMessage.warning(t('releaseOrders.configCheckMissingTarget'));
    return;
  }
  configCheckVisible.value = true;
  configCheckLoading.value = true;
  configCheckResult.value = null;
  try {
    configCheckResult.value = await checkDeployConfig({ appId, envId, serviceId: row.serviceId ? Number(row.serviceId) : undefined });
  } catch {
    configCheckResult.value = null;
    ElMessage.error(t('releaseOrders.configCheckFailed'));
  } finally {
    configCheckLoading.value = false;
  }
}

async function openPipelineDialog(row: Record<string, unknown>) {
  selectedRelease.value = row;
  pipelineDialogVisible.value = true;
  const records: Record<string, unknown>[] = [];
  for (let pageNum = 1; pageNum <= 20; pageNum += 1) {
    const page = await pipelineApi.page({ pageNum, pageSize: 200 });
    records.push(...page.records);
    if (pageNum * page.pageSize >= page.total) {
      break;
    }
  }
  const appPipelineRecords = records.filter((record) => String(record.appId ?? '') === String(row.appId ?? '') && Boolean(record.enabled));
  pipelineOptions.value = appPipelineRecords.map((record) => ({
    label: String(record.pipelineName ?? record.pipelineCode ?? record.id ?? '-'),
    value: Number(record.id),
    realDeploy: false,
  }));
  pipelineOptions.value = await Promise.all(appPipelineRecords.map(async (record) => {
    const realDeploy = await pipelineHasReleaseDeployStep(Number(record.id));
    const baseLabel = String(record.pipelineName ?? record.pipelineCode ?? record.id ?? '-');
    return {
      label: realDeploy ? `${baseLabel}${t('releaseOrders.pipelineRealDeploySuffix')}` : baseLabel,
      value: Number(record.id),
      realDeploy,
    };
  }));
}

function resetPipelineDialog() {
  selectedRelease.value = null;
  pipelineOptions.value = [];
  pipelineForm.value = {};
}

async function submitPipelineRelease() {
  if (!selectedRelease.value || pipelineForm.value.pipelineId == null) {
    return;
  }
  pipelineSubmitting.value = true;
  try {
    const id = Number(selectedRelease.value.id);
    const result = await executeReleasePipeline(id, {
      pipelineId: pipelineForm.value.pipelineId,
    });
    const feedback = getActionSuccessFeedback('pipeline');
    ElMessage.success(feedback.message);
    setRecentAction(id, feedback.recentAction!);
    pipelineDialogVisible.value = false;
    await router.push(`/pipeline/runs/${result.pipelineRunId}/steps`);
    await refreshWithDetailSync(id);
  } finally {
    pipelineSubmitting.value = false;
  }
}

async function openPipelineRun(row: Record<string, unknown>) {
  const page = await pipelineRunApi.page({ pageNum: 1, pageSize: 1, releaseId: Number(row.id) });
  const run = page.records[0];
  if (!run?.id) {
    ElMessage.warning(t('releaseOrders.noPipelineRun'));
    return;
  }
  await router.push(`/pipeline/runs/${run.id}/steps`);
}

function openDetail(row: Record<string, unknown>) {
  selectedRowId.value = Number(row.id);
  selectedDetailRow.value = row;
  detailVisible.value = true;
}

async function onDetailOpenPrecheck() {
  if (!selectedDetailRow.value) return;
  precheckRow(selectedDetailRow.value);
}

async function onDetailOpenDeployRecords() {
  if (!selectedDetailRow.value) return;
  await showDeployRecords(selectedDetailRow.value, null);
}

async function onDetailOpenDeployRecordsWithId(recordId: number) {
  if (!selectedDetailRow.value) return;
  await showDeployRecords(selectedDetailRow.value, recordId);
}

// T-C: Config check from detail drawer
function onDetailCheckDeployConfig() {
  if (!selectedDetailRow.value) return;
  checkRowDeployConfig(selectedDetailRow.value);
}

function queryNumberDefault(key: string) {
  const value = Number(route.query[key]);
  return Number.isFinite(value) && value > 0 ? { [key]: value } : {};
}

function queryStringDefault(key: string) {
  const value = route.query[key];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function onFilterChange(filters: Record<string, unknown>) {
  activeFilters.value = { ...filters };
}

// E1: 灰度发布处理函数
async function openCanaryPlanDialog(row: Record<string, unknown>) {
  selectedRelease.value = row;
  canaryDialogVisible.value = true;
  canaryForm.value = {
    batchCount: 2,
    batchIntervalSeconds: 0,
    autoRollbackEnabled: true,
  };
}

function resetCanaryDialog() {
  selectedRelease.value = null;
  canaryForm.value = {
    batchCount: 2,
    batchIntervalSeconds: 0,
    autoRollbackEnabled: true,
    // E2: 质量门禁默认配置
    qualityGateEnabled: true,
    maxFiringAlerts: 0,
    gateFailureAction: 'BLOCK_AND_ROLLBACK',
  };
}

async function submitCanaryPlan() {
  if (!selectedRelease.value) return;
  canarySubmitting.value = true;
  try {
    const id = Number(selectedRelease.value.id);
    const plan = await createCanaryPlan(id, canaryForm.value);
    ElMessage.success(t('releaseOrders.canaryCreated', { count: plan.batchCount }));
    canaryDialogVisible.value = false;
    setRecentAction(id, t('releaseOrders.canaryCreatedRecent'));
    await refreshWithDetailSync(id);
  } finally {
    canarySubmitting.value = false;
  }
}

async function startCanaryRelease(row: Record<string, unknown>) {
  const id = Number(row.id);
  await ElMessageBox.confirm(
    t('releaseOrders.canaryStartConfirm', { orderNo: String(row.orderNo ?? id) }),
    t('releaseOrders.canaryStartTitle'),
    { type: 'warning' },
  );
  try {
    await startCanary(id);
    ElMessage.success(t('releaseOrders.canaryStarted'));
    setRecentAction(id, t('releaseOrders.canaryStartedRecent'));
    await refreshWithDetailSync(id);
  } catch (e) {
    ElMessage.error(String(e));
  }
}

async function continueCanaryRelease(row: Record<string, unknown>) {
  const id = Number(row.id);
  await ElMessageBox.confirm(t('releaseOrders.canaryNextConfirm'), t('releaseOrders.canaryNextTitle'), {
    type: 'info',
  });
  try {
    const plan = await continueCanary(id);
    if (plan.status === 'SUCCESS') {
      ElMessage.success(t('releaseOrders.canaryCompleted'));
    } else {
      ElMessage.success(t('releaseOrders.canaryNextStarted'));
    }
    setRecentAction(id, t('releaseOrders.canaryNextRecent'));
    await refreshWithDetailSync(id);
  } catch (e) {
    ElMessage.error(String(e));
  }
}

async function pauseCanaryRelease(row: Record<string, unknown>) {
  const id = Number(row.id);
  await ElMessageBox.confirm(t('releaseOrders.canaryPauseConfirm'), t('releaseOrders.canaryPauseTitle'), {
    type: 'warning',
  });
  try {
    await pauseCanary(id);
    ElMessage.success(t('releaseOrders.canaryPaused'));
    setRecentAction(id, t('releaseOrders.canaryPausedRecent'));
    await refreshWithDetailSync(id);
  } catch (e) {
    ElMessage.error(String(e));
  }
}

onMounted(async () => {
  if (route.query.create === '1') {
    await nextTick();
    await crudPageRef.value?.openCreate();
  }
});
</script>

<style scoped lang="scss">
// V1.27: 操作列分层——按钮行 + 独立提示行，窄屏（1024px）下按钮自动换行仍可用
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

// 当前状态下唯一突出的主操作：使用主题令牌，明暗主题一致
.is-row-primary-action {
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: var(--color-accent);
  font-weight: 500;
}

.row-more-trigger {
  color: var(--ih-text-secondary);
}

.status-tip {
  margin-left: 0;
}

.form-tip {
  margin-top: var(--space-3xs);
  color: var(--color-muted);
  font-size: var(--text-xs);
}

// T-C: Indicator icons
.indicator-icon {
  font-size: var(--text-lg);
  cursor: pointer;

  &.has-records {
    color: var(--color-cool);

    &:hover {
      color: var(--color-cool-hover);
    }
  }
}

.text-secondary {
  color: var(--color-muted);
  font-size: var(--text-sm);
}
</style>

<style lang="scss">
// V1.26: Workspace focus highlight (unscoped so el-table row class works)
.el-table .is-focused-from-workspace {
  background-color: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}
</style>
