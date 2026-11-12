<template>
  <div class="alert-workbench-tab">
    <!-- Summary Cards -->
    <el-card class="summary-card">
      <template #header>
        <div class="card-header">
          <span>{{ t('resourceGovernance.alertWorkbench.summary.title') }}</span>
          <el-button link size="small" @click="loadSummary">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>
      <div v-if="loadingSummary" class="section-loading">
        <el-skeleton :rows="2" animated />
      </div>
      <div v-else-if="summaryError" class="error-state">
        <el-alert type="error" :title="summaryError" :closable="false" show-icon />
      </div>
      <div v-else class="summary-grid">
        <div class="summary-item">
          <div class="summary-value">{{ summary?.openCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.openCount') }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-value">{{ summary?.ackedCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.ackedCount') }}</div>
        </div>
        <div class="summary-item summary-success">
          <div class="summary-value">{{ summary?.resolvedCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.resolvedCount') }}</div>
        </div>
        <div class="summary-item summary-primary">
          <div class="summary-value">{{ summary?.assignedToMeCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.assignedToMeCount') }}</div>
        </div>
        <div class="summary-item summary-danger">
          <div class="summary-value">{{ summary?.overdueCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.overdueCount') }}</div>
        </div>
        <div class="summary-item summary-warning">
          <div class="summary-value">{{ summary?.escalatedCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.escalatedCount') }}</div>
        </div>
        <div class="summary-item summary-danger">
          <div class="summary-value">{{ summary?.criticalCount ?? 0 }}</div>
          <div class="summary-label">{{ t('resourceGovernance.alertWorkbench.summary.criticalCount') }}</div>
        </div>
      </div>
      <div class="updated-at">
        {{ t('resourceGovernance.alertWorkbench.summary.updatedAt') }}: {{ formatDateTime(summary?.updatedAt) }}
      </div>
    </el-card>

    <!-- Manual Only Notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.alertWorkbench.manualOnlyNotice') }}</p>

    <!-- Sub-tabs for lists -->
    <el-card class="lists-card">
      <el-tabs v-model="activeListTab" @tab-change="handleListTabChange">
        <!-- My Assigned Tab -->
        <el-tab-pane :label="t('resourceGovernance.alertWorkbench.myAssigned.title')" name="myAssigned">
          <div class="list-header">
            <div class="list-filters">
              <el-select v-model="myAssignedParams.status" :placeholder="t('resourceGovernance.alertRecords.alertStatus')" clearable size="small" style="width: 120px" @change="loadMyAssigned">
                <el-option label="OPEN" value="OPEN" />
                <el-option label="ACKED" value="ACKED" />
                <el-option label="RESOLVED" value="RESOLVED" />
              </el-select>
              <el-select v-model="myAssignedParams.level" :placeholder="t('resourceGovernance.alertRules.alertLevel')" clearable size="small" style="width: 120px" @change="loadMyAssigned">
                <el-option label="INFO" value="INFO" />
                <el-option label="WARNING" value="WARNING" />
                <el-option label="CRITICAL" value="CRITICAL" />
              </el-select>
              <el-checkbox v-model="myAssignedParams.overdueOnly" @change="loadMyAssigned">
                {{ t('resourceGovernance.alertWorkbench.myAssigned.overdueOnly') }}
              </el-checkbox>
            </div>
            <el-button size="small" @click="loadMyAssigned">{{ t('common.refresh') }}</el-button>
          </div>
          <div v-if="loadingMyAssigned" class="section-loading">
            <el-skeleton :rows="5" animated />
          </div>
          <el-alert v-else-if="myAssignedError" type="error" :title="myAssignedError" :closable="false" show-icon />
          <template v-else>
            <el-table :data="myAssignedRecords" stripe size="small">
              <el-table-column prop="alertNo" :label="t('resourceGovernance.alertRecords.alertNo')" width="140" />
              <el-table-column prop="ruleName" :label="t('resourceGovernance.alertRecords.ruleName')" min-width="120" show-overflow-tooltip />
              <el-table-column prop="alertLevel" :label="t('resourceGovernance.alertRecords.alertLevel')" width="110">
                <template #default="{ row }">
                  <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">
                    {{ row.alertLevel }}
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
              <el-table-column :label="t('resourceGovernance.alertAssignment.dueAt')" width="160">
                <template #default="{ row }">
                  <span :class="{ 'overdue-text': isOverdue(row) }">
                    {{ formatDateTime(row.assignmentDueAt) }}
                  </span>
                  <el-tag v-if="isOverdue(row)" type="danger" size="small" style="margin-left: 4px">
                    {{ t('resourceGovernance.alertWorkbench.overdueTag') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="escalationLevel" :label="t('resourceGovernance.alertAssignment.escalationLevel')" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.escalated" type="danger" size="small">
                    {{ row.escalationLevel || 'L1' }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <div v-if="myAssignedTotal > 0" class="pagination-wrapper">
              <el-pagination
                v-model:current-page="myAssignedPageNum"
                v-model:page-size="myAssignedPageSize"
                :total="myAssignedTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                size="small"
                @size-change="loadMyAssigned"
                @current-change="loadMyAssigned"
              />
            </div>
            <div v-if="myAssignedRecords.length === 0" class="empty-data">
              {{ t('resourceGovernance.alertWorkbench.myAssigned.noData') }}
            </div>
          </template>
        </el-tab-pane>

        <!-- Overdue Tab -->
        <el-tab-pane :label="t('resourceGovernance.alertWorkbench.overdue.title')" name="overdue">
          <div class="list-header">
            <div class="list-filters">
              <el-select v-model="overdueParams.level" :placeholder="t('resourceGovernance.alertRules.alertLevel')" clearable size="small" style="width: 120px" @change="loadOverdue">
                <el-option label="INFO" value="INFO" />
                <el-option label="WARNING" value="WARNING" />
                <el-option label="CRITICAL" value="CRITICAL" />
              </el-select>
              <el-select v-model="overdueParams.escalated" :placeholder="t('resourceGovernance.alertWorkbench.summary.escalatedCount')" clearable size="small" style="width: 120px" @change="loadOverdue">
                <el-option :label="t('common.yes')" :value="true" />
                <el-option :label="t('common.no')" :value="false" />
              </el-select>
            </div>
            <el-button size="small" @click="loadOverdue">{{ t('common.refresh') }}</el-button>
          </div>
          <div v-if="loadingOverdue" class="section-loading">
            <el-skeleton :rows="5" animated />
          </div>
          <el-alert v-else-if="overdueError" type="error" :title="overdueError" :closable="false" show-icon />
          <template v-else>
            <el-table :data="overdueRecords" stripe size="small">
              <el-table-column prop="alertNo" :label="t('resourceGovernance.alertRecords.alertNo')" width="140" />
              <el-table-column prop="ruleName" :label="t('resourceGovernance.alertRecords.ruleName')" min-width="120" show-overflow-tooltip />
              <el-table-column prop="alertLevel" :label="t('resourceGovernance.alertRecords.alertLevel')" width="110">
                <template #default="{ row }">
                  <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">
                    {{ row.alertLevel }}
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
              <el-table-column prop="assigneeName" :label="t('resourceGovernance.alertAssignment.assigneeName')" width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.assigneeName || '-' }}
                </template>
              </el-table-column>
              <el-table-column :label="t('resourceGovernance.alertAssignment.dueAt')" width="160">
                <template #default="{ row }">
                  <span class="overdue-text">{{ formatDateTime(row.assignmentDueAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="escalationLevel" :label="t('resourceGovernance.alertAssignment.escalationLevel')" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.escalated" type="danger" size="small">
                    {{ row.escalationLevel || 'L1' }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <div v-if="overdueTotal > 0" class="pagination-wrapper">
              <el-pagination
                v-model:current-page="overduePageNum"
                v-model:page-size="overduePageSize"
                :total="overdueTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                size="small"
                @size-change="loadOverdue"
                @current-change="loadOverdue"
              />
            </div>
            <div v-if="overdueRecords.length === 0" class="empty-data">
              {{ t('resourceGovernance.alertWorkbench.overdue.noData') }}
            </div>
          </template>
        </el-tab-pane>

        <!-- Escalated Tab -->
        <el-tab-pane :label="t('resourceGovernance.alertWorkbench.escalated.title')" name="escalated">
          <div class="list-header">
            <div class="list-filters">
              <el-select v-model="escalatedParams.escalationLevel" :placeholder="t('resourceGovernance.alertAssignment.escalationLevel')" clearable size="small" style="width: 120px" @change="loadEscalated">
                <el-option label="L1" value="L1" />
                <el-option label="L2" value="L2" />
                <el-option label="L3" value="L3" />
              </el-select>
              <el-select v-model="escalatedParams.status" :placeholder="t('resourceGovernance.alertRecords.alertStatus')" clearable size="small" style="width: 120px" @change="loadEscalated">
                <el-option label="OPEN" value="OPEN" />
                <el-option label="ACKED" value="ACKED" />
                <el-option label="RESOLVED" value="RESOLVED" />
              </el-select>
            </div>
            <el-button size="small" @click="loadEscalated">{{ t('common.refresh') }}</el-button>
          </div>
          <div v-if="loadingEscalated" class="section-loading">
            <el-skeleton :rows="5" animated />
          </div>
          <el-alert v-else-if="escalatedError" type="error" :title="escalatedError" :closable="false" show-icon />
          <template v-else>
            <el-table :data="escalatedRecords" stripe size="small">
              <el-table-column prop="alertNo" :label="t('resourceGovernance.alertRecords.alertNo')" width="140" />
              <el-table-column prop="ruleName" :label="t('resourceGovernance.alertRecords.ruleName')" min-width="120" show-overflow-tooltip />
              <el-table-column prop="alertLevel" :label="t('resourceGovernance.alertRecords.alertLevel')" width="110">
                <template #default="{ row }">
                  <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">
                    {{ row.alertLevel }}
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
              <el-table-column prop="assigneeName" :label="t('resourceGovernance.alertAssignment.assigneeName')" width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.assigneeName || '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="escalationLevel" :label="t('resourceGovernance.alertAssignment.escalationLevel')" width="120">
                <template #default="{ row }">
                  <el-tag type="danger" size="small">
                    {{ t(`resourceGovernance.alertAssignment.escalationLevels.${row.escalationLevel}`) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="escalatedAt" :label="t('resourceGovernance.alertWorkbench.summary.escalatedCount')" width="160">
                <template #default="{ row }">
                  {{ formatDateTime(row.escalatedAt) }}
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" :label="t('resourceGovernance.alertRecords.createdAt')" width="160">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <div v-if="escalatedTotal > 0" class="pagination-wrapper">
              <el-pagination
                v-model:current-page="escalatedPageNum"
                v-model:page-size="escalatedPageSize"
                :total="escalatedTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                size="small"
                @size-change="loadEscalated"
                @current-change="loadEscalated"
              />
            </div>
            <div v-if="escalatedRecords.length === 0" class="empty-data">
              {{ t('resourceGovernance.alertWorkbench.escalated.noData') }}
            </div>
          </template>
        </el-tab-pane>

        <!-- Q Stage: Notification Records Tab -->
        <el-tab-pane :label="t('resourceGovernance.notification.title')" name="notificationRecords">
          <NotificationRecordsTab ref="notificationRecordsRef" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import {
  getGovernanceAlertWorkbenchSummary,
  listWorkbenchMyAssignedGovernanceAlerts,
  listOverdueGovernanceAlerts,
  listWorkbenchEscalatedGovernanceAlerts,
  type GovernanceAlertWorkbenchSummaryResponse,
  type GovernanceAlertRecordResponse,
  type GovernanceAlertLevel,
  type GovernanceAlertStatus,
  type GovernanceAlertEscalationLevel
} from '@/api/asset'
import NotificationRecordsTab from './NotificationRecordsTab.vue'

const { t } = useI18n()

// Active list tab
const activeListTab = ref('myAssigned')

// Summary state
const summary = ref<GovernanceAlertWorkbenchSummaryResponse | null>(null)
const loadingSummary = ref(false)
const summaryError = ref('')

// My Assigned state
const myAssignedRecords = ref<GovernanceAlertRecordResponse[]>([])
const loadingMyAssigned = ref(false)
const myAssignedError = ref('')
const myAssignedPageNum = ref(1)
const myAssignedPageSize = ref(20)
const myAssignedTotal = ref(0)
const myAssignedParams = reactive({
  status: undefined as GovernanceAlertStatus | undefined,
  level: undefined as GovernanceAlertLevel | undefined,
  overdueOnly: false
})

// Overdue state
const overdueRecords = ref<GovernanceAlertRecordResponse[]>([])
const loadingOverdue = ref(false)
const overdueError = ref('')
const overduePageNum = ref(1)
const overduePageSize = ref(20)
const overdueTotal = ref(0)
const overdueParams = reactive({
  level: undefined as GovernanceAlertLevel | undefined,
  escalated: undefined as boolean | undefined
})

// Escalated state
const escalatedRecords = ref<GovernanceAlertRecordResponse[]>([])
const loadingEscalated = ref(false)
const escalatedError = ref('')
const escalatedPageNum = ref(1)
const escalatedPageSize = ref(20)
const escalatedTotal = ref(0)
const escalatedParams = reactive({
  escalationLevel: undefined as GovernanceAlertEscalationLevel | undefined,
  status: undefined as GovernanceAlertStatus | undefined
})

// Q Stage: Notification records ref
const notificationRecordsRef = ref<InstanceType<typeof NotificationRecordsTab> | null>(null)

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

function isOverdue(row: GovernanceAlertRecordResponse): boolean {
  if (!row.assignmentDueAt) return false
  if (row.alertStatus === 'RESOLVED') return false
  return dayjs(row.assignmentDueAt).isBefore(dayjs())
}

// Load functions
async function loadSummary() {
  loadingSummary.value = true
  summaryError.value = ''
  try {
    summary.value = await getGovernanceAlertWorkbenchSummary()
  } catch (err) {
    summaryError.value = t('resourceGovernance.alertWorkbench.summary.loadFailed')
    console.error('Failed to load workbench summary:', err)
  } finally {
    loadingSummary.value = false
  }
}

async function loadMyAssigned() {
  loadingMyAssigned.value = true
  myAssignedError.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: myAssignedPageNum.value,
      pageSize: myAssignedPageSize.value
    }
    if (myAssignedParams.status) params.status = myAssignedParams.status
    if (myAssignedParams.level) params.level = myAssignedParams.level
    if (myAssignedParams.overdueOnly) params.overdueOnly = true

    const result = await listWorkbenchMyAssignedGovernanceAlerts(params)
    myAssignedRecords.value = result.records || []
    myAssignedTotal.value = result.total || 0
  } catch (err) {
    myAssignedError.value = t('resourceGovernance.alertWorkbench.myAssigned.loadFailed')
    console.error('Failed to load my assigned alerts:', err)
  } finally {
    loadingMyAssigned.value = false
  }
}

async function loadOverdue() {
  loadingOverdue.value = true
  overdueError.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: overduePageNum.value,
      pageSize: overduePageSize.value
    }
    if (overdueParams.level) params.level = overdueParams.level
    if (overdueParams.escalated !== undefined) params.escalated = overdueParams.escalated

    const result = await listOverdueGovernanceAlerts(params)
    overdueRecords.value = result.records || []
    overdueTotal.value = result.total || 0
  } catch (err) {
    overdueError.value = t('resourceGovernance.alertWorkbench.overdue.loadFailed')
    console.error('Failed to load overdue alerts:', err)
  } finally {
    loadingOverdue.value = false
  }
}

async function loadEscalated() {
  loadingEscalated.value = true
  escalatedError.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: escalatedPageNum.value,
      pageSize: escalatedPageSize.value
    }
    if (escalatedParams.escalationLevel) params.escalationLevel = escalatedParams.escalationLevel
    if (escalatedParams.status) params.status = escalatedParams.status

    const result = await listWorkbenchEscalatedGovernanceAlerts(params)
    escalatedRecords.value = result.records || []
    escalatedTotal.value = result.total || 0
  } catch (err) {
    escalatedError.value = t('resourceGovernance.alertWorkbench.escalated.loadFailed')
    console.error('Failed to load escalated alerts:', err)
  } finally {
    loadingEscalated.value = false
  }
}

function handleListTabChange(tabName: string) {
  if (tabName === 'myAssigned' && myAssignedRecords.value.length === 0) {
    loadMyAssigned()
  } else if (tabName === 'overdue' && overdueRecords.value.length === 0) {
    loadOverdue()
  } else if (tabName === 'escalated' && escalatedRecords.value.length === 0) {
    loadEscalated()
  } else if (tabName === 'notificationRecords') {
    notificationRecordsRef.value?.refresh()
  }
}

// Public refresh method
function refresh() {
  loadSummary()
  loadMyAssigned()
  loadOverdue()
  loadEscalated()
  notificationRecordsRef.value?.refresh()
}

onMounted(() => {
  loadSummary()
  loadMyAssigned()
})

defineExpose({ refresh })
</script>

<style scoped>
.alert-workbench-tab {
  padding: 0;
}

.summary-card {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 0;
}

.summary-item {
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.summary-item:first-child {
  border-left: 0;
  padding-left: 0;
}

.summary-value {
  font-family: var(--font-outlier);
  font-size: var(--text-2xl);
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.summary-label {
  margin-top: var(--space-3xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.updated-at {
  text-align: right;
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-top: 12px;
}

.manual-only-notice {
  margin: 0 0 var(--space-sm);
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
}

.lists-card {
  min-height: 300px;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.list-filters {
  display: flex;
  gap: 12px;
  align-items: center;
}

.section-loading {
  padding: 16px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.empty-data {
  text-align: center;
  padding: 24px;
  color: var(--ih-text-secondary);
}

.overdue-text {
  color: var(--ih-danger);
  font-weight: 500;
}

.error-state {
  padding: 16px;
}
</style>
