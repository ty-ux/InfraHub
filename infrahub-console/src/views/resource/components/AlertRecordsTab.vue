<template>
  <div class="alert-records-tab">
    <!-- Header with evaluate button -->
    <div class="tab-header">
      <div class="header-actions">
        <el-button type="primary" @click="handleEvaluate" :loading="evaluating">
          {{ t('resourceGovernance.alertRecords.evaluate') }}
        </el-button>
        <el-button @click="loadAlertRecords">
          {{ t('common.refresh') }}
        </el-button>
      </div>
    </div>

    <!-- Evaluation result display -->
    <el-card v-if="evaluationResult" class="evaluation-result-card">
      <template #header>
        <div class="card-header">
          <span>{{ t('resourceGovernance.alertRecords.evaluationResult') }}</span>
          <el-button type="primary" link size="small" @click="evaluationResult = null">
            {{ t('common.close') }}
          </el-button>
        </div>
      </template>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.evaluatedAt')">
          {{ formatDateTime(evaluationResult.evaluatedAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.evaluatedRuleCount')">
          {{ evaluationResult.evaluatedRuleCount }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.triggeredAlertCount')">
          <el-tag :type="evaluationResult.triggeredAlertCount > 0 ? 'danger' : 'success'" size="small">
            {{ evaluationResult.triggeredAlertCount }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.manualOnly')">
          <el-tag type="info" size="small">
            {{ evaluationResult.manualOnly ? t('common.yes') : t('common.no') }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- Loading state -->
    <div v-if="loading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- Error state -->
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />

    <!-- Alert records table -->
    <el-table v-else :data="alertRecords" stripe size="small">
      <el-table-column prop="alertNo" :label="t('resourceGovernance.alertRecords.alertNo')" width="140" />
      <el-table-column prop="ruleName" :label="t('resourceGovernance.alertRecords.ruleName')" min-width="150" show-overflow-tooltip />
      <el-table-column prop="metricType" :label="t('resourceGovernance.alertRecords.metricType')" width="160">
        <template #default="{ row }">
          {{ t(`resourceGovernance.alertRules.metricTypes.${row.metricType}`) }}
        </template>
      </el-table-column>
      <el-table-column prop="currentValue" :label="t('resourceGovernance.alertRecords.currentValue')" width="110" align="right" />
      <el-table-column prop="thresholdValue" :label="t('resourceGovernance.alertRecords.thresholdValue')" width="110" align="right" />
      <el-table-column prop="alertLevel" :label="t('resourceGovernance.alertRecords.alertLevel')" width="110">
        <template #default="{ row }">
          <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">
            {{ t(`resourceGovernance.alertRules.alertLevels.${row.alertLevel}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="alertStatus" :label="t('resourceGovernance.alertRecords.alertStatus')" width="140">
        <template #default="{ row }">
          <el-tag :type="getAlertStatusTagType(row.alertStatus)" size="small">
            {{ t(`resourceGovernance.alertRecords.alertStatuses.${row.alertStatus}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- O Stage: Assignee column -->
      <el-table-column :label="t('resourceGovernance.alertAssignment.assigneeName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.assigneeName || '-' }}
        </template>
      </el-table-column>
      <!-- O Stage: Escalated column -->
      <el-table-column :label="t('resourceGovernance.alertRecords.alertLevel')" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.escalated" type="danger" size="small">
            {{ row.escalationLevel || 'L1' }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" :label="t('resourceGovernance.alertRecords.reason')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.reason || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="440" fixed="right">
        <template #default="{ row }">
          <!-- N Stage: Original handle actions -->
          <template v-if="row.alertStatus === 'OPEN'">
            <el-button type="primary" link size="small" @click="openHandleDialog(row, 'ACK')">
              {{ t('resourceGovernance.alertHandling.ack') }}
            </el-button>
            <el-button type="primary" link size="small" @click="openHandleDialog(row, 'RESOLVE')">
              {{ t('resourceGovernance.alertHandling.resolve') }}
            </el-button>
          </template>
          <template v-else-if="row.alertStatus === 'ACKED'">
            <el-button type="primary" link size="small" @click="openHandleDialog(row, 'RESOLVE')">
              {{ t('resourceGovernance.alertHandling.resolve') }}
            </el-button>
            <el-button type="primary" link size="small" @click="openHandleDialog(row, 'REOPEN')">
              {{ t('resourceGovernance.alertHandling.reopen') }}
            </el-button>
          </template>
          <template v-else-if="row.alertStatus === 'RESOLVED'">
            <el-button type="primary" link size="small" @click="openHandleDialog(row, 'REOPEN')">
              {{ t('resourceGovernance.alertHandling.reopen') }}
            </el-button>
          </template>
          <el-divider direction="vertical" />
          <!-- O Stage: Assignment and Escalation Actions (only for OPEN/ACKED) -->
          <template v-if="row.alertStatus === 'OPEN' || row.alertStatus === 'ACKED'">
            <!-- Unassigned: show Assign -->
            <template v-if="!row.assigneeId">
              <el-button type="primary" link size="small" @click="openAssignDialog(row)">
                {{ t('resourceGovernance.alertAssignment.assign') }}
              </el-button>
            </template>
            <!-- Assigned: show Reassign and Unassign -->
            <template v-else>
              <el-button type="primary" link size="small" @click="openReassignDialog(row)">
                {{ t('resourceGovernance.alertAssignment.reassign') }}
              </el-button>
              <el-button type="warning" link size="small" @click="openUnassignDialog(row)">
                {{ t('resourceGovernance.alertAssignment.unassign') }}
              </el-button>
            </template>
            <!-- Escalate always available for OPEN/ACKED -->
            <el-button type="danger" link size="small" @click="openEscalateDialog(row)">
              {{ t('resourceGovernance.alertAssignment.escalate') }}
            </el-button>
          </template>
          <el-divider direction="vertical" />
          <!-- Q Stage: Generate Notification Draft -->
          <el-button type="info" link size="small" @click="openNotificationDraftPreview(row)">
            {{ t('resourceGovernance.notification.generateDraft') }}
          </el-button>
          <el-divider direction="vertical" />
          <!-- O Stage: History links (all statuses) -->
          <el-button type="info" link size="small" @click="openAssignmentHistoryDrawer(row)">
            {{ t('resourceGovernance.alertAssignment.viewAssignmentHistory') }}
          </el-button>
          <el-button type="info" link size="small" @click="openEscalationHistoryDrawer(row)">
            {{ t('resourceGovernance.alertAssignment.viewEscalationHistory') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Pagination -->
    <div v-if="!loading && !error && total > 0" class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        size="small"
        @size-change="loadAlertRecords"
        @current-change="loadAlertRecords"
      />
    </div>

    <!-- Manual only notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.alertRecords.manualOnlyNotice') }}</p>

    <!-- Handle Dialog (Ack/Resolve/Reopen) -->
    <el-dialog
      v-model="handleDialogVisible"
      :title="handleDialogTitle"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form :model="handleForm" label-width="100px">
        <el-form-item :label="t('resourceGovernance.alertHandling.handleNote')">
          <el-input
            v-model="handleForm.handleNote"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertHandling.handleNotePlaceholder')"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertHandling.evidencePayload')">
          <el-input
            v-model="evidencePayloadInput"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.alertHandling.evidencePayloadPlaceholder')"
          />
          <div class="evidence-hint">
            {{ t('resourceGovernance.alertHandling.evidencePayloadHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitHandle" :loading="handleSubmitting">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- O Stage: Assign Dialog -->
    <el-dialog
      v-model="assignDialogVisible"
      :title="t('resourceGovernance.alertAssignment.assignTitle')"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        {{ t('resourceGovernance.alertAssignment.manualAssignNotice') }}
      </el-alert>
      <el-form :model="assignForm" label-width="120px" :rules="assignRules" ref="assignFormRef">
        <el-form-item :label="t('resourceGovernance.alertAssignment.assigneeId')" prop="assigneeId">
          <el-input-number v-model="assignForm.assigneeId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.assigneeName')" prop="assigneeName">
          <el-input v-model="assignForm.assigneeName" :placeholder="t('resourceGovernance.alertAssignment.assigneeNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.assignReason')">
          <el-input
            v-model="assignForm.assignReason"
            type="textarea"
            :rows="2"
            :placeholder="t('resourceGovernance.alertAssignment.assignReasonPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.dueAt')">
          <el-date-picker
            v-model="assignForm.dueAt"
            type="datetime"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :placeholder="t('resourceGovernance.alertAssignment.dueAtPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.evidencePayload')">
          <el-input
            v-model="assignEvidenceInput"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertAssignment.evidencePayloadPlaceholder')"
          />
          <div class="evidence-hint">
            {{ t('resourceGovernance.alertAssignment.evidencePayloadHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitAssign" :loading="assignSubmitting">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- O Stage: Reassign Dialog -->
    <el-dialog
      v-model="reassignDialogVisible"
      :title="t('resourceGovernance.alertAssignment.reassignTitle')"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        {{ t('resourceGovernance.alertAssignment.manualReassignNotice') }}
      </el-alert>
      <el-form :model="reassignForm" label-width="120px" :rules="reassignRules" ref="reassignFormRef">
        <el-form-item :label="t('resourceGovernance.alertAssignment.currentAssignee')">
          <span>{{ selectedAlert?.assigneeName || '-' }}</span>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.toAssigneeId')" prop="toAssigneeId">
          <el-input-number v-model="reassignForm.toAssigneeId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.toAssigneeName')" prop="toAssigneeName">
          <el-input v-model="reassignForm.toAssigneeName" :placeholder="t('resourceGovernance.alertAssignment.toAssigneeNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.reassignReason')">
          <el-input
            v-model="reassignForm.reassignReason"
            type="textarea"
            :rows="2"
            :placeholder="t('resourceGovernance.alertAssignment.reassignReasonPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.dueAt')">
          <el-date-picker
            v-model="reassignForm.dueAt"
            type="datetime"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :placeholder="t('resourceGovernance.alertAssignment.dueAtPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.evidencePayload')">
          <el-input
            v-model="reassignEvidenceInput"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertAssignment.evidencePayloadPlaceholder')"
          />
          <div class="evidence-hint">
            {{ t('resourceGovernance.alertAssignment.evidencePayloadHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reassignDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitReassign" :loading="reassignSubmitting">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- O Stage: Unassign Dialog -->
    <el-dialog
      v-model="unassignDialogVisible"
      :title="t('resourceGovernance.alertAssignment.unassignTitle')"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        {{ t('resourceGovernance.alertAssignment.manualUnassignNotice') }}
      </el-alert>
      <el-form :model="unassignForm" label-width="120px">
        <el-form-item :label="t('resourceGovernance.alertAssignment.currentAssignee')">
          <span>{{ selectedAlert?.assigneeName || '-' }}</span>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.unassignReason')">
          <el-input
            v-model="unassignForm.unassignReason"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertAssignment.unassignReasonPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.evidencePayload')">
          <el-input
            v-model="unassignEvidenceInput"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertAssignment.evidencePayloadPlaceholder')"
          />
          <div class="evidence-hint">
            {{ t('resourceGovernance.alertAssignment.evidencePayloadHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="unassignDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitUnassign" :loading="unassignSubmitting">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- O Stage: Escalate Dialog -->
    <el-dialog
      v-model="escalateDialogVisible"
      :title="t('resourceGovernance.alertAssignment.escalateTitle')"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert type="info" :closable="false" style="margin-bottom: 16px">
        {{ t('resourceGovernance.alertAssignment.manualEscalateNotice') }}
      </el-alert>
      <el-form :model="escalateForm" label-width="120px" :rules="escalateRules" ref="escalateFormRef">
        <el-form-item :label="t('resourceGovernance.alertAssignment.escalationLevel')" prop="escalationLevel">
          <el-select v-model="escalateForm.escalationLevel" :placeholder="t('resourceGovernance.alertAssignment.selectEscalationLevel')" style="width: 100%">
            <el-option label="L1" value="L1" />
            <el-option label="L2" value="L2" />
            <el-option label="L3" value="L3" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.escalatedToId')" prop="escalatedToId">
          <el-input-number v-model="escalateForm.escalatedToId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.escalatedToName')" prop="escalatedToName">
          <el-input v-model="escalateForm.escalatedToName" :placeholder="t('resourceGovernance.alertAssignment.escalatedToNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.escalateReason')">
          <el-input
            v-model="escalateForm.escalateReason"
            type="textarea"
            :rows="2"
            :placeholder="t('resourceGovernance.alertAssignment.escalateReasonPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertAssignment.evidencePayload')">
          <el-input
            v-model="escalateEvidenceInput"
            type="textarea"
            :rows="3"
            :placeholder="t('resourceGovernance.alertAssignment.evidencePayloadPlaceholder')"
          />
          <div class="evidence-hint">
            {{ t('resourceGovernance.alertAssignment.evidencePayloadHint') }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="escalateDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitEscalate" :loading="escalateSubmitting">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- O Stage: Assignment History Drawer -->
    <el-drawer
      v-model="assignmentHistoryDrawerVisible"
      :title="t('resourceGovernance.alertAssignment.assignmentHistory')"
      size="600px"
    >
      <div v-if="loadingAssignmentHistory" class="history-loading">
        <el-skeleton :rows="5" animated />
      </div>
      <el-table v-else :data="assignmentHistoryRecords" stripe size="small">
        <el-table-column :label="t('resourceGovernance.alertAssignment.action')" width="140">
          <template #default="{ row }">
            <el-tag :type="getAssignmentActionTagType(row.action)" size="small">
              {{ t(`resourceGovernance.alertAssignment.actions.${row.action}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertAssignment.fromAssignee')" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.fromAssigneeName || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertAssignment.toAssignee')" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.toAssigneeName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="reason" :label="t('resourceGovernance.alertRecords.reason')" min-width="150">
          <template #default="{ row }">
            {{ row.reason || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertAssignment.dueAt')" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.dueAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" :label="t('common.actions')" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.operatorName || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- O Stage: Escalation History Drawer -->
    <el-drawer
      v-model="escalationHistoryDrawerVisible"
      :title="t('resourceGovernance.alertAssignment.escalationHistory')"
      size="600px"
    >
      <div v-if="loadingEscalationHistory" class="history-loading">
        <el-skeleton :rows="5" animated />
      </div>
      <el-table v-else :data="escalationHistoryRecords" stripe size="small">
        <el-table-column :label="t('resourceGovernance.alertAssignment.escalationLevel')" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="danger" size="small">
              {{ t(`resourceGovernance.alertAssignment.escalationLevels.${row.escalationLevel}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertAssignment.escalatedToName')" width="120">
          <template #default="{ row }">
            {{ row.escalatedToName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="escalateReason" :label="t('resourceGovernance.alertAssignment.escalateReason')" min-width="150">
          <template #default="{ row }">
            {{ row.escalateReason || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" :label="t('common.actions')" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.operatorName || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- Evidence Detail Dialog -->
    <el-dialog
      v-model="evidenceDialogVisible"
      :title="t('resourceGovernance.timeline.evidenceDetail')"
      width="500px"
    >
      <pre class="evidence-content">{{ evidenceDetailContent }}</pre>
    </el-dialog>

    <!-- Q Stage: Notification Draft Preview Dialog -->
    <NotificationDraftPreview
      v-model="notificationDraftPreviewVisible"
      :alert-record="selectedAlert"
      @open-confirm="handleOpenNotificationConfirm"
    />

    <!-- Q Stage: Notification Manual Confirm Dialog -->
    <NotificationManualConfirmDialog
      v-model="notificationConfirmDialogVisible"
      :draft="notificationDraft"
      :form="notificationDraftForm"
      @confirmed="handleNotificationConfirmed"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import dayjs from 'dayjs'
import {
  listGovernanceAlertRecords,
  evaluateGovernanceAlerts,
  ackGovernanceAlert,
  resolveGovernanceAlert,
  reopenGovernanceAlert,
  getGovernanceAlert,
  listGovernanceAlertHandlingRecords,
  // O Stage imports
  assignGovernanceAlert,
  reassignGovernanceAlert,
  unassignGovernanceAlert,
  escalateGovernanceAlert,
  listGovernanceAlertAssignmentRecords,
  listGovernanceAlertEscalationRecords,
  type GovernanceAlertRecordResponse,
  type GovernanceAlertEvaluationResponse,
  type GovernanceAlertHandlingRecordResponse,
  type GovernanceAlertHandleAction,
  type GovernanceAlertAssignmentRecordResponse,
  type GovernanceAlertEscalationRecordResponse,
  type GovernanceAlertEscalationLevel,
  // Q Stage imports
  type GovernanceAlertNotificationDraftResponse,
  type GovernanceAlertNotificationRecordResponse,
  type GovernanceAlertNotificationChannelType
} from '@/api/asset'
import NotificationDraftPreview from './NotificationDraftPreview.vue'
import NotificationManualConfirmDialog from './NotificationManualConfirmDialog.vue'

const props = defineProps<{
  envId?: number
  resourcePoolId?: number
  serviceId?: number
}>()

const { t } = useI18n()

// State
const alertRecords = ref<GovernanceAlertRecordResponse[]>([])
const loading = ref(false)
const error = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

// Evaluation state
const evaluating = ref(false)
const evaluationResult = ref<GovernanceAlertEvaluationResponse | null>(null)

// Handle dialog state
const handleDialogVisible = ref(false)
const handleSubmitting = ref(false)
const selectedAlert = ref<GovernanceAlertRecordResponse | null>(null)
const selectedAction = ref<GovernanceAlertHandleAction>('ACK')
const handleForm = ref({
  handleNote: ''
})
const evidencePayloadInput = ref('')

// O Stage: Assign dialog state
const assignDialogVisible = ref(false)
const assignSubmitting = ref(false)
const assignFormRef = ref<FormInstance>()
const assignForm = reactive({
  assigneeId: 0,
  assigneeName: '',
  assignReason: '',
  dueAt: ''
})
const assignEvidenceInput = ref('')
const assignRules: FormRules = {
  assigneeId: [{ required: true, message: t('resourceGovernance.alertAssignment.assigneeRequired'), trigger: 'blur' }],
  assigneeName: [{ required: true, message: t('resourceGovernance.alertAssignment.assigneeRequired'), trigger: 'blur' }]
}

// O Stage: Reassign dialog state
const reassignDialogVisible = ref(false)
const reassignSubmitting = ref(false)
const reassignFormRef = ref<FormInstance>()
const reassignForm = reactive({
  toAssigneeId: 0,
  toAssigneeName: '',
  reassignReason: '',
  dueAt: ''
})
const reassignEvidenceInput = ref('')
const reassignRules: FormRules = {
  toAssigneeId: [{ required: true, message: t('resourceGovernance.alertAssignment.assigneeRequired'), trigger: 'blur' }],
  toAssigneeName: [{ required: true, message: t('resourceGovernance.alertAssignment.assigneeRequired'), trigger: 'blur' }]
}

// O Stage: Unassign dialog state
const unassignDialogVisible = ref(false)
const unassignSubmitting = ref(false)
const unassignForm = reactive({
  unassignReason: ''
})
const unassignEvidenceInput = ref('')

// O Stage: Escalate dialog state
const escalateDialogVisible = ref(false)
const escalateSubmitting = ref(false)
const escalateFormRef = ref<FormInstance>()
const escalateForm = reactive({
  escalationLevel: '' as GovernanceAlertEscalationLevel | '',
  escalatedToId: 0,
  escalatedToName: '',
  escalateReason: ''
})
const escalateEvidenceInput = ref('')
const escalateRules: FormRules = {
  escalationLevel: [{ required: true, message: t('resourceGovernance.alertAssignment.escalationLevelRequired'), trigger: 'change' }],
  escalatedToId: [{ required: true, message: t('resourceGovernance.alertAssignment.escalatedToRequired'), trigger: 'blur' }],
  escalatedToName: [{ required: true, message: t('resourceGovernance.alertAssignment.escalatedToRequired'), trigger: 'blur' }]
}

// O Stage: Assignment history drawer state
const assignmentHistoryDrawerVisible = ref(false)
const loadingAssignmentHistory = ref(false)
const assignmentHistoryRecords = ref<GovernanceAlertAssignmentRecordResponse[]>([])

// O Stage: Escalation history drawer state
const escalationHistoryDrawerVisible = ref(false)
const loadingEscalationHistory = ref(false)
const escalationHistoryRecords = ref<GovernanceAlertEscalationRecordResponse[]>([])

// Q Stage: Notification draft preview state
const notificationDraftPreviewVisible = ref(false)
const notificationDraft = ref<GovernanceAlertNotificationDraftResponse | null>(null)
const notificationDraftForm = ref<{
  channelType: GovernanceAlertNotificationChannelType
  targetUserId: number
  targetUserName: string
  subject: string
  content: string
} | null>(null)
const notificationConfirmDialogVisible = ref(false)

// Detail drawer state
const detailDrawerVisible = ref(false)
const loadingHistory = ref(false)
const handlingHistory = ref<GovernanceAlertHandlingRecordResponse[]>([])

// Evidence dialog state
const evidenceDialogVisible = ref(false)
const evidenceDetailContent = ref('')

// Computed
const handleDialogTitle = computed(() => {
  const actionKey = `resourceGovernance.alertHandling.${selectedAction.value.toLowerCase()}Title`
  return t(actionKey)
})

// Helper functions
function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function getAlertLevelTagType(level: string): 'info' | 'warning' | 'danger' {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    default: return 'info'
  }
}

function getAlertStatusTagType(status: string): 'info' | 'warning' | 'success' {
  switch (status) {
    case 'OPEN': return 'warning'
    case 'ACKED': return 'info'
    case 'RESOLVED': return 'success'
    default: return 'info'
  }
}

function getActionTagType(action: GovernanceAlertHandleAction): 'success' | 'warning' | 'info' {
  switch (action) {
    case 'ACK': return 'info'
    case 'RESOLVE': return 'success'
    case 'REOPEN': return 'warning'
    default: return 'info'
  }
}

function getAssignmentActionTagType(action: string): 'success' | 'warning' | 'info' | 'danger' {
  switch (action) {
    case 'ASSIGN': return 'success'
    case 'REASSIGN': return 'warning'
    case 'UNASSIGN': return 'info'
    default: return 'info'
  }
}

// Parse evidence payload - wrap plain text as { text: "..." }
function parseEvidencePayload(input: string): Record<string, unknown> | undefined {
  if (!input || !input.trim()) {
    return undefined
  }
  const trimmed = input.trim()
  try {
    const parsed = JSON.parse(trimmed)
    if (typeof parsed === 'object' && parsed !== null) {
      return parsed
    }
    return { text: parsed }
  } catch {
    return { text: trimmed }
  }
}

// Load alert records
async function loadAlertRecords() {
  loading.value = true
  error.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    }
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId

    const result = await listGovernanceAlertRecords(params)
    alertRecords.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    error.value = t('resourceGovernance.alertRecords.loadFailed')
    console.error('Failed to load alert records:', err)
  } finally {
    loading.value = false
  }
}

// Evaluate alerts (manual trigger)
async function handleEvaluate() {
  evaluating.value = true
  try {
    const result = await evaluateGovernanceAlerts()
    evaluationResult.value = result
    ElMessage.success(t('resourceGovernance.alertRecords.evaluateSuccess', { count: result.triggeredAlertCount }))
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('resourceGovernance.alertRecords.evaluateFailed'))
    console.error('Failed to evaluate alerts:', err)
  } finally {
    evaluating.value = false
  }
}

// Open handle dialog
function openHandleDialog(alert: GovernanceAlertRecordResponse, action: GovernanceAlertHandleAction) {
  selectedAlert.value = alert
  selectedAction.value = action
  handleForm.value.handleNote = ''
  evidencePayloadInput.value = ''
  handleDialogVisible.value = true
}

// Submit handle action
async function submitHandle() {
  if (!selectedAlert.value) return

  handleSubmitting.value = true
  try {
    const data = {
      handleNote: handleForm.value.handleNote || undefined,
      evidencePayload: parseEvidencePayload(evidencePayloadInput.value)
    }

    let result: GovernanceAlertRecordResponse
    switch (selectedAction.value) {
      case 'ACK':
        result = await ackGovernanceAlert(selectedAlert.value.id, data)
        break
      case 'RESOLVE':
        result = await resolveGovernanceAlert(selectedAlert.value.id, data)
        break
      case 'REOPEN':
        result = await reopenGovernanceAlert(selectedAlert.value.id, data)
        break
      default:
        throw new Error(`Unknown action: ${selectedAction.value}`)
    }

    ElMessage.success(t(`resourceGovernance.alertHandling.${selectedAction.value.toLowerCase()}Success`))
    handleDialogVisible.value = false
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to handle alert:', err)
  } finally {
    handleSubmitting.value = false
  }
}

// O Stage: Open assign dialog
function openAssignDialog(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  assignForm.assigneeId = 0
  assignForm.assigneeName = ''
  assignForm.assignReason = ''
  assignForm.dueAt = ''
  assignEvidenceInput.value = ''
  assignDialogVisible.value = true
}

// O Stage: Submit assign
async function submitAssign() {
  if (!selectedAlert.value) return
  if (!assignFormRef.value) return

  try {
    await assignFormRef.value.validate()
  } catch {
    return
  }

  assignSubmitting.value = true
  try {
    await assignGovernanceAlert(selectedAlert.value.id, {
      assigneeId: assignForm.assigneeId,
      assigneeName: assignForm.assigneeName,
      assignReason: assignForm.assignReason || undefined,
      dueAt: assignForm.dueAt || undefined,
      evidencePayload: parseEvidencePayload(assignEvidenceInput.value)
    })
    ElMessage.success(t('resourceGovernance.alertAssignment.assignSuccess'))
    assignDialogVisible.value = false
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to assign alert:', err)
  } finally {
    assignSubmitting.value = false
  }
}

// O Stage: Open reassign dialog
function openReassignDialog(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  reassignForm.toAssigneeId = 0
  reassignForm.toAssigneeName = ''
  reassignForm.reassignReason = ''
  reassignForm.dueAt = ''
  reassignEvidenceInput.value = ''
  reassignDialogVisible.value = true
}

// O Stage: Submit reassign
async function submitReassign() {
  if (!selectedAlert.value) return
  if (!reassignFormRef.value) return

  try {
    await reassignFormRef.value.validate()
  } catch {
    return
  }

  reassignSubmitting.value = true
  try {
    await reassignGovernanceAlert(selectedAlert.value.id, {
      toAssigneeId: reassignForm.toAssigneeId,
      toAssigneeName: reassignForm.toAssigneeName,
      reassignReason: reassignForm.reassignReason || undefined,
      dueAt: reassignForm.dueAt || undefined,
      evidencePayload: parseEvidencePayload(reassignEvidenceInput.value)
    })
    ElMessage.success(t('resourceGovernance.alertAssignment.reassignSuccess'))
    reassignDialogVisible.value = false
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to reassign alert:', err)
  } finally {
    reassignSubmitting.value = false
  }
}

// O Stage: Open unassign dialog
function openUnassignDialog(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  unassignForm.unassignReason = ''
  unassignEvidenceInput.value = ''
  unassignDialogVisible.value = true
}

// O Stage: Submit unassign
async function submitUnassign() {
  if (!selectedAlert.value) return

  unassignSubmitting.value = true
  try {
    await unassignGovernanceAlert(selectedAlert.value.id, {
      unassignReason: unassignForm.unassignReason || undefined,
      evidencePayload: parseEvidencePayload(unassignEvidenceInput.value)
    })
    ElMessage.success(t('resourceGovernance.alertAssignment.unassignSuccess'))
    unassignDialogVisible.value = false
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to unassign alert:', err)
  } finally {
    unassignSubmitting.value = false
  }
}

// O Stage: Open escalate dialog
function openEscalateDialog(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  escalateForm.escalationLevel = ''
  escalateForm.escalatedToId = 0
  escalateForm.escalatedToName = ''
  escalateForm.escalateReason = ''
  escalateEvidenceInput.value = ''
  escalateDialogVisible.value = true
}

// O Stage: Submit escalate
async function submitEscalate() {
  if (!selectedAlert.value) return
  if (!escalateFormRef.value) return

  try {
    await escalateFormRef.value.validate()
  } catch {
    return
  }

  escalateSubmitting.value = true
  try {
    await escalateGovernanceAlert(selectedAlert.value.id, {
      escalationLevel: escalateForm.escalationLevel as GovernanceAlertEscalationLevel,
      escalatedToId: escalateForm.escalatedToId,
      escalatedToName: escalateForm.escalatedToName,
      escalateReason: escalateForm.escalateReason || undefined,
      evidencePayload: parseEvidencePayload(escalateEvidenceInput.value)
    })
    ElMessage.success(t('resourceGovernance.alertAssignment.escalateSuccess'))
    escalateDialogVisible.value = false
    loadAlertRecords()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to escalate alert:', err)
  } finally {
    escalateSubmitting.value = false
  }
}

// O Stage: Open assignment history drawer
async function openAssignmentHistoryDrawer(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  assignmentHistoryDrawerVisible.value = true
  loadingAssignmentHistory.value = true
  try {
    const result = await listGovernanceAlertAssignmentRecords(alert.id)
    assignmentHistoryRecords.value = result.records || []
  } catch (err) {
    console.error('Failed to load assignment history:', err)
    assignmentHistoryRecords.value = []
  } finally {
    loadingAssignmentHistory.value = false
  }
}

// O Stage: Open escalation history drawer
async function openEscalationHistoryDrawer(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  escalationHistoryDrawerVisible.value = true
  loadingEscalationHistory.value = true
  try {
    const result = await listGovernanceAlertEscalationRecords(alert.id)
    escalationHistoryRecords.value = result.records || []
  } catch (err) {
    console.error('Failed to load escalation history:', err)
    escalationHistoryRecords.value = []
  } finally {
    loadingEscalationHistory.value = false
  }
}

// Q Stage: Open notification draft preview
function openNotificationDraftPreview(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  notificationDraftPreviewVisible.value = true
}

// Q Stage: Handle open notification confirm dialog
function handleOpenNotificationConfirm(data: { draft: GovernanceAlertNotificationDraftResponse; form: typeof notificationDraftForm.value }) {
  notificationDraft.value = data.draft
  notificationDraftForm.value = data.form
  notificationDraftPreviewVisible.value = false
  notificationConfirmDialogVisible.value = true
}

// Q Stage: Handle notification confirmed
function handleNotificationConfirmed(record: GovernanceAlertNotificationRecordResponse) {
  notificationConfirmDialogVisible.value = false
  notificationDraft.value = null
  notificationDraftForm.value = null
  // Refresh alert records after notification confirmed
  loadAlertRecords()
}

// Show evidence detail
function showEvidenceDetail(payload: Record<string, unknown> | undefined) {
  if (!payload) {
    evidenceDetailContent.value = '-'
  } else {
    evidenceDetailContent.value = JSON.stringify(payload, null, 2)
  }
  evidenceDialogVisible.value = true
}

// Load handling history
async function loadHandlingHistory(alertId: number) {
  loadingHistory.value = true
  try {
    const result = await listGovernanceAlertHandlingRecords(alertId)
    handlingHistory.value = result.records || []
  } catch (err) {
    console.error('Failed to load handling history:', err)
    handlingHistory.value = []
  } finally {
    loadingHistory.value = false
  }
}

// Open detail drawer
async function openDetailDrawer(alert: GovernanceAlertRecordResponse) {
  selectedAlert.value = alert
  detailDrawerVisible.value = true
  await loadHandlingHistory(alert.id)
}

// Public refresh method
function refresh() {
  pageNum.value = 1
  evaluationResult.value = null
  loadAlertRecords()
}

// Watch props changes
watch(() => [props.envId, props.resourcePoolId, props.serviceId], () => {
  refresh()
})

onMounted(() => {
  loadAlertRecords()
})

defineExpose({ refresh })
</script>

<style scoped>
.alert-records-tab {
  padding: 0;
}

.tab-header {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.evaluation-result-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-loading {
  padding: 16px;
  background: var(--ih-bg-content);
  border-radius: 4px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.manual-only-notice {
  margin-top: var(--space-md);
  padding-top: var(--space-xs);
  border-top: var(--rule-hair) solid var(--color-rule);
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
}

.evidence-hint {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-top: 4px;
}

.manual-only-hint {
  margin-top: 16px;
  text-align: center;
}

.handling-history {
  margin-top: 24px;
}

.handling-history h4 {
  margin-bottom: 16px;
  font-size: 14px;
  font-weight: 500;
}

.history-loading {
  padding: 16px;
}

.history-item {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.history-note {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.no-history {
  text-align: center;
  padding: 24px;
  color: var(--ih-text-secondary);
}

.evidence-content {
  background: var(--color-paper-3);
  padding: var(--space-md);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  font-family: var(--font-outlier);
  font-size: var(--text-sm);
  overflow-x: auto;
  max-height: 400px;
  overflow-y: auto;
}
</style>
