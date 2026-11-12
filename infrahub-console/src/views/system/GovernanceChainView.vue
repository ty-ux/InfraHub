<template>
  <div class="governance-chain-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('governance.title') }}</h2>
    </div>

    <el-card class="filter-card" v-loading="loading">
      <div class="filter-bar">
        <el-select
          v-model="queryParams.bizType"
          :placeholder="t('governance.bizType')"
          clearable
          style="width: 200px"
          @change="handleSearch"
        >
          <el-option label="MONITOR_ALERT_RULE" value="MONITOR_ALERT_RULE" />
          <el-option label="MONITOR_HEALTH_CHECK" value="MONITOR_HEALTH_CHECK" />
          <el-option label="MONITOR_ALERT_EVENT" value="MONITOR_ALERT_EVENT" />
          <el-option label="MONITOR_COLLECTION" value="MONITOR_COLLECTION" />
          <el-option label="MONITOR_EVALUATION" value="MONITOR_EVALUATION" />
          <el-option label="RELEASE_ORDER" value="RELEASE_ORDER" />
          <el-option label="ROLLBACK_ORDER" value="ROLLBACK_ORDER" />
          <el-option label="APPROVAL" value="APPROVAL" />
        </el-select>
        <el-input
          v-model.number="queryParams.bizId"
          :placeholder="t('governance.bizId')"
          clearable
          type="number"
          style="width: 160px"
          @keyup.enter="handleSearch"
        />
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>
    </el-card>

    <!-- Empty State -->
    <div v-if="!loading && !hasData" class="empty-panel">
      {{ t('governance.noData') }}
    </div>

    <!-- Data Sections -->
    <template v-if="hasData">
      <!-- Business Summary -->
      <el-card class="data-card" v-if="data.businessSummary">
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ t('governance.businessSummary') }}</span>
          </div>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('governance.bizType')">
            {{ data.businessSummary.bizType }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('governance.bizId')">
            {{ data.businessSummary.bizId }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('governance.displayId')">
            {{ data.businessSummary.displayId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('governance.status')">
            <el-tag :type="getStatusType(data.businessSummary.status)" effect="dark">
              {{ data.businessSummary.status }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="details-section" v-if="data.businessSummary.details && Object.keys(data.businessSummary.details).length > 0">
          <h4 class="data-title">{{ t('governance.details') }}</h4>
          <pre class="json-pre">{{ formatJson(data.businessSummary.details) }}</pre>
        </div>
      </el-card>

      <!-- Approval Records -->
      <el-card class="data-card" v-if="data.approvals && data.approvals.length > 0">
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ t('governance.approvalRecords') }}</span>
            <el-tag type="info">{{ data.approvals.length }}</el-tag>
          </div>
        </template>
        <el-table :data="data.approvals" style="width: 100%">
          <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
          <el-table-column :label="t('governance.action')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              {{ t(`governance.actionEnum.${row.action}`) }}
            </template>
          </el-table-column>
          <el-table-column prop="status" :label="t('governance.status')" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="getStatusType(row.status)" effect="dark">
                {{ t(`governance.approvalStatus.${row.status}`) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="applicantName" :label="t('governance.applicantName')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.applicantName || row.applicantId }}
            </template>
          </el-table-column>
          <el-table-column prop="applyNote" :label="t('governance.applyNote')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.applyNote || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="approverName" :label="t('governance.approverName')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.approverName || row.approverId || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="approveNote" :label="t('governance.approveNote')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.approveNote || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('governance.createdAt')" min-width="180">
            <template #default="{ row }">
              {{ formatDate(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column prop="approvedAt" :label="t('governance.approvedAt')" min-width="180">
            <template #default="{ row }">
              {{ row.approvedAt ? formatDate(row.approvedAt) : '-' }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- Audit Logs -->
      <el-card class="data-card" v-if="data.auditLogs && data.auditLogs.length > 0">
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ t('governance.auditLogs') }}</span>
            <el-tag type="info">{{ data.auditLogs.length }}</el-tag>
          </div>
        </template>
        <el-table :data="data.auditLogs" style="width: 100%">
          <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
          <el-table-column prop="action" :label="t('governance.action')" min-width="220" show-overflow-tooltip />
          <el-table-column prop="result" :label="t('governance.result')" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="dark">
                {{ row.result }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('governance.beforeData')" width="120" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDataDetail(row.beforeData, 'before')">
                {{ t('governance.view') }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column :label="t('governance.afterData')" width="120" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="showDataDetail(row.afterData, 'after')">
                {{ t('governance.view') }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column prop="occurredAt" :label="t('governance.occurredAt')" min-width="180">
            <template #default="{ row }">
              {{ formatDate(row.occurredAt) }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- Notification Records -->
      <el-card class="data-card" v-if="data.notifications && data.notifications.length > 0">
        <template #header>
          <div class="card-header">
            <span class="card-title">{{ t('governance.notifications') }}</span>
            <el-tag type="info">{{ data.notifications.length }}</el-tag>
          </div>
        </template>
        <el-table :data="data.notifications" style="width: 100%">
          <el-table-column prop="id" :label="t('common.id')" width="80" />
          <el-table-column prop="eventType" :label="t('governance.eventType')" width="160" />
          <el-table-column prop="status" :label="t('governance.status')" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SENT' ? 'success' : 'danger'" effect="dark">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetUrl" :label="t('governance.targetUrl')" min-width="200">
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.targetUrl || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="errorMessage" :label="t('governance.errorMessage')" min-width="150">
            <template #default="{ row }">
              <span class="text-ellipsis">{{ row.errorMessage || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" :label="t('governance.createdAt')" width="160">
            <template #default="{ row }">
              {{ formatDate(row.createdAt) }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- Data Detail Dialog -->
    <el-dialog v-model="dataDialogVisible" :title="dataDialogTitle" width="700px">
      <pre class="json-pre">{{ dataDialogContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { getEvidenceChain, type GovernanceEvidenceChain } from '@/api/governance'

const { t } = useI18n()
const route = useRoute()

const loading = ref(false)
const hasData = ref(false)
const data = ref<GovernanceEvidenceChain>({
  businessSummary: {} as GovernanceEvidenceChain['businessSummary'],
  approvals: [],
  auditLogs: [],
  notifications: []
})

const dataDialogVisible = ref(false)
const dataDialogTitle = ref('')
const dataDialogContent = ref('')

const queryParams = reactive({
  bizType: '',
  bizId: undefined as number | undefined
})

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const formatJson = (obj: unknown) => {
  if (!obj) return '-'
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

const getStatusType = (status: string) => {
  switch (status) {
    case 'ACTIVE':
    case 'APPROVED':
    case 'RESOLVED':
    case 'SENT':
      return 'success'
    case 'PENDING':
    case 'FIRING':
      return 'warning'
    case 'REJECTED':
    case 'CANCELLED':
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

const loadData = async () => {
  if (!queryParams.bizType || !queryParams.bizId) {
    hasData.value = false
    return
  }

  loading.value = true
  try {
    const result = await getEvidenceChain(queryParams.bizType, queryParams.bizId)
    data.value = result
    hasData.value = true
  } catch (err) {
    console.error('Failed to load governance evidence chain:', err)
    ElMessage.error(t('common.loadFailed'))
    hasData.value = false
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.bizId = queryParams.bizId
  loadData()
}

const showDataDetail = (dataObj: Record<string, unknown> | undefined, type: string) => {
  if (!dataObj || Object.keys(dataObj).length === 0) {
    dataDialogContent.value = '-'
  } else {
    dataDialogContent.value = formatJson(dataObj)
  }
  dataDialogTitle.value = type === 'before' ? t('governance.beforeData') : t('governance.afterData')
  dataDialogVisible.value = true
}

// Initialize from query params
onMounted(() => {
  if (route.query.bizType) {
    queryParams.bizType = route.query.bizType as string
  }
  if (route.query.bizId) {
    queryParams.bizId = Number(route.query.bizId)
  }

  if (queryParams.bizType && queryParams.bizId) {
    loadData()
  }
})
</script>

<style scoped>
.governance-chain-view {
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

.data-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--color-ink);
}

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.empty-panel {
  display: flex;
  min-height: 240px;
  align-items: center;
  justify-content: center;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
  color: var(--color-muted);
  font-size: var(--text-sm);
}

.details-section {
  margin-top: 20px;
}

.data-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-ink);
  margin-bottom: var(--space-xs);
}

.json-pre {
  background: var(--ih-bg-subtle);
  padding: 16px;
  border-radius: 4px;
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow: auto;
}

.text-ellipsis {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
