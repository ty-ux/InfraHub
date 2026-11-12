<template>
  <div class="capacity-risk-tab">
    <el-table v-loading="loading" :data="tableData" style="width: 100%">
      <el-table-column :label="t('resourceGovernance.capacityRisk.riskLevel')" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="getRiskLevelType(row.riskLevel)" effect="dark">
            {{ getRiskLevelLabel(row.riskLevel) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resourcePoolName" :label="t('resourceGovernance.capacityRisk.poolName')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.resourcePoolName || '-' }}</span>
          <span v-if="row.poolCode" class="pool-code">({{ row.poolCode }})</span>
        </template>
      </el-table-column>
      <el-table-column prop="envName" :label="t('resourceGovernance.capacityRisk.envName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.capacityRisk.resourceUsage')" min-width="200">
        <template #default="{ row }">
          <div class="usage-bars">
            <div class="usage-bar-item">
              <span class="usage-label">CPU:</span>
              <el-progress
                :percentage="row.cpuUsagePercent || 0"
                :stroke-width="8"
                :color="getProgressColor(row.cpuUsagePercent)"
              />
            </div>
            <div class="usage-bar-item">
              <span class="usage-label">MEM:</span>
              <el-progress
                :percentage="row.memoryUsagePercent || 0"
                :stroke-width="8"
                :color="getProgressColor(row.memoryUsagePercent)"
              />
            </div>
            <div class="usage-bar-item">
              <span class="usage-label">DISK:</span>
              <el-progress
                :percentage="row.diskUsagePercent || 0"
                :stroke-width="8"
                :color="getProgressColor(row.diskUsagePercent)"
              />
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.capacityRisk.offlineHosts')" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.offlineHostCount > 0" type="danger" size="small">
            {{ row.offlineHostCount }}
          </el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.capacityRisk.unknownHosts')" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.unknownHostCount > 0" type="warning" size="small">
            {{ row.unknownHostCount }}
          </el-tag>
          <span v-else>0</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.capacityRisk.riskReasons')" min-width="200">
        <template #default="{ row }">
          <div v-if="row.riskReasons && row.riskReasons.length > 0" class="risk-reasons">
            <el-tag
              v-for="(reason, index) in row.riskReasons.slice(0, 3)"
              :key="index"
              size="small"
              class="risk-reason-tag"
            >
              {{ reason }}
            </el-tag>
            <span v-if="row.riskReasons.length > 3" class="more-reasons">
              +{{ row.riskReasons.length - 3 }}
            </span>
          </div>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourceGovernance.capacityRisk.evaluatedAt')" width="160">
        <template #default="{ row }">
          {{ formatDate(row.evaluatedAt) }}
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
      <el-empty :description="t('resourceGovernance.capacityRisk.noRiskData')" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { pageResourcePoolRisks, type ResourcePoolRisk, type RiskLevel } from '@/api/asset'

const props = defineProps<{
  envId?: number;
  resourcePoolId?: number;
  riskLevel?: RiskLevel;
}>()

const { t } = useI18n()

const loading = ref(false)
const error = ref('')
const tableData = ref<ResourcePoolRisk[]>([])
const total = ref(0)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10
})

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

const getProgressColor = (percent?: number) => {
  if (!percent) return 'var(--color-ok)'
  if (percent >= 80) return 'var(--color-bad)'
  if (percent >= 60) return 'var(--color-accent)'
  return 'var(--color-ok)'
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

    const result = await pageResourcePoolRisks(params)
    tableData.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    console.error('Failed to load capacity risk data:', err)
    error.value = t('resourceGovernance.capacityRisk.loadFailed')
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

watch(() => [props.envId, props.resourcePoolId, props.riskLevel], () => {
  queryParams.pageNum = 1
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.capacity-risk-tab {
  min-height: 200px;
}

.pool-code {
  color: var(--ih-text-secondary);
  font-size: 12px;
  margin-left: 4px;
}

.usage-bars {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.usage-bar-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.usage-label {
  font-size: 12px;
  color: var(--ih-text-secondary);
  width: 40px;
}

.usage-bar-item :deep(.el-progress) {
  flex: 1;
}

.risk-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.risk-reason-tag {
  margin-bottom: 2px;
}

.more-reasons {
  color: var(--ih-text-secondary);
  font-size: 12px;
  align-self: center;
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
</style>
