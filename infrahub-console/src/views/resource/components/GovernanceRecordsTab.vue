<template>
  <div class="governance-records-tab">
    <el-table v-loading="loading" :data="tableData" style="width: 100%">
      <el-table-column prop="id" :label="t('common.id')" width="80" />
      <el-table-column :label="t('resourceGovernance.records.eventType')" width="120">
        <template #default="{ row }">
          <el-tag effect="plain">{{ formatEventType(row.eventType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.resourcePool')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.resourcePoolName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.host')" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.hostname || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.instance')" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.instanceName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.env')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.service')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.serviceName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.executorType')" width="130">
        <template #default="{ row }">
          {{ row.executorType || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.actionPlanNo')" width="120">
        <template #default="{ row }">
          {{ row.actionPlanNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.actionExecutionNo')" width="130">
        <template #default="{ row }">
          {{ row.actionExecutionNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.suggestionNo')" width="130">
        <template #default="{ row }">
          {{ row.suggestionNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.resultStatus')" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.resultStatus === 'SUCCESS' ? 'success' : 'danger'" effect="dark" size="small">
            {{ row.resultStatus === 'SUCCESS' ? t('audit.success') : t('audit.failed') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.reason')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.reason || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.evidencePayload')" width="140">
        <template #default="{ row }">
          <el-button
            v-if="row.afterPayload"
            type="primary"
            link
            size="small"
            @click="showEvidenceDrawer(row)"
          >
            {{ t('resourceGovernance.records.viewEvidence') }}
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.createdBy')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.createdBy || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.records.createdAt')" width="160">
        <template #default="{ row }">
          {{ formatDate(row.createdAt) }}
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
      <el-empty :description="t('resourceGovernance.records.notAvailable')" />
    </div>

    <!-- Evidence Drawer -->
    <el-drawer
      v-model="evidenceDrawerVisible"
      :title="t('resourceGovernance.records.evidencePayload')"
      direction="rtl"
      size="500px"
    >
      <div v-if="selectedRecord" class="evidence-content">
        <div class="evidence-section">
          <h4>{{ t('resourceGovernance.records.actionPlanNo') }}</h4>
          <p>{{ selectedRecord.actionPlanNo || '-' }}</p>
        </div>
        <div class="evidence-section">
          <h4>{{ t('resourceGovernance.records.actionExecutionNo') }}</h4>
          <p>{{ selectedRecord.actionExecutionNo || '-' }}</p>
        </div>
        <div class="evidence-section">
          <h4>{{ t('resourceGovernance.records.suggestionNo') }}</h4>
          <p>{{ selectedRecord.suggestionNo || '-' }}</p>
        </div>
        <div class="evidence-section">
          <h4>{{ t('resourceGovernance.records.evidencePayload') }}</h4>
          <pre class="json-display">{{ formatPayload(selectedRecord.afterPayload) }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { listResourceGovernanceRecords, type ResourceGovernanceRecord } from '@/api/asset'

const props = defineProps<{
  envId?: number;
  resourcePoolId?: number;
  hostId?: number;
  serviceId?: number;
}>()

const { t } = useI18n()

const loading = ref(false)
const error = ref('')
const tableData = ref<ResourceGovernanceRecord[]>([])
const total = ref(0)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10
})

const evidenceDrawerVisible = ref(false)
const selectedRecord = ref<ResourceGovernanceRecord | null>(null)

const showEvidenceDrawer = (row: ResourceGovernanceRecord) => {
  selectedRecord.value = row
  evidenceDrawerVisible.value = true
}

const formatPayload = (payload?: string) => {
  if (!payload) return '-'
  try {
    const parsed = JSON.parse(payload)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return payload
  }
}

const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const formatEventType = (eventType?: string) => {
  if (!eventType) return '-'
  // Format event type to be more readable
  return eventType.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase())
}

const loadData = async () => {
  loading.value = true
  error.value = ''

  try {
    const query: Record<string, unknown> = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }
    if (props.envId) query.envId = props.envId
    if (props.resourcePoolId) query.resourcePoolId = props.resourcePoolId
    if (props.hostId) query.hostId = props.hostId
    if (props.serviceId) query.serviceId = props.serviceId

    const response = await listResourceGovernanceRecords(query)
    tableData.value = response.records || []
    total.value = response.total || 0
  } catch (err) {
    console.error('Failed to load governance records:', err)
    error.value = t('resourceGovernance.records.loadFailed')
  } finally {
    loading.value = false
  }
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

watch(() => [props.envId, props.resourcePoolId, props.hostId, props.serviceId], () => {
  queryParams.pageNum = 1
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.governance-records-tab {
  min-height: 200px;
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
  margin-top: 32px;
}

.evidence-content {
  padding: 0 16px;
}

.evidence-section {
  margin-bottom: 20px;
}

.evidence-section h4 {
  margin-bottom: 8px;
  color: var(--ih-text-secondary);
  font-size: 14px;
}

.evidence-section p {
  color: var(--ih-text-primary);
}

.json-display {
  background: var(--ih-bg-subtle);
  border: 1px solid var(--ih-border);
  border-radius: 4px;
  padding: 12px;
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow: auto;
}
</style>
