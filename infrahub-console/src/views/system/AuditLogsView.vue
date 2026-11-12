<template>
  <div class="audit-logs-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('audit.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('audit.list') }}</span>
          <el-button size="small" @click="handleRefresh">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-select
          v-model="queryParams.bizType"
          :placeholder="t('audit.bizType')"
          clearable
          style="width: 180px"
          @change="handleSearch"
        >
          <el-option label="MONITOR_ALERT_RULE" value="MONITOR_ALERT_RULE" />
          <el-option label="MONITOR_HEALTH_CHECK" value="MONITOR_HEALTH_CHECK" />
          <el-option label="MONITOR_ALERT_EVENT" value="MONITOR_ALERT_EVENT" />
          <el-option label="MONITOR_COLLECTION" value="MONITOR_COLLECTION" />
          <el-option label="MONITOR_EVALUATION" value="MONITOR_EVALUATION" />
          <el-option label="RELEASE_ORDER" value="RELEASE_ORDER" />
          <el-option label="APPROVAL" value="APPROVAL" />
        </el-select>
        <el-input
          v-model="queryParams.bizId"
          :placeholder="t('audit.bizId')"
          clearable
          style="width: 120px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.action"
          :placeholder="t('audit.action')"
          clearable
          style="width: 160px"
          @change="handleSearch"
        >
          <el-option label="CREATE_RULE" value="CREATE_RULE" />
          <el-option label="UPDATE_RULE" value="UPDATE_RULE" />
          <el-option label="DELETE_RULE" value="DELETE_RULE" />
          <el-option label="TOGGLE_RULE" value="TOGGLE_RULE" />
          <el-option label="CREATE_CHECK" value="CREATE_CHECK" />
          <el-option label="UPDATE_CHECK" value="UPDATE_CHECK" />
          <el-option label="DELETE_CHECK" value="DELETE_CHECK" />
          <el-option label="TOGGLE_CHECK" value="TOGGLE_CHECK" />
          <el-option label="MANUAL_COLLECT" value="MANUAL_COLLECT" />
          <el-option label="RUN_ONCE_COLLECT" value="RUN_ONCE_COLLECT" />
          <el-option label="EVALUATE_RULES" value="EVALUATE_RULES" />
          <el-option label="MANUAL_RESOLVE" value="MANUAL_RESOLVE" />
          <el-option label="APPROVAL_CREATE" value="APPROVAL_CREATE" />
          <el-option label="APPROVAL_APPROVE" value="APPROVAL_APPROVE" />
          <el-option label="APPROVAL_REJECT" value="APPROVAL_REJECT" />
          <el-option label="APPROVAL_CANCEL" value="APPROVAL_CANCEL" />
          <el-option label="RELEASE_ADMISSION_PASS" value="RELEASE_ADMISSION_PASS" />
          <el-option label="RELEASE_ADMISSION_BLOCKED" value="RELEASE_ADMISSION_BLOCKED" />
          <el-option label="RELEASE_ADMISSION_APPROVAL_REQUIRED" value="RELEASE_ADMISSION_APPROVAL_REQUIRED" />
          <el-option label="ROLLBACK_ADMISSION_PASS" value="ROLLBACK_ADMISSION_PASS" />
          <el-option label="ROLLBACK_ADMISSION_BLOCKED" value="ROLLBACK_ADMISSION_BLOCKED" />
          <el-option label="ROLLBACK_ADMISSION_APPROVAL_REQUIRED" value="ROLLBACK_ADMISSION_APPROVAL_REQUIRED" />
          <!-- E2: Canary gate actions -->
          <el-option label="RELEASE_CANARY_GATE_EVALUATED" value="RELEASE_CANARY_GATE_EVALUATED" />
          <el-option label="RELEASE_CANARY_GATE_PASSED" value="RELEASE_CANARY_GATE_PASSED" />
          <el-option label="RELEASE_CANARY_GATE_FAILED" value="RELEASE_CANARY_GATE_FAILED" />
          <el-option label="RELEASE_CANARY_CONTINUE_BLOCKED" value="RELEASE_CANARY_CONTINUE_BLOCKED" />
          <el-option label="RELEASE_CANARY_PLAN_CREATED" value="RELEASE_CANARY_PLAN_CREATED" />
          <el-option label="RELEASE_CANARY_BATCH_STARTED" value="RELEASE_CANARY_BATCH_STARTED" />
          <el-option label="RELEASE_CANARY_BATCH_SUCCESS" value="RELEASE_CANARY_BATCH_SUCCESS" />
          <el-option label="RELEASE_CANARY_BATCH_FAILED" value="RELEASE_CANARY_BATCH_FAILED" />
          <el-option label="RELEASE_CANARY_PLAN_PAUSED" value="RELEASE_CANARY_PLAN_PAUSED" />
          <el-option label="RELEASE_CANARY_PLAN_COMPLETED" value="RELEASE_CANARY_PLAN_COMPLETED" />
          <el-option label="RELEASE_CANARY_AUTO_ROLLBACK_TRIGGERED" value="RELEASE_CANARY_AUTO_ROLLBACK_TRIGGERED" />
        </el-select>
        <el-select
          v-model="queryParams.result"
          :placeholder="t('audit.result')"
          clearable
          style="width: 100px"
          @change="handleSearch"
        >
          <el-option :label="t('audit.success')" value="SUCCESS" />
          <el-option :label="t('audit.failed')" value="FAILED" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          :range-separator="t('audit.to')"
          :start-placeholder="t('audit.startTime')"
          :end-placeholder="t('audit.endTime')"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 300px"
          @change="handleTimeChange"
        />
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%">
        <el-table-column prop="id" :label="t('common.id')" width="80" align="center" />
        <el-table-column prop="createdAt" :label="t('audit.createdAt')" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="operatorId" :label="t('audit.operatorId')" width="100" align="center" />
        <el-table-column prop="bizType" :label="t('audit.bizType')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="bizId" :label="t('audit.bizId')" width="100" align="center" />
        <el-table-column prop="action" :label="t('audit.action')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="result" :label="t('audit.result')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" effect="dark">
              {{ row.result }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showDetail(row)">
              {{ t('audit.viewDetail') }}
            </el-button>
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
    </el-card>

    <el-dialog v-model="detailDialogVisible" :title="t('audit.detail')" width="700px">
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="t('audit.createdAt')">{{ formatDate(detailData.createdAt) }}</el-descriptions-item>
        <el-descriptions-item :label="t('audit.operatorId')">{{ detailData.operatorId }}</el-descriptions-item>
        <el-descriptions-item :label="t('audit.bizType')">{{ detailData.bizType }}</el-descriptions-item>
        <el-descriptions-item :label="t('audit.bizId')">{{ detailData.bizId }}</el-descriptions-item>
        <el-descriptions-item :label="t('audit.action')">{{ detailData.action }}</el-descriptions-item>
        <el-descriptions-item :label="t('audit.result')">
          <el-tag :type="detailData.result === 'SUCCESS' ? 'success' : 'danger'" effect="dark">
            {{ detailData.result }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('audit.ip')">{{ detailData.ip || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="data-section" v-if="detailData.beforeData">
        <h4 class="data-title">{{ t('audit.beforeData') }}</h4>
        <pre class="json-pre">{{ formatJson(detailData.beforeData) }}</pre>
      </div>

      <div class="data-section" v-if="detailData.afterData">
        <h4 class="data-title">{{ t('audit.afterData') }}</h4>
        <pre class="json-pre">{{ formatJson(detailData.afterData) }}</pre>
      </div>

      <div class="governance-nav">
        <el-button type="primary" @click="navigateToGovernanceChain">
          {{ t('governance.viewChain') }}
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { pageAuditLogs, type SysAuditLog, type AuditLogQuery } from '@/api/audit'

const { t } = useI18n()
const route = useRoute()

const loading = ref(false)
const tableData = ref<SysAuditLog[]>([])
const total = ref(0)
const detailDialogVisible = ref(false)
const detailData = ref<SysAuditLog>({} as SysAuditLog)
const timeRange = ref<[string, string] | null>(null)

const queryParams = reactive<AuditLogQuery>({
  pageNum: 1,
  pageSize: 10,
  bizType: '',
  bizId: undefined,
  action: '',
  result: '',
  operatorId: undefined,
  startTime: undefined,
  endTime: undefined
})

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const formatJson = (jsonStr: string) => {
  if (!jsonStr) return '-'
  try {
    return JSON.stringify(JSON.parse(jsonStr), null, 2)
  } catch {
    return jsonStr
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageAuditLogs(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load audit logs:', err)
    ElMessage.error(t('common.loadFailed'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  loadData()
}

const handleRefresh = () => {
  loadData()
}

const handleTimeChange = (val: [string, string] | null) => {
  if (val) {
    // Append local timezone offset to match backend OffsetDateTime expectation
    // Format: YYYY-MM-DDTHH:mm:ss+08:00 (or Z for UTC)
    const offset = new Date().getTimezoneOffset()
    const sign = offset <= 0 ? '+' : '-'
    const absOffset = Math.abs(offset)
    const hours = String(Math.floor(absOffset / 60)).padStart(2, '0')
    const minutes = String(absOffset % 60).padStart(2, '0')
    const tzOffset = `${sign}${hours}:${minutes}`
    queryParams.startTime = val[0] + tzOffset
    queryParams.endTime = val[1] + tzOffset
  } else {
    queryParams.startTime = undefined
    queryParams.endTime = undefined
  }
  handleSearch()
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

const showDetail = (row: SysAuditLog) => {
  detailData.value = row
  detailDialogVisible.value = true
}

const navigateToGovernanceChain = () => {
  if (!detailData.value.bizType || !detailData.value.bizId) return
  const url = `/system/governance-chain?bizType=${detailData.value.bizType}&bizId=${detailData.value.bizId}`
  window.location.href = url
}

// Initialize from query params
const initFromQuery = () => {
  if (route.query.bizType) queryParams.bizType = route.query.bizType as string
  if (route.query.bizId) queryParams.bizId = Number(route.query.bizId)
  if (route.query.action) queryParams.action = route.query.action as string
  return !!(queryParams.bizType || queryParams.bizId || queryParams.action)
}

onMounted(() => {
  const hasQuery = initFromQuery()
  if (!hasQuery) {
    loadData()
  } else {
    // loadData will be triggered by handleSearch after query is set
    handleSearch()
  }
})
</script>

<style scoped>
.audit-logs-view {
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

.table-card {
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
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.data-section {
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

.governance-nav {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
