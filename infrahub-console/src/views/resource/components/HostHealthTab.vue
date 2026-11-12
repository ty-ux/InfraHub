<template>
  <div class="host-health-tab">
    <el-table v-loading="loading" :data="tableData" style="width: 100%">
      <el-table-column prop="hostname" :label="t('hosts.hostname')" min-width="120" show-overflow-tooltip />
      <el-table-column prop="ip" :label="t('hosts.ip')" width="120" show-overflow-tooltip />
      <el-table-column prop="envName" :label="t('hosts.envDisplayName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.envName || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="resourcePoolName" :label="t('hosts.resourcePoolDisplayName')" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.resourcePoolName || '-' }}
        </template>
      </el-table-column>
      <el-table-column :label="t('hosts.healthStatus')" width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="getHealthStatusType(row.healthStatus)" effect="dark">
            {{ getHealthStatusLabel(row.healthStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('hosts.healthReason')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.healthReason || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('hosts.runtimeInstances')" width="120" align="center">
        <template #default="{ row }">
          {{ row.runtimeInstanceCount || 0 }}
        </template>
      </el-table-column>
      <el-table-column :label="t('hosts.latestDeployAt')" width="160">
        <template #default="{ row }">
          <span>{{ formatDate(row.latestDeployAt) }}</span>
          <el-tag v-if="row.latestDeployStatus" size="small" style="margin-left: 4px" :type="getDeployStatusType(row.latestDeployStatus)">
            {{ row.latestDeployStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="viewHostDetail(row)">
            {{ t('hosts.profileTitle') }}
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
import { pageAssetHosts, getHostProfile, type AssetHost, type HostProfile } from '@/api/asset'

const props = defineProps<{
  envId?: number;
  resourcePoolId?: number;
  hostId?: number;
  status?: string;
}>()

const { t } = useI18n()
const router = useRouter()

const loading = ref(false)
const error = ref('')
const tableData = ref<(HostProfile & { ip?: string })[]>([])
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

const getHealthStatusType = (status?: string) => {
  switch (status) {
    case 'ONLINE': return 'success'
    case 'OFFLINE': return 'danger'
    case 'DEGRADED': return 'warning'
    default: return 'info'
  }
}

const getHealthStatusLabel = (status?: string) => {
  switch (status) {
    case 'ONLINE': return t('hosts.healthOnline')
    case 'OFFLINE': return t('hosts.healthOffline')
    case 'DEGRADED': return t('hosts.healthDegraded')
    case 'UNKNOWN': return t('hosts.healthUnknown')
    default: return status || '-'
  }
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

const viewHostDetail = (row: HostProfile) => {
  router.push({
    path: '/resource/hosts',
    query: { keyword: row.hostname }
  })
}

const loadData = async () => {
  loading.value = true
  error.value = ''

  try {
    // Build query params for host list
    const hostQuery: Record<string, unknown> = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }
    if (props.envId) hostQuery.envId = props.envId
    if (props.resourcePoolId) hostQuery.resourcePoolId = props.resourcePoolId
    if (props.hostId) hostQuery.id = props.hostId
    if (props.status) {
      if (props.status === 'ONLINE') hostQuery.agentStatus = 'ONLINE'
      else if (props.status === 'OFFLINE') hostQuery.agentStatus = 'OFFLINE'
    }

    const hostsRes = await pageAssetHosts(hostQuery)
    const hosts = hostsRes.records || []
    total.value = hostsRes.total || 0

    // Load profile for each host
    const profilePromises = hosts.map(async host => {
      try {
        const profile = await getHostProfile(host.id)
        return {
          ...profile,
          ip: host.ip
        }
      } catch (err) {
        console.warn(`Failed to load profile for host ${host.id}:`, err)
        // Return basic info from host
        return {
          hostId: host.id,
          hostname: host.hostname,
          ip: host.ip,
          healthStatus: 'UNKNOWN' as const,
          healthReason: t('hosts.profileLoadFailed'),
          runtimeInstanceCount: 0
        }
      }
    })

    tableData.value = await Promise.all(profilePromises)
  } catch (err) {
    console.error('Failed to load host health data:', err)
    error.value = t('common.loadFailed')
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

watch(() => [props.envId, props.resourcePoolId, props.hostId, props.status], () => {
  queryParams.pageNum = 1
  loadData()
})

defineExpose({ refresh })
</script>

<style scoped>
.host-health-tab {
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
</style>
