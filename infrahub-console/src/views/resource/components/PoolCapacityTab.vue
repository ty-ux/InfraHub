<template>
  <div class="pool-capacity-tab">
    <el-table v-loading="loading" :data="tableData" style="width: 100%">
      <el-table-column prop="poolCode" :label="t('resourcePools.poolCode')" min-width="120" show-overflow-tooltip />
      <el-table-column prop="resourcePoolName" :label="t('resourcePools.poolName')" min-width="150" show-overflow-tooltip />
      <el-table-column prop="envName" :label="t('resourcePools.envName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.hostCount')" width="80" align="center">
        <template #default="{ row }">
          {{ row.hostCount || 0 }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.onlineHostCount')" width="80" align="center">
        <template #default="{ row }">
          <el-tag type="success" size="small">{{ row.onlineHostCount || 0 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.offlineHostCount')" width="80" align="center">
        <template #default="{ row }">
          <el-tag type="danger" size="small">{{ row.offlineHostCount || 0 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.unknownHostCount')" width="80" align="center">
        <template #default="{ row }">
          <el-tag type="warning" size="small">{{ row.unknownHostCount || 0 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.runtimeInstanceCount')" width="100" align="center">
        <template #default="{ row }">
          {{ row.runtimeInstanceCount || 0 }}
        </template>
      </el-table-column>
      <el-table-column :label="t('resourcePools.resourceUsage')" min-width="200">
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
      <el-table-column :label="t('common.actions')" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="viewPoolDetail(row)">
            {{ t('resourcePools.capacity') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="error" class="error-message">
      <el-alert :title="error" type="error" show-icon :closable="false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { pageResourcePools, getResourcePoolCapacity, type ResourcePool, type ResourcePoolCapacity } from '@/api/asset'

const props = defineProps<{
  envId?: number;
  resourcePoolId?: number;
}>()

const { t } = useI18n()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const tableData = ref<(ResourcePoolCapacity & { poolCode?: string })[]>([])

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
    const poolsRes = await pageResourcePools({
      pageNum: 1,
      pageSize: 100
    })

    let pools = poolsRes.records || []

    // Client-side filtering since API doesn't support envId/id params
    if (props.resourcePoolId) {
      pools = pools.filter(p => p.id === props.resourcePoolId)
    }

    // Load capacity with concurrency limit to avoid overwhelming server
    const concurrencyLimit = 5
    const results: (ResourcePoolCapacity & { poolCode?: string })[] = []

    for (let i = 0; i < pools.length; i += concurrencyLimit) {
      const batch = pools.slice(i, i + concurrencyLimit)
      const batchResults = await Promise.all(
        batch.map(async pool => {
          try {
            const capacity = await getResourcePoolCapacity(pool.id)
            // Apply envId filter on client side
            // When envId filter is set, filter out pools with empty/different envId
            if (props.envId) {
              if (!capacity.envId || capacity.envId !== props.envId) {
                return null
              }
            }
            return {
              ...capacity,
              poolCode: pool.poolCode
            }
          } catch (err) {
            console.warn(`Failed to load capacity for pool ${pool.id}:`, err)
            return null
          }
        })
      )
      results.push(...batchResults.filter(Boolean) as (ResourcePoolCapacity & { poolCode?: string })[])
    }

    tableData.value = results
  } catch (err) {
    console.error('Failed to load pool capacity data:', err)
    error.value = t('common.loadFailed')
  } finally {
    loading.value = false
  }
}

const viewPoolDetail = (row: ResourcePoolCapacity) => {
  router.push({
    path: '/resource/resource-pools',
    query: { keyword: row.poolCode || row.resourcePoolName }
  })
}

const refresh = () => {
  loadData()
}

onMounted(() => {
  loadData()
})

watch(() => [props.envId, props.resourcePoolId], () => {
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.pool-capacity-tab {
  min-height: 200px;
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

.error-message {
  margin-top: 16px;
}
</style>
