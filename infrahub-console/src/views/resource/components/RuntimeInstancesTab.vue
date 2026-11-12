<template>
  <div class="runtime-instances-tab">
    <el-table v-loading="loading" :data="tableData" style="width: 100%" table-layout="fixed">
      <el-table-column prop="instanceName" :label="t('runtimeInstances.instanceName')" min-width="240" show-overflow-tooltip />
      <el-table-column :label="t('runtimeInstances.serviceDisplayName')" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.serviceName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.envDisplayName')" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.hostDisplayName')" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.hostname || '-' }}
          <span v-if="row.ip" class="ip-hint">({{ row.ip }})</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.resourcePool')" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.resourcePoolName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.currentVersion')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.currentVersionNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.deployStatus')" width="110" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.latestDeployStatus" :type="getDeployStatusType(row.latestDeployStatus)" effect="plain" size="small">
            {{ row.latestDeployStatus }}
          </el-tag>
          <span v-else class="text-placeholder">{{ t('runtimeInstances.noDeploy') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.monitorStatus')" width="110" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.monitorStatus" :type="getMonitorStatusType(row.monitorStatus)" effect="plain" size="small">
            {{ row.monitorStatus }}
          </el-tag>
          <span v-else class="text-placeholder">{{ t('runtimeInstances.noMonitor') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('runtimeInstances.deployTime')" width="170">
        <template #default="{ row }">
          {{ formatDate(row.latestDeployAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="110" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="viewInstanceDetail(row)">
            {{ t('runtimeInstances.status') }}
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

    <div v-if="error" class="error-message">
      <el-alert :title="error" type="error" show-icon :closable="false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  pageRuntimeServiceInstances,
  getRuntimeServiceInstanceResourceView,
  type RuntimeServiceInstance,
  type RuntimeInstanceResourceView
} from '@/api/runtime'

interface InstanceWithResourceView extends RuntimeServiceInstance {
  serviceName?: string;
  envName?: string;
  hostname?: string;
  ip?: string;
  resourcePoolName?: string;
  currentVersionNo?: string;
  latestDeployStatus?: string;
  latestDeployAt?: string;
  monitorStatus?: string;
}

const props = defineProps<{
  envId?: number;
  hostId?: number;
  serviceId?: number;
}>()

const emit = defineEmits<{
  (e: 'loaded', count: number): void
}>()

const { t } = useI18n()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const tableData = ref<InstanceWithResourceView[]>([])
const total = ref(0)
const requestId = ref(0)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10
})

const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getDeployStatusType = (status?: string) => {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'RUNNING':
    case 'PENDING': return 'warning'
    default: return 'info'
  }
}

const getMonitorStatusType = (status?: string) => {
  switch (status) {
    case 'UP': return 'success'
    case 'DOWN': return 'danger'
    case 'UNKNOWN': return 'info'
    default: return 'warning'
  }
}

const viewInstanceDetail = (row: InstanceWithResourceView) => {
  router.push({
    path: '/resource/runtime-instances',
    query: { keyword: row.instanceName }
  })
}

const loadData = async () => {
  const currentRequestId = ++requestId.value
  loading.value = true
  error.value = ''

  try {
    const instanceQuery: Record<string, unknown> = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }
    if (props.envId) instanceQuery.envId = props.envId
    if (props.hostId) instanceQuery.hostId = props.hostId
    if (props.serviceId) instanceQuery.serviceId = props.serviceId

    const instancesRes = await pageRuntimeServiceInstances(instanceQuery)
    if (currentRequestId !== requestId.value) return

    const instances = instancesRes.records || []
    total.value = instancesRes.total || 0
    emit('loaded', total.value)

    tableData.value = instances.map(instance => ({
      ...instance,
      serviceName: undefined,
      envName: undefined,
      hostname: undefined,
      ip: undefined,
      resourcePoolName: undefined,
      currentVersionNo: undefined,
      latestDeployStatus: undefined,
      latestDeployAt: undefined,
      monitorStatus: undefined
    }))

    loading.value = false

    ;(async () => {
      const concurrencyLimit = 5
      for (let i = 0; i < instances.length; i += concurrencyLimit) {
        if (currentRequestId !== requestId.value) return
        const batch = instances.slice(i, i + concurrencyLimit)
        await Promise.all(
          batch.map(async instance => {
            if (currentRequestId !== requestId.value) return
            try {
              const view = await getRuntimeServiceInstanceResourceView(instance.id)
              if (currentRequestId !== requestId.value) return
              const idx = tableData.value.findIndex(item => item.id === instance.id)
              if (idx !== -1 && view) {
                tableData.value[idx] = {
                  ...tableData.value[idx],
                  serviceName: view.serviceName,
                  envName: view.envName,
                  hostname: view.hostname,
                  ip: view.ip,
                  resourcePoolName: view.resourcePoolName,
                  currentVersionNo: view.currentVersionNo,
                  latestDeployStatus: view.latestDeployStatus,
                  latestDeployAt: view.latestDeployAt,
                  monitorStatus: view.monitorStatus
                }
              }
            } catch (err) {
              console.warn(`Failed to load resource view for instance ${instance.id}:`, err)
            }
          })
        )
      }
    })()
  } catch (err) {
    console.error('Failed to load runtime instances:', err)
    if (currentRequestId === requestId.value) {
      error.value = t('common.loadFailed')
    }
  } finally {
    if (currentRequestId === requestId.value) {
      loading.value = false
    }
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

watch(() => [props.envId, props.hostId, props.serviceId], () => {
  queryParams.pageNum = 1
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.runtime-instances-tab {
  min-height: 200px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.ip-hint {
  font-size: var(--ih-font-size-xs);
  color: var(--ih-text-secondary);
}

.text-placeholder {
  color: var(--ih-text-secondary);
  font-size: 12px;
}

.error-message {
  margin-top: 16px;
}
</style>
