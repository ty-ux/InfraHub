<template>
  <div class="governance-suggestions-tab">
    <div class="tab-header">
      <el-button
        type="primary"
        :loading="generating"
        @click="handleGenerateSuggestions"
      >
        {{ t('resourceGovernance.suggestions.generateSuggestions') }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="tableData" style="width: 100%">
      <el-table-column prop="suggestionNo" :label="t('resourceGovernance.suggestions.suggestionNo')" width="150">
        <template #default="{ row }">
          <span>{{ row.suggestionNo || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.suggestions.suggestionType')" width="130">
        <template #default="{ row }">
          <el-tag size="small" type="info">
            {{ getSuggestionTypeLabel(row.suggestionType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.suggestions.riskLevel')" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="getRiskLevelType(row.riskLevel)" effect="dark" size="small">
            {{ getRiskLevelLabel(row.riskLevel) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resourcePoolName" :label="t('resourceGovernance.suggestions.resourcePool')" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.resourcePoolName || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="envName" :label="t('resourceGovernance.suggestions.envName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="title" :label="t('resourceGovernance.suggestions.suggestionTitle')" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.title || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.suggestions.status')" width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)" size="small">
            {{ getStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.suggestions.createdAt')" width="160">
        <template #default="{ row }">
          {{ formatDate(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="280" fixed="right">
        <template #default="{ row }">
          <div class="action-buttons">
            <!-- Action Plan buttons: show for OPEN/ACKED status -->
            <el-button
              v-if="canGeneratePlan(row.status) && !row.existingPlan"
              type="primary"
              size="small"
              :loading="actionLoading[row.id + '-plan']"
              @click="handleGeneratePlan(row)"
            >
              {{ t('resourceGovernance.actionPlan.generatePlan') }}
            </el-button>
            <el-button
              v-if="row.existingPlan"
              type="info"
              size="small"
              @click="openPlanDrawer(row.existingPlan)"
            >
              {{ t('resourceGovernance.actionPlan.viewPlan') }}
            </el-button>
            <!-- Original suggestion action buttons -->
            <el-button
              v-if="canAck(row.status)"
              type="success"
              size="small"
              :loading="actionLoading[row.id + '-ack']"
              @click="handleAck(row)"
            >
              {{ t('resourceGovernance.suggestions.ack') }}
            </el-button>
            <el-button
              v-if="canIgnore(row.status)"
              type="warning"
              size="small"
              :loading="actionLoading[row.id + '-ignore']"
              @click="openHandleDialog(row, 'ignore')"
            >
              {{ t('resourceGovernance.suggestions.ignore') }}
            </el-button>
            <el-button
              v-if="canResolve(row.status)"
              type="primary"
              size="small"
              :loading="actionLoading[row.id + '-resolve']"
              @click="openHandleDialog(row, 'resolve')"
            >
              {{ t('resourceGovernance.suggestions.resolve') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="queryParams.pageNum"
        v-model:page-size="queryParams.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>

    <div v-if="error" class="error-message">
      <el-alert :title="error" type="error" show-icon :closable="false" />
    </div>

    <div v-if="!loading && tableData.length === 0 && !error" class="empty-message">
      <el-empty :description="t('resourceGovernance.suggestions.noSuggestions')" />
    </div>

    <el-dialog
      v-model="handleDialogVisible"
      :title="handleDialogTitle"
      width="500px"
      @close="closeHandleDialog"
    >
      <el-form :model="handleForm" label-width="80px">
        <el-form-item :label="t('resourceGovernance.suggestions.handleNote')">
          <el-input
            v-model="handleForm.handleNote"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.suggestions.handleNotePlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeHandleDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="handleDialogLoading" @click="submitHandleDialog">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Action Plan Drawer -->
    <el-drawer
      v-model="planDrawerVisible"
      :title="t('resourceGovernance.actionPlan.planDetail')"
      direction="rtl"
      size="500px"
      @close="closePlanDrawer"
    >
      <div v-if="currentPlan" class="plan-detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.planNo')">
            {{ currentPlan.planNo || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.actionType')">
            <el-tag size="small" type="info">{{ getActionTypeLabel(currentPlan.actionType) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.status')">
            <el-tag :type="getPlanStatusType(currentPlan.status)" size="small">
              {{ getPlanStatusLabel(currentPlan.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.riskLevel')">
            <el-tag :type="getRiskLevelType(currentPlan.riskLevel)" effect="dark" size="small">
              {{ getRiskLevelLabel(currentPlan.riskLevel) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.resourcePool')">
            {{ currentPlan.resourcePoolName || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.envName')">
            {{ currentPlan.envName || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.actionPlan.createdAt')">
            {{ formatDate(currentPlan.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentPlan.confirmedAt" :label="t('resourceGovernance.actionPlan.confirmedAt')">
            {{ formatDate(currentPlan.confirmedAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentPlan.cancelledAt" :label="t('resourceGovernance.actionPlan.cancelledAt')">
            {{ formatDate(currentPlan.cancelledAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentPlan.cancelReason" :label="t('resourceGovernance.actionPlan.cancelReason')">
            {{ currentPlan.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="plan-payload-section">
          <h4>{{ t('resourceGovernance.actionPlan.planPayload') }}</h4>
          <pre class="plan-payload-content">{{ formatPlanPayload(currentPlan.planPayload) }}</pre>
        </div>

        <!-- Timeline Entry -->
        <div class="timeline-entry-section" v-if="canViewTimeline(currentPlan.status)">
          <el-button
            type="primary"
            size="small"
            @click="openTimelineDrawer"
          >
            {{ t('resourceGovernance.timeline.viewTimeline') }}
          </el-button>
        </div>

        <!-- Execution Records Section -->
        <div class="execution-records-section">
          <div class="section-header">
            <h4>{{ t('resourceGovernance.actionPlan.executionRecords') }}</h4>
            <span class="hint-text">{{ t('resourceGovernance.actionPlan.noAutoExecuteText') }}</span>
          </div>

          <div v-if="hasRunningExecution" class="running-hint">
            <el-tag type="warning" size="small">{{ t('resourceGovernance.actionPlan.hasRunningExecution') }}</el-tag>
          </div>

          <el-table
            v-loading="executionsLoading"
            :data="executionRecords"
            size="small"
            style="width: 100%"
          >
            <el-table-column prop="executionNo" :label="t('resourceGovernance.actionPlan.executionNo')" width="120">
              <template #default="{ row }">
                {{ row.executionNo || '-' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.status')" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="getExecutionStatusType(row.status)" size="small">
                  {{ getExecutionStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.executionMode')" width="150">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ t('resourceGovernance.actionPlan.manualExecution') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operator" :label="t('resourceGovernance.actionPlan.operator')" width="120" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.operator || row.createdBy || '-' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.startedAt')" width="160">
              <template #default="{ row }">
                {{ formatDate(row.startedAt) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.finishedAt')" width="160">
              <template #default="{ row }">
                {{ formatDate(row.finishedAt) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.resultSummary')" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.resultSummary || '-' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.failureReason')" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.failureReason || '-' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.actionPlan.executionEvidence')" min-width="120">
              <template #default="{ row }">
                <el-popover
                  v-if="row.evidencePayload"
                  placement="left"
                  :width="400"
                  trigger="hover"
                >
                  <template #reference>
                    <el-button type="primary" link size="small">{{ t('resourceGovernance.actionPlan.viewPlan') }}</el-button>
                  </template>
                  <pre class="evidence-popover">{{ formatEvidencePayload(row.evidencePayload) }}</pre>
                </el-popover>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="200" fixed="right">
              <template #default="{ row }">
                <div class="action-buttons" v-if="row.status === 'RUNNING'">
                  <el-button type="primary" size="small" @click="openCompleteDialog(row)">
                    {{ t('resourceGovernance.actionPlan.markComplete') }}
                  </el-button>
                  <el-button type="danger" size="small" @click="openFailDialog(row)">
                    {{ t('resourceGovernance.actionPlan.markFailed') }}
                  </el-button>
                  <el-button type="warning" size="small" @click="openCancelExecutionDialog(row)">
                    {{ t('resourceGovernance.actionPlan.cancelExecution') }}
                  </el-button>
                </div>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="!executionsLoading && executionRecords.length === 0" class="empty-executions">
            <el-empty :description="t('resourceGovernance.actionPlan.noExecutions')" :image-size="60" />
          </div>
        </div>

        <div class="plan-actions">
          <el-button
            v-if="canStartExecution"
            type="primary"
            :loading="planActionLoading"
            @click="handleStartExecution"
          >
            {{ t('resourceGovernance.actionPlan.startExecution') }}
          </el-button>
          <el-button
            v-if="canConfirmPlan(currentPlan.status)"
            type="primary"
            :loading="planActionLoading"
            @click="openConfirmPlanDialog"
          >
            {{ t('resourceGovernance.actionPlan.confirmPlan') }}
          </el-button>
          <el-button
            v-if="canCancelPlan(currentPlan.status)"
            type="danger"
            :loading="planActionLoading"
            @click="openCancelPlanDialog"
          >
            {{ t('resourceGovernance.actionPlan.cancelPlan') }}
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- Confirm Plan Dialog -->
    <el-dialog
      v-model="confirmPlanDialogVisible"
      :title="t('resourceGovernance.actionPlan.confirmPlanTitle')"
      width="400px"
    >
      <p>{{ t('resourceGovernance.actionPlan.confirmPlanMessage') }}</p>
      <template #footer>
        <el-button @click="confirmPlanDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="planActionLoading" @click="handleConfirmPlan">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Cancel Plan Dialog -->
    <el-dialog
      v-model="cancelPlanDialogVisible"
      :title="t('resourceGovernance.actionPlan.cancelPlanTitle')"
      width="500px"
      @close="closeCancelPlanDialog"
    >
      <el-form :model="cancelPlanForm" label-width="80px">
        <el-form-item :label="t('resourceGovernance.actionPlan.cancelReason')" required>
          <el-input
            v-model="cancelPlanForm.cancelReason"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.cancelReasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeCancelPlanDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" :loading="planActionLoading" @click="handleCancelPlan">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Mark Complete Dialog -->
    <el-dialog
      v-model="completeDialogVisible"
      :title="t('resourceGovernance.actionPlan.markCompleteTitle')"
      width="500px"
      @close="closeCompleteDialog"
    >
      <el-form :model="completeForm" label-width="100px">
        <el-form-item :label="t('resourceGovernance.actionPlan.resultSummary')" required>
          <el-input
            v-model="completeForm.resultSummary"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.resultSummaryPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.actionPlan.executionEvidence')">
          <el-input
            v-model="completeForm.evidencePayload"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.evidencePayloadPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeCompleteDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="executionActionLoading" @click="handleMarkComplete">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Mark Failed Dialog -->
    <el-dialog
      v-model="failDialogVisible"
      :title="t('resourceGovernance.actionPlan.markFailedTitle')"
      width="500px"
      @close="closeFailDialog"
    >
      <el-form :model="failForm" label-width="100px">
        <el-form-item :label="t('resourceGovernance.actionPlan.failureReason')" required>
          <el-input
            v-model="failForm.failureReason"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.failureReasonPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.actionPlan.executionEvidence')">
          <el-input
            v-model="failForm.evidencePayload"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.evidencePayloadPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeFailDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" :loading="executionActionLoading" @click="handleMarkFailed">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Cancel Execution Dialog -->
    <el-dialog
      v-model="cancelExecutionDialogVisible"
      :title="t('resourceGovernance.actionPlan.cancelExecutionTitle')"
      width="500px"
      @close="closeCancelExecutionDialog"
    >
      <el-form :model="cancelExecutionForm" label-width="80px">
        <el-form-item :label="t('resourceGovernance.actionPlan.cancelReason')" required>
          <el-input
            v-model="cancelExecutionForm.cancelReason"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.actionPlan.cancelExecutionReasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeCancelExecutionDialog">{{ t('common.cancel') }}</el-button>
        <el-button type="warning" :loading="executionActionLoading" @click="handleCancelExecution">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Timeline Drawer -->
    <ExecutionTimelineDrawer
      v-model="timelineDrawerVisible"
      :plan-id="currentPlan?.id"
      :action-plan-no="currentPlan?.planNo"
      :suggestion-no="currentPlan?.suggestionNo"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  pageGovernanceSuggestions,
  generateSuggestions,
  ackGovernanceSuggestion,
  ignoreGovernanceSuggestion,
  resolveGovernanceSuggestion,
  createActionPlanFromSuggestion,
  getGovernanceActionPlan,
  confirmGovernanceActionPlan,
  cancelGovernanceActionPlan,
  listActionExecutions,
  startActionExecution,
  getActionExecution,
  completeActionExecution,
  failActionExecution,
  cancelActionExecution,
  type GovernanceSuggestion,
  type SuggestionStatus,
  type RiskLevel,
  type SuggestionType,
  type GovernanceActionPlan,
  type ActionPlanStatus,
  type ActionType,
  type GovernanceActionExecution,
  type ActionExecutionStatus
} from '@/api/asset'
import ExecutionTimelineDrawer from './ExecutionTimelineDrawer.vue'

const props = defineProps<{
  envId?: number;
  resourcePoolId?: number;
  riskLevel?: RiskLevel;
  suggestionType?: SuggestionType;
  status?: SuggestionStatus;
}>()

const emit = defineEmits<{
  (e: 'generated'): void;
}>()

const { t } = useI18n()

const loading = ref(false)
const generating = ref(false)
const error = ref('')
const tableData = ref<GovernanceSuggestion[]>([])
const total = ref(0)

const actionLoading = ref<Record<string, boolean>>({})

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10
})

const handleDialogVisible = ref(false)
const handleDialogTitle = ref('')
const handleDialogLoading = ref(false)
const handleForm = reactive({
  handleNote: ''
})
let currentHandleRow: GovernanceSuggestion | null = null
let currentHandleAction: 'ignore' | 'resolve' | null = null

// Action Plan state
const planDrawerVisible = ref(false)
const currentPlan = ref<GovernanceActionPlan | null>(null)
const planActionLoading = ref(false)
const confirmPlanDialogVisible = ref(false)
const cancelPlanDialogVisible = ref(false)
const cancelPlanForm = reactive({
  cancelReason: ''
})

// Execution Records state
const executionRecords = ref<GovernanceActionExecution[]>([])
const executionsLoading = ref(false)
const hasRunningExecution = computed(() => executionRecords.value.some(e => e.status === 'RUNNING'))

// Execution Dialogs state
const completeDialogVisible = ref(false)
const failDialogVisible = ref(false)
const cancelExecutionDialogVisible = ref(false)
const executionActionLoading = ref(false)
const currentExecution = ref<GovernanceActionExecution | null>(null)
const completeForm = reactive({
  resultSummary: '',
  evidencePayload: ''
})
const failForm = reactive({
  failureReason: '',
  evidencePayload: ''
})
const cancelExecutionForm = reactive({
  cancelReason: ''
})

// Timeline Drawer state
const timelineDrawerVisible = ref(false)

const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getRiskLevelType = (level?: RiskLevel) => {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    case 'NORMAL': return 'success'
    default: return 'info'
  }
}

const getRiskLevelLabel = (level?: RiskLevel) => {
  switch (level) {
    case 'CRITICAL': return t('resourceGovernance.riskLevel.CRITICAL')
    case 'WARNING': return t('resourceGovernance.riskLevel.WARNING')
    case 'NORMAL': return t('resourceGovernance.riskLevel.NORMAL')
    case 'UNKNOWN': return t('resourceGovernance.riskLevel.UNKNOWN')
    default: return '-'
  }
}

const getSuggestionTypeLabel = (type?: SuggestionType) => {
  switch (type) {
    case 'SCALE_OUT': return t('resourceGovernance.suggestions.typeScaleOut')
    case 'CHECK_HOST': return t('resourceGovernance.suggestions.typeCheckHost')
    case 'CLEANUP_INSTANCE': return t('resourceGovernance.suggestions.typeCleanupInstance')
    case 'INVESTIGATE_CAPACITY': return t('resourceGovernance.suggestions.typeInvestigateCapacity')
    default: return '-'
  }
}

const getStatusType = (status?: SuggestionStatus) => {
  switch (status) {
    case 'OPEN': return 'warning'
    case 'ACKED': return 'primary'
    case 'IGNORED': return 'info'
    case 'RESOLVED': return 'success'
    default: return 'info'
  }
}

const getStatusLabel = (status?: SuggestionStatus) => {
  switch (status) {
    case 'OPEN': return t('resourceGovernance.suggestions.statusOpen')
    case 'ACKED': return t('resourceGovernance.suggestions.statusAcked')
    case 'IGNORED': return t('resourceGovernance.suggestions.statusIgnored')
    case 'RESOLVED': return t('resourceGovernance.suggestions.statusResolved')
    default: return '-'
  }
}

const canAck = (status?: SuggestionStatus) => status === 'OPEN'
const canIgnore = (status?: SuggestionStatus) => status === 'OPEN' || status === 'ACKED'
const canResolve = (status?: SuggestionStatus) => status === 'OPEN' || status === 'ACKED'
const canGeneratePlan = (status?: SuggestionStatus) => status === 'OPEN' || status === 'ACKED'

// Action Plan helpers
const getActionTypeLabel = (type?: ActionType) => {
  switch (type) {
    case 'SCALE_OUT_PLAN': return t('resourceGovernance.actionPlan.actionTypeScaleOutPlan')
    case 'CHECK_HOST_PLAN': return t('resourceGovernance.actionPlan.actionTypeCheckHostPlan')
    case 'INVESTIGATE_CAPACITY_PLAN': return t('resourceGovernance.actionPlan.actionTypeInvestigateCapacityPlan')
    case 'CLEANUP_INSTANCE_PLAN': return t('resourceGovernance.actionPlan.actionTypeCleanupInstancePlan')
    default: return '-'
  }
}

const getPlanStatusType = (status?: ActionPlanStatus) => {
  switch (status) {
    case 'DRAFT': return 'info'
    case 'CONFIRMED': return 'primary'
    case 'CANCELLED': return 'info'
    case 'EXECUTED': return 'success'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

const getPlanStatusLabel = (status?: ActionPlanStatus) => {
  switch (status) {
    case 'DRAFT': return t('resourceGovernance.actionPlan.statusDraft')
    case 'CONFIRMED': return t('resourceGovernance.actionPlan.statusConfirmed')
    case 'CANCELLED': return t('resourceGovernance.actionPlan.statusCancelled')
    case 'EXECUTED': return t('resourceGovernance.actionPlan.statusExecuted')
    case 'FAILED': return t('resourceGovernance.actionPlan.statusFailed')
    default: return '-'
  }
}

const canConfirmPlan = (status?: ActionPlanStatus) => status === 'DRAFT'
const canCancelPlan = (status?: ActionPlanStatus) => status === 'DRAFT' || status === 'CONFIRMED'
const canViewTimeline = (status?: ActionPlanStatus) => status === 'CONFIRMED' || status === 'EXECUTED' || status === 'FAILED'

const openTimelineDrawer = () => {
  timelineDrawerVisible.value = true
}

const formatPlanPayload = (payload?: string | Record<string, unknown>) => {
  if (!payload) return '-'
  if (typeof payload === 'string') {
    try {
      const parsed = JSON.parse(payload)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return payload
    }
  }
  return JSON.stringify(payload, null, 2)
}

const loadData = async () => {
  loading.value = true
  error.value = ''

  try {
    const params: Record<string, unknown> = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.riskLevel) params.riskLevel = props.riskLevel
    if (props.suggestionType) params.suggestionType = props.suggestionType
    if (props.status) params.status = props.status

    const result = await pageGovernanceSuggestions(params)
    tableData.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    console.error('Failed to load governance suggestions:', err)
    error.value = t('resourceGovernance.suggestions.loadFailed')
  } finally {
    loading.value = false
  }
}

const handleGenerateSuggestions = async () => {
  if (!props.resourcePoolId) {
    ElMessage.warning(t('resourceGovernance.suggestions.selectResourcePoolFirst'))
    return
  }

  try {
    generating.value = true
    await generateSuggestions(props.resourcePoolId)
    ElMessage.success(t('resourceGovernance.suggestions.generateSuccess'))
    loadData()
    emit('generated')
  } catch (err) {
    console.error('Failed to generate suggestions:', err)
  } finally {
    generating.value = false
  }
}

const handleAck = async (row: GovernanceSuggestion) => {
  const key = `${row.id}-ack`
  actionLoading.value[key] = true
  try {
    await ackGovernanceSuggestion(row.id)
    ElMessage.success(t('resourceGovernance.suggestions.ackSuccess'))
    loadData()
  } catch (err) {
    console.error('Failed to ack suggestion:', err)
  } finally {
    actionLoading.value[key] = false
  }
}

const openHandleDialog = (row: GovernanceSuggestion, action: 'ignore' | 'resolve') => {
  currentHandleRow = row
  currentHandleAction = action
  handleForm.handleNote = ''
  handleDialogTitle.value = action === 'ignore'
    ? t('resourceGovernance.suggestions.ignoreTitle')
    : t('resourceGovernance.suggestions.resolveTitle')
  handleDialogVisible.value = true
}

const closeHandleDialog = () => {
  handleDialogVisible.value = false
  currentHandleRow = null
  currentHandleAction = null
  handleForm.handleNote = ''
}

const submitHandleDialog = async () => {
  if (!currentHandleRow || !currentHandleAction) return

  if (!handleForm.handleNote.trim()) {
    ElMessage.warning(t('resourceGovernance.suggestions.handleNoteRequired'))
    return
  }

  const key = `${currentHandleRow.id}-${currentHandleAction}`
  handleDialogLoading.value = true
  actionLoading.value[key] = true

  try {
    const payload = { handleNote: handleForm.handleNote }
    if (currentHandleAction === 'ignore') {
      await ignoreGovernanceSuggestion(currentHandleRow.id, payload)
      ElMessage.success(t('resourceGovernance.suggestions.ignoreSuccess'))
    } else {
      await resolveGovernanceSuggestion(currentHandleRow.id, payload)
      ElMessage.success(t('resourceGovernance.suggestions.resolveSuccess'))
    }
    closeHandleDialog()
    loadData()
  } catch (err) {
    console.error(`Failed to ${currentHandleAction} suggestion:`, err)
  } finally {
    handleDialogLoading.value = false
    actionLoading.value[key] = false
  }
}

// Action Plan handlers
const handleGeneratePlan = async (row: GovernanceSuggestion) => {
  const key = `${row.id}-plan`
  actionLoading.value[key] = true
  try {
    const plan = await createActionPlanFromSuggestion(row.id)
    ElMessage.success(t('resourceGovernance.actionPlan.generateSuccess'))
    currentPlan.value = plan
    planDrawerVisible.value = true
    loadData()
  } catch (err) {
    console.error('Failed to generate action plan:', err)
  } finally {
    actionLoading.value[key] = false
  }
}

const openPlanDrawer = async (plan: GovernanceActionPlan) => {
  try {
    const freshPlan = await getGovernanceActionPlan(plan.id)
    currentPlan.value = freshPlan
    planDrawerVisible.value = true
    await loadExecutionRecords()
  } catch (err) {
    console.error('Failed to load action plan:', err)
  }
}

const closePlanDrawer = () => {
  planDrawerVisible.value = false
  currentPlan.value = null
  executionRecords.value = []
}

const openConfirmPlanDialog = () => {
  confirmPlanDialogVisible.value = true
}

const handleConfirmPlan = async () => {
  if (!currentPlan.value) return
  planActionLoading.value = true
  try {
    await confirmGovernanceActionPlan(currentPlan.value.id)
    ElMessage.success(t('resourceGovernance.actionPlan.confirmSuccess'))
    confirmPlanDialogVisible.value = false
    const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
    currentPlan.value = freshPlan
    loadData()
  } catch (err) {
    console.error('Failed to confirm action plan:', err)
  } finally {
    planActionLoading.value = false
  }
}

const openCancelPlanDialog = () => {
  cancelPlanForm.cancelReason = ''
  cancelPlanDialogVisible.value = true
}

const closeCancelPlanDialog = () => {
  cancelPlanDialogVisible.value = false
  cancelPlanForm.cancelReason = ''
}

const handleCancelPlan = async () => {
  if (!currentPlan.value) return
  if (!cancelPlanForm.cancelReason.trim()) {
    ElMessage.warning(t('resourceGovernance.actionPlan.cancelReasonRequired'))
    return
  }
  planActionLoading.value = true
  try {
    await cancelGovernanceActionPlan(currentPlan.value.id, { cancelReason: cancelPlanForm.cancelReason })
    ElMessage.success(t('resourceGovernance.actionPlan.cancelSuccess'))
    closeCancelPlanDialog()
    const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
    currentPlan.value = freshPlan
    loadData()
  } catch (err) {
    console.error('Failed to cancel action plan:', err)
  } finally {
    planActionLoading.value = false
  }
}

// Execution Records helpers
const getExecutionStatusType = (status?: ActionExecutionStatus) => {
  switch (status) {
    case 'PENDING': return 'info'
    case 'RUNNING': return 'warning'
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'CANCELLED': return 'info'
    default: return 'info'
  }
}

const getExecutionStatusLabel = (status?: ActionExecutionStatus) => {
  switch (status) {
    case 'PENDING': return t('resourceGovernance.actionPlan.executionStatusPending')
    case 'RUNNING': return t('resourceGovernance.actionPlan.executionStatusRunning')
    case 'SUCCESS': return t('resourceGovernance.actionPlan.executionStatusSuccess')
    case 'FAILED': return t('resourceGovernance.actionPlan.executionStatusFailed')
    case 'CANCELLED': return t('resourceGovernance.actionPlan.executionStatusCancelled')
    default: return '-'
  }
}

const canStartExecution = computed(() => {
  return currentPlan.value?.status === 'CONFIRMED' && !hasRunningExecution.value
})

const loadExecutionRecords = async () => {
  if (!currentPlan.value) return
  executionsLoading.value = true
  try {
    const result = await listActionExecutions(currentPlan.value.id)
    executionRecords.value = result.records || []
  } catch (err) {
    console.error('Failed to load execution records:', err)
    ElMessage.error(t('resourceGovernance.actionPlan.loadExecutionsFailed'))
    executionRecords.value = []
  } finally {
    executionsLoading.value = false
  }
}

const handleStartExecution = async () => {
  if (!currentPlan.value) return
  planActionLoading.value = true
  try {
    const execution = await startActionExecution(currentPlan.value.id)
    ElMessage.success(t('resourceGovernance.actionPlan.startExecutionSuccess'))
    currentExecution.value = execution
    await loadExecutionRecords()
    const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
    currentPlan.value = freshPlan
    loadData()
  } catch (err) {
    console.error('Failed to start execution:', err)
  } finally {
    planActionLoading.value = false
  }
}

const openCompleteDialog = (execution: GovernanceActionExecution) => {
  currentExecution.value = execution
  completeForm.resultSummary = ''
  completeForm.evidencePayload = ''
  completeDialogVisible.value = true
}

const closeCompleteDialog = () => {
  completeDialogVisible.value = false
  currentExecution.value = null
  completeForm.resultSummary = ''
  completeForm.evidencePayload = ''
}

/**
 * Parse evidence payload input: JSON object or plain text wrapped as { text: ... }
 */
function parseEvidencePayload(input: string): Record<string, unknown> | undefined {
  const trimmed = input.trim()
  if (!trimmed) return undefined

  // Try to parse as JSON object
  try {
    const parsed = JSON.parse(trimmed)
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {
    // Not valid JSON - wrap as plain text
  }

  // Wrap plain text as { text: "..." }
  return { text: trimmed }
}

const handleMarkComplete = async () => {
  if (!currentExecution.value) return
  if (!completeForm.resultSummary.trim()) {
    ElMessage.warning(t('resourceGovernance.actionPlan.resultSummaryRequired'))
    return
  }
  executionActionLoading.value = true
  try {
    const evidencePayload = parseEvidencePayload(completeForm.evidencePayload)
    await completeActionExecution(currentExecution.value.id, {
      resultSummary: completeForm.resultSummary,
      evidencePayload
    })
    ElMessage.success(t('resourceGovernance.actionPlan.markCompleteSuccess'))
    closeCompleteDialog()
    await loadExecutionRecords()
    if (currentPlan.value) {
      const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
      currentPlan.value = freshPlan
    }
    loadData()
  } catch (err) {
    console.error('Failed to mark execution complete:', err)
  } finally {
    executionActionLoading.value = false
  }
}

const openFailDialog = (execution: GovernanceActionExecution) => {
  currentExecution.value = execution
  failForm.failureReason = ''
  failForm.evidencePayload = ''
  failDialogVisible.value = true
}

const closeFailDialog = () => {
  failDialogVisible.value = false
  currentExecution.value = null
  failForm.failureReason = ''
  failForm.evidencePayload = ''
}

const handleMarkFailed = async () => {
  if (!currentExecution.value) return
  if (!failForm.failureReason.trim()) {
    ElMessage.warning(t('resourceGovernance.actionPlan.failureReasonRequired'))
    return
  }
  executionActionLoading.value = true
  try {
    const evidencePayload = parseEvidencePayload(failForm.evidencePayload)
    await failActionExecution(currentExecution.value.id, {
      failureReason: failForm.failureReason,
      evidencePayload
    })
    ElMessage.success(t('resourceGovernance.actionPlan.markFailedSuccess'))
    closeFailDialog()
    await loadExecutionRecords()
    if (currentPlan.value) {
      const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
      currentPlan.value = freshPlan
    }
    loadData()
  } catch (err) {
    console.error('Failed to mark execution failed:', err)
  } finally {
    executionActionLoading.value = false
  }
}

const openCancelExecutionDialog = (execution: GovernanceActionExecution) => {
  currentExecution.value = execution
  cancelExecutionForm.cancelReason = ''
  cancelExecutionDialogVisible.value = true
}

const closeCancelExecutionDialog = () => {
  cancelExecutionDialogVisible.value = false
  currentExecution.value = null
  cancelExecutionForm.cancelReason = ''
}

const handleCancelExecution = async () => {
  if (!currentExecution.value) return
  if (!cancelExecutionForm.cancelReason.trim()) {
    ElMessage.warning(t('resourceGovernance.actionPlan.cancelExecutionReasonRequired'))
    return
  }
  executionActionLoading.value = true
  try {
    await cancelActionExecution(currentExecution.value.id, {
      cancelReason: cancelExecutionForm.cancelReason
    })
    ElMessage.success(t('resourceGovernance.actionPlan.cancelExecutionSuccess'))
    closeCancelExecutionDialog()
    await loadExecutionRecords()
    if (currentPlan.value) {
      const freshPlan = await getGovernanceActionPlan(currentPlan.value.id)
      currentPlan.value = freshPlan
    }
    loadData()
  } catch (err) {
    console.error('Failed to cancel execution:', err)
  } finally {
    executionActionLoading.value = false
  }
}

const formatEvidencePayload = (payload?: string | Record<string, unknown>) => {
  if (!payload) return '-'
  if (typeof payload === 'string') {
    try {
      const parsed = JSON.parse(payload)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return payload
    }
  }
  return JSON.stringify(payload, null, 2)
}

const handleSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  loadData()
}

const handleCurrentChange = (page: number) => {
  queryParams.pageNum = page
  loadData()
}

const refresh = () => {
  queryParams.pageNum = 1
  loadData()
}

onMounted(() => {
  loadData()
})

watch(() => [props.envId, props.resourcePoolId, props.riskLevel, props.suggestionType, props.status], () => {
  queryParams.pageNum = 1
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.governance-suggestions-tab {
  min-height: 200px;
}

.tab-header {
  margin-bottom: 16px;
  display: flex;
  justify-content: flex-end;
}

.action-buttons {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.error-message {
  margin-top: 16px;
}

.empty-message {
  margin-top: 16px;
}

.plan-detail {
  padding: 0 16px;
}

.plan-payload-section {
  margin-top: 20px;
}

.plan-payload-section h4 {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ih-text-secondary);
}

.plan-payload-content {
  background: var(--color-paper-3);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  padding: var(--space-sm);
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow: auto;
}

.plan-actions {
  margin-top: 24px;
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.execution-records-section {
  margin-top: 24px;
}

.execution-records-section .section-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.execution-records-section h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--ih-text-secondary);
}

.execution-records-section .hint-text {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.execution-records-section .running-hint {
  margin-bottom: 12px;
}

.empty-executions {
  margin-top: 12px;
}

.evidence-popover {
  background: var(--color-paper-3);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  padding: var(--space-sm);
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow: auto;
}

.timeline-entry-section {
  margin-top: 24px;
  margin-bottom: 16px;
  display: flex;
  justify-content: flex-start;
}
</style>
