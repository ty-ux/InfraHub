<template>
  <div class="resource-pools-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('resourcePools.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('resourcePools.list') }}</span>
          <el-button size="small" @click="handleRefresh">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-input
          v-model="queryParams.keyword"
          :placeholder="t('resourcePools.searchPlaceholder')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.poolType"
          :placeholder="t('resourcePools.poolType')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option label="VM" value="VM" />
          <el-option label="BARE_METAL" value="BARE_METAL" />
          <el-option label="CONTAINER" value="CONTAINER" />
        </el-select>
        <el-select
          v-model="queryParams.status"
          :placeholder="t('resourcePools.status')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option :label="t('common.enabled')" value="ENABLED" />
          <el-option :label="t('common.disabled')" value="DISABLED" />
        </el-select>
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%">
        <el-table-column prop="poolCode" :label="t('resourcePools.poolCode')" min-width="120" />
        <el-table-column prop="poolName" :label="t('resourcePools.poolName')" min-width="150" />
        <el-table-column prop="poolType" :label="t('resourcePools.poolType')" width="100" />
        <el-table-column prop="region" :label="t('resourcePools.region')" width="100" />
        <el-table-column :label="t('resourcePools.totalCapacity')" min-width="180">
          <template #default="{ row }">
            <span>CPU: {{ row.totalCpu?.toFixed(1) || 0 }} | </span>
            <span>MEM: {{ row.totalMemoryGb?.toFixed(1) || 0 }}GB | </span>
            <span>DISK: {{ row.totalDiskGb?.toFixed(1) || 0 }}GB</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('resourcePools.usedCapacity')" min-width="180">
          <template #default="{ row }">
            <span>CPU: {{ row.usedCpu?.toFixed(1) || 0 }} | </span>
            <span>MEM: {{ row.usedMemoryGb?.toFixed(1) || 0 }}GB | </span>
            <span>DISK: {{ row.usedDiskGb?.toFixed(1) || 0 }}GB</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('resourcePools.status')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" effect="dark">
              {{ row.status === 'ENABLED' ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('common.createdAt')" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewHosts(row)">
              {{ t('resourcePools.viewHosts') }}
            </el-button>
            <el-button link size="small" @click="showCapacity(row)">
              {{ t('resourcePools.capacity') }}
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

    <el-dialog v-model="capacityDialogVisible" :title="t('resourcePools.capacityDetails')" width="600px">
      <div v-if="capacityLoading" class="capacity-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ t('common.loading') }}</span>
      </div>
      <div v-else-if="capacityData" class="capacity-content">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('resourcePools.poolCode')">{{ capacityData.poolCode }}</el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.poolName')">{{ capacityData.resourcePoolName }}</el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.envName')">{{ capacityData.envName || '-' }}</el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.hostCount')">{{ capacityData.hostCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.onlineHostCount')">
            <el-tag type="success" size="small">{{ capacityData.onlineHostCount }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.offlineHostCount')">
            <el-tag type="danger" size="small">{{ capacityData.offlineHostCount }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.unknownHostCount')">
            <el-tag type="warning" size="small">{{ capacityData.unknownHostCount }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourcePools.runtimeInstanceCount')">{{ capacityData.runtimeInstanceCount }}</el-descriptions-item>
        </el-descriptions>
        <el-divider />
        <div class="capacity-section">
          <h4>{{ t('resourcePools.resourceUsage') }}</h4>
          <el-row :gutter="20">
            <el-col :span="8">
              <div class="usage-card">
                <div class="usage-label">CPU</div>
                <div class="usage-value">{{ capacityData.cpuTotal != null ? capacityData.cpuTotal.toFixed(1) : '-' }}</div>
                <div class="usage-detail">
                  {{ t('resourcePools.used') }}: {{ capacityData.cpuUsed != null ? capacityData.cpuUsed.toFixed(1) : '-' }}
                </div>
                <el-progress
                  v-if="capacityData.cpuUsagePercent != null"
                  :percentage="capacityData.cpuUsagePercent"
                  :stroke-width="10"
                  :color="getProgressColor(capacityData.cpuUsagePercent)"
                />
              </div>
            </el-col>
            <el-col :span="8">
              <div class="usage-card">
                <div class="usage-label">{{ t('resourcePools.memory') }} (GB)</div>
                <div class="usage-value">{{ capacityData.memoryTotalGb != null ? capacityData.memoryTotalGb.toFixed(1) : '-' }}</div>
                <div class="usage-detail">
                  {{ t('resourcePools.used') }}: {{ capacityData.memoryUsedGb != null ? capacityData.memoryUsedGb.toFixed(1) : '-' }}
                </div>
                <el-progress
                  v-if="capacityData.memoryUsagePercent != null"
                  :percentage="capacityData.memoryUsagePercent"
                  :stroke-width="10"
                  :color="getProgressColor(capacityData.memoryUsagePercent)"
                />
              </div>
            </el-col>
            <el-col :span="8">
              <div class="usage-card">
                <div class="usage-label">{{ t('resourcePools.disk') }} (GB)</div>
                <div class="usage-value">{{ capacityData.diskTotalGb != null ? capacityData.diskTotalGb.toFixed(1) : '-' }}</div>
                <div class="usage-detail">
                  {{ t('resourcePools.used') }}: {{ capacityData.diskUsedGb != null ? capacityData.diskUsedGb.toFixed(1) : '-' }}
                </div>
                <el-progress
                  v-if="capacityData.diskUsagePercent != null"
                  :percentage="capacityData.diskUsagePercent"
                  :stroke-width="10"
                  :color="getProgressColor(capacityData.diskUsagePercent)"
                />
              </div>
            </el-col>
          </el-row>
        </div>
      </div>
      <template #footer>
        <el-button @click="capacityDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { pageResourcePools, getResourcePoolCapacity, type ResourcePool, type ResourcePoolCapacity } from '@/api/asset'

const router = useRouter()
const { t } = useI18n()

const loading = ref(false)
const tableData = ref<ResourcePool[]>([])
const total = ref(0)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  poolType: '',
  status: ''
})

const capacityDialogVisible = ref(false)
const capacityLoading = ref(false)
const capacityData = ref<ResourcePoolCapacity | null>(null)

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getProgressColor = (percent: number) => {
  if (percent >= 80) return 'var(--color-bad)'
  if (percent >= 60) return 'var(--color-accent)'
  return 'var(--color-ok)'
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageResourcePools(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load resource pools:', err)
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

const handleSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  loadData()
}

const handleCurrentChange = (page: number) => {
  queryParams.pageNum = page
  loadData()
}

const viewHosts = (row: ResourcePool) => {
  router.push({
    path: '/resource/hosts',
    query: { resourcePoolId: row.id }
  })
}

const showCapacity = async (row: ResourcePool) => {
  capacityDialogVisible.value = true
  capacityLoading.value = true
  capacityData.value = null
  try {
    capacityData.value = await getResourcePoolCapacity(row.id)
  } catch (err) {
    console.error('Failed to load capacity:', err)
    ElMessage.error(t('common.loadFailed'))
    capacityDialogVisible.value = false
  } finally {
    capacityLoading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.resource-pools-view {
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
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.capacity-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--ih-text-secondary);
}

.capacity-content {
  padding: 0 var(--space-xs);
}

.capacity-section {
  margin-top: var(--space-md);
}

.capacity-section h4 {
  margin-bottom: var(--space-md);
  font-size: 14px;
  color: var(--ih-text-secondary);
}

.usage-card {
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.usage-label {
  margin-bottom: var(--space-2xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.usage-value {
  margin-bottom: var(--space-2xs);
  font-family: var(--font-outlier);
  font-size: var(--text-2xl);
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.usage-detail {
  margin-bottom: var(--space-xs);
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
  color: var(--color-muted);
  font-variant-numeric: tabular-nums;
}
</style>
