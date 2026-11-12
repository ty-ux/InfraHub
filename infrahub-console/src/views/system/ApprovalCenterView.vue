<template>
  <div class="approval-center-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('approval.title') }}</h2>
    </div>

    <el-tabs v-model="activeTab" class="approval-tabs">
      <el-tab-pane :label="t('approval.list')" name="requests">
        <div class="filter-bar ih-filter-bar">
          <el-select
            v-model="queryParams.status"
            :placeholder="t('approval.filterStatusPlaceholder')"
            clearable
            @change="handleSearch"
          >
            <el-option :label="t('approval.approvalStatus.PENDING')" value="PENDING" />
            <el-option :label="t('approval.approvalStatus.APPROVED')" value="APPROVED" />
            <el-option :label="t('approval.approvalStatus.REJECTED')" value="REJECTED" />
            <el-option :label="t('approval.approvalStatus.CANCELLED')" value="CANCELLED" />
          </el-select>
          <el-select
            v-model="queryParams.bizType"
            :placeholder="t('approval.filterBizTypePlaceholder')"
            clearable
            @change="handleSearch"
          >
            <el-option :label="t('approval.bizTypeEnum.MONITOR_ALERT_RULE')" value="MONITOR_ALERT_RULE" />
            <el-option :label="t('approval.bizTypeEnum.MONITOR_HEALTH_CHECK')" value="MONITOR_HEALTH_CHECK" />
            <el-option :label="t('approval.bizTypeEnum.RELEASE_ORDER')" value="RELEASE_ORDER" />
            <el-option :label="t('approval.bizTypeEnum.ROLLBACK_ORDER')" value="ROLLBACK_ORDER" />
          </el-select>
          <el-input
            v-model="queryParams.applicantId"
            :placeholder="t('approval.applicantId')"
            clearable
            @keyup.enter="handleSearch"
          />
          <el-button @click="handleSearch">
            {{ t('common.search') }}
          </el-button>
          <el-button link class="filter-reset" @click="handleResetFilters">
            {{ t('common.resetFilters') }}
          </el-button>
        </div>

        <el-card class="table-card">
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
                <el-button size="small" @click="handleRefresh">
                  {{ t('common.retry') }}
                </el-button>
              </div>
            </template>
          </el-alert>

          <el-table :data="tableData" style="width: 100%" v-loading="loading">
            <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
            <el-table-column prop="bizType" :label="t('approval.bizType')" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                {{ bizTypeLabel(row.bizType) }}
              </template>
            </el-table-column>
            <el-table-column prop="bizId" :label="t('approval.bizId')" width="100" align="center" />
            <el-table-column :label="t('approval.businessSummary')" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                {{ getBusinessSummary(row) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('approval.action')" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ actionLabel(row.action) }}
              </template>
            </el-table-column>
            <el-table-column prop="status" :label="t('approval.status')" width="150" align="center">
              <template #default="{ row }">
                <div class="status-cell">
                  <el-tag :type="getStatusType(row.status)" effect="dark">
                    {{ t(`approval.approvalStatus.${row.status}`) }}
                  </el-tag>
                  <el-tooltip
                    v-if="isUnconfirmed(row)"
                    :content="t('approval.statusUnconfirmedHint')"
                    placement="top"
                  >
                    <el-tag class="status-unconfirmed" type="warning" effect="plain" size="small">
                      {{ t('approval.statusUnconfirmed') }}
                    </el-tag>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="applicantName" :label="t('approval.applicantName')" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.applicantName || row.applicantId }}
              </template>
            </el-table-column>
            <el-table-column prop="approverName" :label="t('approval.approverName')" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.approverName || row.approverId || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('approval.createdAt')" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="approvedAt" :label="t('approval.approvedAt')" min-width="180">
              <template #default="{ row }">
                {{ row.approvedAt ? formatDate(row.approvedAt) : '-' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="220" fixed="right" align="center">
              <template #default="{ row }">
                <div class="ih-table-actions">
                  <el-button link type="primary" size="small" @click="showDetail(row)">
                    {{ t('approval.viewDetail') }}
                  </el-button>

                  <!-- An approval whose state is not confirmed is refreshed first, never operated on -->
                  <el-button
                    v-if="isUnconfirmed(row)"
                    link
                    type="warning"
                    size="small"
                    :loading="isRefreshingStatus(row)"
                    @click="refreshRowStatus(row)"
                  >
                    {{ t('approval.refreshStatus') }}
                  </el-button>

                  <template v-else-if="row.status === 'PENDING'">
                    <el-tooltip
                      :disabled="!isBusy(row)"
                      :content="t('approval.actionBusyHint')"
                      placement="top"
                    >
                      <span class="action-group">
                        <template v-if="hasAuthority">
                          <el-button
                            type="primary"
                            size="small"
                            class="action-primary"
                            :loading="isBusy(row)"
                            @click="openActionDialog('approve', row)"
                          >
                            {{ t('approval.actions.approve') }}
                          </el-button>
                          <el-button
                            link
                            type="danger"
                            size="small"
                            :disabled="isBusy(row)"
                            @click="openActionDialog('reject', row)"
                          >
                            {{ t('approval.actions.reject') }}
                          </el-button>
                        </template>
                        <el-button
                          v-else-if="row.applicantId === currentUserId"
                          link
                          type="warning"
                          size="small"
                          :disabled="isBusy(row)"
                          @click="openActionDialog('cancel', row)"
                        >
                          {{ t('approval.actions.cancel') }}
                        </el-button>
                      </span>
                    </el-tooltip>
                  </template>
                </div>
              </template>
            </el-table-column>

            <template #empty>
              <!-- A failed read says so above; it is never dressed up as an empty list -->
              <div v-if="!readFailed" class="ih-empty-state empty-state">
                <template v-if="hasActiveFilters">
                  <p class="empty-title">{{ t('common.noMatch') }}</p>
                  <p class="empty-hint">{{ t('common.noMatchHint') }}</p>
                  <el-button size="small" @click="handleResetFilters">
                    {{ t('common.clearFilters') }}
                  </el-button>
                </template>
                <template v-else>
                  <p class="empty-title">{{ t('common.noData') }}</p>
                  <p class="empty-hint">{{ t('common.noDataHint') }}</p>
                  <el-button size="small" @click="handleRefresh">
                    {{ t('common.refresh') }}
                  </el-button>
                </template>
              </div>
            </template>
          </el-table>

          <div class="ih-pagination-bar">
            <!-- Element Plus words its own pagination from its own vocabulary, so the page hands it
                 the one that matches the console language instead of leaving it on the default -->
            <el-config-provider :locale="paginationLocale">
              <el-pagination
                v-model:current-page="queryParams.pageNum"
                v-model:page-size="queryParams.pageSize"
                :page-sizes="[10, 20, 50, 100]"
                :total="total"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="handleSizeChange"
                @current-change="handleCurrentChange"
              />
            </el-config-provider>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="hasAuthority" :label="t('approval.policyManagement')" name="policies">
        <div class="filter-bar ih-filter-bar">
          <el-button size="small" @click="loadPolicies">
            {{ t('common.refresh') }}
          </el-button>
        </div>

        <el-card class="table-card">
          <el-alert
            v-if="policyReadFailed"
            class="ih-error-alert ih-error-alert--danger read-error"
            type="error"
            :closable="false"
            show-icon
          >
            <template #title>{{ t('common.readFailed') }}</template>
            <template #default>
              <span class="read-error-hint">{{ t('common.readFailedHint') }}</span>
            </template>
          </el-alert>

          <el-table :data="policyTableData" style="width: 100%" v-loading="policyTableLoading">
            <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
            <el-table-column :label="t('approval.bizType')" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                {{ bizTypeLabel(row.bizType) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('approval.action')" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ actionLabel(row.action) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('approval.enabled')" width="120" align="center">
              <template #default="{ row }">
                <el-switch
                  v-model="row.enabled"
                  :aria-label="policySwitchAria(row)"
                  :loading="policyLoading[getPolicyLoadingKey(row)]"
                  @change="handleTogglePolicy(row)"
                />
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('approval.createdAt')" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" :label="t('approval.updatedAt')" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.updatedAt) }}
              </template>
            </el-table-column>

            <template #empty>
              <div v-if="!policyReadFailed" class="ih-empty-state empty-state">
                <p class="empty-title">{{ t('common.noData') }}</p>
                <p class="empty-hint">{{ t('common.noDataHint') }}</p>
              </div>
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailDialogVisible" :title="t('approval.detail')" width="600px" class="ih-dialog">
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="t('common.id')">{{ detailData.id }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.status')">
          <el-tag :type="getStatusType(detailData.status)">{{ t(`approval.approvalStatus.${detailData.status}`) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('approval.businessSource')">
          {{ getBusinessSourceLabel(detailData.bizType) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('approval.businessSummary')">
          {{ getBusinessSummary(detailData) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('approval.bizType')">{{ detailData.bizType }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.bizId')">{{ detailData.bizId }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.action')">{{ detailData.action }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.applicantName')">
          {{ detailData.applicantName || detailData.applicantId }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('approval.handlerName')">
          {{ detailData.status === 'PENDING' ? '-' : (detailData.approverName || detailData.approverId || '-') }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('approval.createdAt')">{{ formatDate(detailData.createdAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="detailData.status !== 'PENDING'" :label="t('approval.handleTime')">
          {{ detailData.approvedAt ? formatDate(detailData.approvedAt) : '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- Handle Note section for APPROVED/REJECTED -->
      <div class="data-section" v-if="detailData.status === 'APPROVED' || detailData.status === 'REJECTED'">
        <h4 class="data-title">{{ t('approval.handleNote') }}</h4>
        <pre class="json-pre">{{ detailData.approveNote || '-' }}</pre>
      </div>

      <div class="data-section" v-if="detailData.applyNote">
        <h4 class="data-title">{{ t('approval.applyNote') }}</h4>
        <pre class="json-pre">{{ detailData.applyNote }}</pre>
      </div>

      <div class="data-section" v-if="detailData.approveNote">
        <h4 class="data-title">{{ t('approval.approveNote') }}</h4>
        <pre class="json-pre">{{ detailData.approveNote }}</pre>
      </div>

      <div class="data-section" v-if="detailData.requestPayload">
        <h4 class="data-title">{{ t('approval.requestPayload') }}</h4>
        <pre class="json-pre">{{ formatPayload(detailData.requestPayload) }}</pre>
      </div>

      <div class="nav-bar">
        <div class="business-nav" v-if="getBusinessNavUrl(detailData)">
          <el-button type="primary" @click="navigateToBusiness(detailData)">
            {{ getBusinessNavLabel(detailData) }}
          </el-button>
        </div>

        <!-- Audit navigation button -->
        <div class="audit-nav" v-if="detailData.status !== 'PENDING'">
          <el-button @click="navigateToAudit(detailData)">
            {{ t('approval.viewAudit') }}
          </el-button>
        </div>

        <!-- Governance Evidence Chain button -->
        <div class="governance-nav">
          <el-button @click="navigateToGovernanceChain(detailData)">
            {{ t('governance.viewChain') }}
          </el-button>
        </div>
      </div>
    </el-dialog>

    <!-- One dialog shared by approve, reject and cancel: the context it shows is frozen when it opens -->
    <el-dialog
      v-model="actionDialog.visible"
      :title="actionDialogTitle"
      width="560px"
      class="ih-dialog"
      :show-close="!dialogFrozen"
      :close-on-press-escape="!dialogFrozen"
      :close-on-click-modal="!dialogFrozen"
      :before-close="handleActionDialogBeforeClose"
    >
      <template v-if="actionDialog.target">
        <el-descriptions :column="2" border size="small" class="action-context">
          <el-descriptions-item :label="t('approval.bizType')">
            {{ bizTypeLabel(actionDialog.target.bizType) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('approval.bizId')">
            {{ actionDialog.target.bizId }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('approval.businessSummary')">
            {{ getBusinessSummary(actionDialog.target) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('approval.action')">
            {{ actionLabel(actionDialog.target.action) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('approval.status')" :span="2">
            <el-tag :type="getStatusType(actionDialog.target.status)" effect="dark">
              {{ t(`approval.approvalStatus.${actionDialog.target.status}`) }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          class="action-impact"
          type="warning"
          :closable="false"
          show-icon
          :title="t('approval.impactTitle')"
          :description="actionImpact"
        />

        <el-form
          v-if="actionDialog.kind !== 'cancel'"
          ref="actionFormRef"
          :model="actionDialog"
          :rules="actionFormRules"
          label-position="top"
          class="ih-form action-note"
        >
          <el-form-item :label="actionNoteLabel" prop="note">
            <el-input
              v-model="actionDialog.note"
              type="textarea"
              :rows="3"
              :disabled="dialogFrozen"
              :placeholder="actionNotePlaceholder"
            />
          </el-form-item>
        </el-form>

        <!-- The verdict of a refused write stays in the dialog it belongs to -->
        <el-alert
          v-if="actionNotice"
          class="action-notice"
          type="warning"
          :closable="false"
          show-icon
          :title="actionNotice"
        />
      </template>

      <template #footer>
        <el-button :disabled="dialogFrozen" @click="requestCloseActionDialog">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          :type="actionSubmitType"
          :loading="submitting"
          :disabled="submitting"
          @click="handleActionSubmit"
        >
          {{ actionSubmitLabel }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import zhCnLocale from 'element-plus/es/locale/lang/zh-cn'
import enLocale from 'element-plus/es/locale/lang/en'
import { useAuthStore } from '@/store/auth'
import {
  listApprovalRequests,
  approveRequest,
  rejectRequest,
  cancelRequest,
  listApprovalPolicies,
  upsertApprovalPolicy,
  type ApprovalRequest,
  type ApprovalPolicy
} from '@/api/approval'

const { t, locale } = useI18n()
const authStore = useAuthStore()

const hasAuthority = computed(() => authStore.canAccess(['SYSTEM_ADMIN']))
const currentUserId = computed(() => authStore.currentUser?.userId)

const activeTab = ref('requests')
const loading = ref(false)
const tableData = ref<ApprovalRequest[]>([])
const total = ref(0)
/** Whether the last read of the list failed: the rows on screen are then the ones read before it. */
const readFailed = ref(false)

// Policy management
const policyTableData = ref<ApprovalPolicy[]>([])
const policyTableLoading = ref(false)
const policyReadFailed = ref(false)
// Use `${bizType}:${action}` as stable loading key (virtual policies have no id)
const policyLoading = ref<Record<string, boolean>>({})

// Helper to get stable loading key for a policy
const getPolicyLoadingKey = (row: ApprovalPolicy): string => `${row.bizType}:${row.action}`

/**
 * The approvals a write is claimed for. Approve, reject and cancel of one approval share this one
 * control, so the same approval is never written twice at once, while another approval stays free.
 * The claim is held from the moment the write is sent until that approval's state has been read
 * back, so a write that has landed but not been answered for yet keeps its approval claimed.
 */
const inFlightIds = ref<Set<number>>(new Set())
/**
 * The approvals whose last operation is not confirmed yet. Nothing settles these but a read that
 * answers for them, so their operate entries stay closed until then.
 */
const unconfirmedIds = ref<Set<number>>(new Set())
/** The approvals whose status is being re-read right now, whether the page asked for it or the operator did. */
const refreshingStatusIds = ref<Set<number>>(new Set())
/** Whether any write is in flight: while one is, no other approval is taken up. */
const anyActionInFlight = computed(() => inFlightIds.value.size > 0)

const detailDialogVisible = ref(false)
const detailData = ref<ApprovalRequest>({} as ApprovalRequest)

type ActionKind = 'approve' | 'reject' | 'cancel'

/**
 * The one dialog all three actions share. Its context is frozen when it opens: the approval it acts
 * on is a copy, so a list refresh behind the dialog can never move the object being decided on.
 */
const actionDialog = reactive({
  visible: false,
  kind: 'approve' as ActionKind,
  target: null as ApprovalRequest | null,
  note: ''
})
/** The verdict of a refused write, shown inside the dialog it belongs to. */
const actionNotice = ref('')
/** Whether the action request itself is in flight. It freezes the dialog while it is. */
const submitting = ref(false)
const actionFormRef = ref<FormInstance>()

/** A write in flight freezes the dialog: no field, no cancel, no close, no Escape, no mask. */
const dialogFrozen = computed(() => submitting.value)

const actionFormRules = computed<FormRules>(() =>
  actionDialog.kind === 'reject'
    ? { note: [{ required: true, message: t('approval.rejectNoteRequired'), trigger: 'blur' }] }
    : {}
)

const actionDialogTitle = computed(() => t(`approval.actionDialogTitle.${actionDialog.kind}`))
const actionSubmitLabel = computed(() => t(`approval.actionDialogSubmit.${actionDialog.kind}`))
const actionSubmitType = computed(() => (actionDialog.kind === 'approve' ? 'primary' : 'danger'))
const actionNoteLabel = computed(() =>
  actionDialog.kind === 'reject' ? t('approval.rejectNote') : t('approval.approveNote'))
const actionNotePlaceholder = computed(() =>
  actionDialog.kind === 'reject' ? t('approval.rejectNotePlaceholder') : t('approval.approveNotePlaceholder'))

/**
 * What the operation does. Approving a release or rollback starts its execution, so the two are told
 * apart from each other and from an approval that only moves an application on in its flow - and an
 * approval that went through is never worded as an execution that succeeded.
 */
const actionImpact = computed(() => {
  if (actionDialog.kind === 'reject') return t('approval.impactReject')
  if (actionDialog.kind === 'cancel') return t('approval.impactCancel')
  const bizType = actionDialog.target?.bizType
  if (bizType === 'RELEASE_ORDER') return t('approval.impactApproveRelease')
  if (bizType === 'ROLLBACK_ORDER') return t('approval.impactApproveRollback')
  return t('approval.impactApproveGeneric')
})

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  status: '',
  bizType: '',
  applicantId: undefined as number | undefined
})

function isSet(value: unknown): boolean {
  return value !== undefined && value !== null && String(value).trim() !== ''
}

/** Whether any filter is on screen: an empty table is then a filter result, not an empty list. */
const hasActiveFilters = computed(
  () => isSet(queryParams.status) || isSet(queryParams.bizType) || isSet(queryParams.applicantId)
)

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  if (Number.isNaN(date.getTime())) return dateStr
  return date.toLocaleString(locale.value)
}

const getStatusType = (status: string) => {
  switch (status) {
    case 'PENDING': return 'warning'
    case 'APPROVED': return 'success'
    case 'REJECTED': return 'danger'
    case 'CANCELLED': return 'info'
    default: return 'info'
  }
}

/**
 * The label of an enum the server sent. A value this build does not know stays readable as the value
 * itself: no label is invented for it.
 */
const enumLabel = (namespace: string, value: string): string => {
  if (!value) return '-'
  const key = `${namespace}.${value}`
  const label = t(key)
  return label === key ? value : label
}

const bizTypeLabel = (bizType: string): string => enumLabel('approval.bizTypeEnum', bizType)
const actionLabel = (action: string): string => enumLabel('approval.actionEnum', action)

const parsePayload = (payload: string | Record<string, unknown> | undefined): Record<string, unknown> | null => {
  if (!payload) return null
  if (typeof payload === 'object') return payload
  try {
    return JSON.parse(payload)
  } catch {
    return null
  }
}

const getBusinessSummary = (row: ApprovalRequest): string => {
  const payload = parsePayload(row.requestPayload)
  if (!payload) return '-'
  if (payload.orderNo) return String(payload.orderNo)
  if (payload.rollbackNo) return String(payload.rollbackNo)
  if (payload.ruleName) return String(payload.ruleName)
  if (payload.checkName) return String(payload.checkName)
  return '-'
}

const formatPayload = (payload: string | Record<string, unknown> | undefined): string => {
  if (!payload) return '-'
  if (typeof payload === 'object') return JSON.stringify(payload, null, 2)
  try {
    const parsed = JSON.parse(payload)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return payload
  }
}

const getBusinessNavUrl = (row: ApprovalRequest): string | null => {
  const payload = parsePayload(row.requestPayload)
  if (!payload) return null
  if (row.bizType === 'RELEASE_ORDER' && payload.orderNo) {
    return `/release/orders?keyword=${encodeURIComponent(String(payload.orderNo))}&focus=${encodeURIComponent(String(payload.orderNo))}`
  }
  if (row.bizType === 'ROLLBACK_ORDER' && payload.rollbackNo) {
    return `/release/rollbacks?keyword=${encodeURIComponent(String(payload.rollbackNo))}&focus=${encodeURIComponent(String(payload.rollbackNo))}`
  }
  return null
}

const getBusinessNavLabel = (row: ApprovalRequest): string => {
  if (row.bizType === 'RELEASE_ORDER') return t('approval.viewReleaseOrder')
  if (row.bizType === 'ROLLBACK_ORDER') return t('approval.viewRollbackOrder')
  return t('approval.viewBusiness')
}

const navigateToBusiness = (row: ApprovalRequest) => {
  const url = getBusinessNavUrl(row)
  if (url) {
    window.location.href = url
  }
}

const getBusinessSourceLabel = (bizType: string): string => {
  switch (bizType) {
    case 'RELEASE_ORDER': return t('approval.bizSourceLabel.RELEASE_ORDER')
    case 'ROLLBACK_ORDER': return t('approval.bizSourceLabel.ROLLBACK_ORDER')
    case 'MONITOR_ALERT_RULE': return t('approval.bizSourceLabel.MONITOR_ALERT_RULE')
    case 'MONITOR_HEALTH_CHECK': return t('approval.bizSourceLabel.MONITOR_HEALTH_CHECK')
    default: return bizType
  }
}

const navigateToAudit = (row: ApprovalRequest) => {
  let action = ''
  if (row.status === 'APPROVED') action = 'APPROVAL_APPROVE'
  else if (row.status === 'REJECTED') action = 'APPROVAL_REJECT'
  else if (row.status === 'CANCELLED') action = 'APPROVAL_CANCEL'

  const url = `/system/audit-logs?bizType=APPROVAL&bizId=${row.id}&action=${action}`
  window.location.href = url
}

const navigateToGovernanceChain = (row: ApprovalRequest) => {
  const url = `/system/governance-chain?bizType=${row.bizType}&bizId=${row.bizId}`
  window.location.href = url
}

/** Adds or removes one approval id, returning a new set so the change is always reactive. */
function withId(current: Set<number>, id: number, present: boolean): Set<number> {
  const next = new Set(current)
  if (present) next.add(id)
  else next.delete(id)
  return next
}

/** Only the newest read may write into the page. */
let listRequestSeq = 0

/**
 * What one read of the list settled. Only an answer that applied to the page can settle anything: a
 * read a newer one superseded never reached the rows on screen, and a read that failed produced no
 * answer at all - neither of them may be taken as the state of an approval.
 */
type ListRead =
  | { outcome: 'answered'; records: ApprovalRequest[] }
  | { outcome: 'superseded' }
  | { outcome: 'failed' }

/**
 * The row a read answered for one approval, or nothing. A read a newer one superseded, a read that
 * failed and a read that did not carry that approval all answer for nothing.
 */
function readRowFor(read: ListRead, id: number): ApprovalRequest | undefined {
  return read.outcome === 'answered' ? read.records.find(item => item.id === id) : undefined
}

/**
 * Reads the list, reporting what it settled. A read that failed keeps the rows on screen and is
 * shown as a read failure - never as an empty list - and the shared interceptor has reported it.
 */
async function loadData(): Promise<ListRead> {
  const seq = ++listRequestSeq
  loading.value = true
  try {
    const response = await listApprovalRequests(queryParams)
    // A read a newer one superseded is not applied, so it answers for nothing on this page
    if (seq !== listRequestSeq) return { outcome: 'superseded' }
    const records = response.records || []
    tableData.value = records
    total.value = response.total ?? records.length
    readFailed.value = false
    // The server has just answered for these approvals, so whatever was unconfirmed about them is
    // settled by that answer and their entries open again.
    if (unconfirmedIds.value.size > 0) {
      const answered = new Set(records.map(item => item.id))
      unconfirmedIds.value = new Set([...unconfirmedIds.value].filter(id => !answered.has(id)))
    }
    return { outcome: 'answered', records }
  } catch {
    // The shared interceptor has already reported the failure; a request a newer one superseded has
    // nothing left to report.
    if (seq !== listRequestSeq) return { outcome: 'superseded' }
    readFailed.value = true
    return { outcome: 'failed' }
  } finally {
    if (seq === listRequestSeq) loading.value = false
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  loadData()
}

const handleRefresh = () => {
  loadData()
}

/** Clears the filters alone: no other state of the page - no draft, no open decision - is touched. */
const handleResetFilters = () => {
  queryParams.status = ''
  queryParams.bizType = ''
  queryParams.applicantId = undefined
  queryParams.pageNum = 1
  loadData()
}

/**
 * The Element Plus vocabulary the pagination is worded in. Element Plus words its own controls from
 * its own locale, so this page hands its pagination the one that matches the console language rather
 * than leaving it on the library default. Nothing but this pagination is scoped by it.
 */
const paginationLocale = computed(() => (locale.value === 'en-US' ? enLocale : zhCnLocale))

const handleSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  loadData()
}

const handleCurrentChange = (page: number) => {
  queryParams.pageNum = page
  loadData()
}

const showDetail = (row: ApprovalRequest) => {
  detailData.value = row
  detailDialogVisible.value = true
}

/** Whether that approval is still claimed by a write: sent, and not yet answered for by a read. */
function isBusy(row: ApprovalRequest): boolean {
  return inFlightIds.value.has(row.id)
}

/** Whether that approval's last operation is still waiting for a read to confirm it. */
function isUnconfirmed(row: ApprovalRequest): boolean {
  return unconfirmedIds.value.has(row.id)
}

function isRefreshingStatus(row: ApprovalRequest): boolean {
  return refreshingStatusIds.value.has(row.id)
}

/** Reports only what the shared interceptor does not, so a failure is never shown twice. */
function reportError(err: unknown, fallbackKey: string) {
  if (axios.isAxiosError(err)) return
  ElMessage.error(err instanceof Error && err.message ? err.message : t(fallbackKey))
}

/**
 * A request that produced no answer at all - a timeout or a dropped connection - may still be running
 * on the server, so its outcome can only be settled by reading the state back.
 */
function isUnconfirmedTransport(err: unknown): boolean {
  return axios.isAxiosError(err) && !err.response
}

/**
 * Opens the shared dialog for one action. A write in flight owns the page, so no other approval is
 * taken up while one is unfinished; an approval whose state is not confirmed is refreshed first and
 * never operated on.
 */
function openActionDialog(kind: ActionKind, row: ApprovalRequest) {
  if (anyActionInFlight.value) return
  if (isUnconfirmed(row)) {
    ElMessage.warning(t('approval.statusUnconfirmedHint'))
    return
  }
  if (row.status !== 'PENDING') return
  actionDialog.kind = kind
  actionDialog.target = { ...row }
  actionDialog.note = ''
  actionNotice.value = ''
  actionDialog.visible = true
}

/** Closes the dialog for the operator; a write in flight freezes it, so this close is refused. */
function requestCloseActionDialog() {
  if (dialogFrozen.value) return
  actionDialog.visible = false
}

/** The close button, the mask and Escape all come through here, and a write in flight refuses them. */
function handleActionDialogBeforeClose(done: () => void) {
  if (dialogFrozen.value) return
  done()
}

/**
 * Settles a refused write against the server alone. Nothing is read out of the failure text: the list
 * is re-read and only what that read answers for the approval decides what happens next, and no path
 * here sends the request again.
 */
async function settleRefusedAction(id: number, err: unknown) {
  const read = await loadData()
  const row = readRowFor(read, id)

  if (row && row.status !== 'PENDING') {
    // Handled: the server moved it on, so the entry that would send it again is gone with it
    unconfirmedIds.value = withId(unconfirmedIds.value, id, false)
    actionDialog.visible = false
    ElMessage.warning(t('approval.messages.failedButHandled'))
    return
  }

  if (row) {
    // Confirmed still pending: nothing was written, so the dialog keeps the note that was typed
    unconfirmedIds.value = withId(unconfirmedIds.value, id, false)
    actionNotice.value = t('approval.messages.stillPending')
    return
  }

  // Nothing answered for it, so the outcome is not confirmed: the approval is not operated on again
  // until a read confirms it. This is where a timeout, a dropped connection, a failed read and a
  // read a newer one superseded all land.
  unconfirmedIds.value = withId(unconfirmedIds.value, id, true)
  actionDialog.visible = false
  ElMessage.warning(
    isUnconfirmedTransport(err)
      ? t('approval.messages.resultUnconfirmedTimeout')
      : t('approval.messages.resultUnconfirmed')
  )
}

const actionSuccessKey: Record<ActionKind, string> = {
  approve: 'approval.messages.approveSuccess',
  reject: 'approval.messages.rejectSuccess',
  cancel: 'approval.cancelSuccess'
}

/** Releases the claim a write took on one approval, so its entries are decided by its state again. */
function releaseActionClaim(id: number) {
  inFlightIds.value = withId(inFlightIds.value, id, false)
  submitting.value = false
}

/**
 * Settles a write that landed. The approval is held as not confirmed from the moment the write landed
 * until a read answers for it, so the row it was sent from keeps only its refresh entry across that
 * whole window and the approval that was just written is never decided a second time. Nothing here
 * sends the request again, and nothing but an answer for that approval takes the hold off.
 */
async function settleLandedWrite(id: number) {
  unconfirmedIds.value = withId(unconfirmedIds.value, id, true)
  refreshingStatusIds.value = withId(refreshingStatusIds.value, id, true)
  try {
    const read = await loadData()
    // A read that did not answer for it leaves the write accepted and unconfirmed - unless a newer
    // read has answered for it in the meantime, which is how this one was replaced. That newer answer
    // is the one that counts, so nothing is warned about a state it has already settled.
    if (!readRowFor(read, id) && unconfirmedIds.value.has(id)) {
      // The write itself landed, so this is a read that did not answer for it - and an approval no
      // read has answered for is not called confirmed.
      ElMessage.warning(t('approval.messages.acceptedButRefreshFailed'))
    }
  } finally {
    refreshingStatusIds.value = withId(refreshingStatusIds.value, id, false)
  }
}

/**
 * Sends the action the operator asked for. The guard is claimed before the form is validated -
 * validation is asynchronous, and a second click during it must not start a second request - and the
 * request is built from the context the dialog froze when it opened.
 *
 * The claim outlives the write itself: it is released only once that approval's state has been read
 * back, so the window between the write landing and the read that settles it is never a window in
 * which the same approval can be written again. Closing the dialog is not what frees it.
 */
async function handleActionSubmit() {
  const target = actionDialog.target
  if (!target) return
  if (submitting.value) return
  if (isBusy(target)) return
  if (isUnconfirmed(target)) return

  const kind = actionDialog.kind
  const id = target.id
  const note = actionDialog.note.trim()

  inFlightIds.value = withId(inFlightIds.value, id, true)
  submitting.value = true
  actionNotice.value = ''

  try {
    if (kind === 'reject' && !note) {
      // A rejection carries a reason. The field message is what the operator reads; the decision to
      // send is taken from the draft itself, so the rule holds wherever the validator cannot run.
      await actionFormRef.value?.validate().catch(() => false)
      // Nothing was sent, so this approval is not being settled by a read and stays free: the dialog
      // keeps the draft it was refused for, and the operator may send it once the reason is written.
      releaseActionClaim(id)
      return
    }
    if (kind === 'approve') await approveRequest(id, note || undefined)
    else if (kind === 'reject') await rejectRequest(id, note)
    else await cancelRequest(id)
  } catch (err) {
    // The shared interceptor has reported the failure with its own message and traceId. The refusal
    // is settled by reading the state back, and the approval stays claimed while that read is out.
    reportError(err, 'common.operationFailed')
    try {
      await settleRefusedAction(id, err)
    } finally {
      releaseActionClaim(id)
    }
    return
  }

  // The write landed. Whether it took effect is what the server answers for it, so the dialog closes
  // while that read is out and the approval waits in the not-confirmed state until it has answered.
  actionDialog.visible = false
  ElMessage.success(t(actionSuccessKey[kind]))
  try {
    await settleLandedWrite(id)
  } finally {
    releaseActionClaim(id)
  }
}

/**
 * Re-reads the list for one approval whose state is not confirmed. Only a read that answered for it
 * clears the mark; anything else - a read that failed, a read a newer one superseded, and a read that
 * did not carry it - keeps it and says so. Nothing is sent on its behalf.
 */
async function refreshRowStatus(row: ApprovalRequest) {
  if (isRefreshingStatus(row)) return
  refreshingStatusIds.value = withId(refreshingStatusIds.value, row.id, true)
  try {
    const read = await loadData()
    if (!readRowFor(read, row.id)) {
      ElMessage.warning(t('approval.messages.statusStillUnconfirmed'))
      return
    }
    unconfirmedIds.value = withId(unconfirmedIds.value, row.id, false)
    ElMessage.success(t('approval.messages.statusRefreshed'))
  } finally {
    refreshingStatusIds.value = withId(refreshingStatusIds.value, row.id, false)
  }
}

const policySwitchAria = (row: ApprovalPolicy): string =>
  t('approval.policySwitchAria', { bizType: bizTypeLabel(row.bizType), action: actionLabel(row.action) })

const loadPolicies = async () => {
  policyTableLoading.value = true
  try {
    policyTableData.value = await listApprovalPolicies()
    policyReadFailed.value = false
  } catch (err) {
    // A failed read keeps the policies on screen and is shown as a read failure
    reportError(err, 'common.loadFailed')
    policyReadFailed.value = true
  } finally {
    policyTableLoading.value = false
  }
}

const handleTogglePolicy = async (row: ApprovalPolicy) => {
  const key = getPolicyLoadingKey(row)
  policyLoading.value[key] = true
  try {
    await upsertApprovalPolicy({
      bizType: row.bizType,
      action: row.action,
      enabled: row.enabled
    })
    ElMessage.success(t('common.operationSuccess'))
  } catch (err) {
    // The refused write changes nothing: the switch keeps showing the value it was confirmed with
    reportError(err, 'common.operationFailed')
    row.enabled = !row.enabled
  } finally {
    policyLoading.value[key] = false
  }
}

onMounted(() => {
  loadData()
})

watch(activeTab, (newTab) => {
  if (newTab === 'policies') {
    loadPolicies()
  }
})
</script>

<style scoped>
.approval-center-view {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.filter-bar {
  padding: var(--ih-spacing-md) var(--ih-spacing-lg);
  background: var(--ih-bg-content);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-lg);
}

.table-card {
  margin-bottom: var(--space-md);
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

.status-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ih-spacing-1);
}

.status-unconfirmed {
  cursor: help;
}

/* One primary action stands out; the rest stay lightweight entries next to it */
.action-group {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-1);
}

.action-primary {
  font-weight: 500;
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

.nav-bar {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--ih-spacing-sm);
  margin-top: var(--ih-spacing-xl);
}

.business-nav,
.audit-nav,
.governance-nav {
  display: flex;
}

.data-section {
  margin-top: var(--ih-spacing-xl);
}

.data-title {
  font: var(--ih-font-section-title);
  color: var(--color-ink);
  margin: 0 0 var(--ih-spacing-sm);
}

.json-pre {
  background: var(--ih-bg-subtle);
  border: 1px solid var(--ih-border-light);
  padding: var(--ih-spacing-lg);
  border-radius: var(--ih-radius-md);
  color: var(--color-ink);
  font: var(--ih-font-code);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}

.action-context {
  margin-bottom: var(--ih-spacing-lg);
}

.action-impact {
  margin-bottom: var(--ih-spacing-lg);
}

.action-notice {
  margin-top: var(--ih-spacing-md);
}

.action-note {
  margin-top: var(--ih-spacing-md);
}

:deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
</style>