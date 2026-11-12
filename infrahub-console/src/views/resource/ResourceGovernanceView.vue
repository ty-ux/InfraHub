<template>
  <div class="governance-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('resourceGovernance.title') }}</h2>
    </div>

    <!-- Filter Bar -->
    <el-card class="filter-card">
      <div class="filter-bar">
        <el-select
          v-model="queryParams.envId"
          :placeholder="t('resourceGovernance.filter.env')"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="env in envOptions"
            :key="env.id"
            :label="env.envName"
            :value="env.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.resourcePoolId"
          :placeholder="t('resourceGovernance.filter.resourcePool')"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="pool in poolOptions"
            :key="pool.id"
            :label="pool.poolName"
            :value="pool.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.hostId"
          :placeholder="t('resourceGovernance.filter.host')"
          clearable
          filterable
          style="width: 180px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="host in hostOptions"
            :key="host.id"
            :label="`${host.hostname} (${host.ip})`"
            :value="host.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.serviceId"
          :placeholder="t('resourceGovernance.filter.service')"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="svc in serviceOptions"
            :key="svc.id"
            :label="svc.serviceName"
            :value="svc.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.status"
          :placeholder="t('resourceGovernance.filter.status')"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option :label="t('resourceGovernance.status.online')" value="ONLINE" />
          <el-option :label="t('resourceGovernance.status.offline')" value="OFFLINE" />
          <el-option :label="t('resourceGovernance.status.unknown')" value="UNKNOWN" />
          <el-option :label="t('resourceGovernance.status.degraded')" value="DEGRADED" />
        </el-select>
        <el-select
          v-if="activeTab === 'capacityRisk' || activeTab === 'governanceSuggestions'"
          v-model="queryParams.riskLevel"
          :placeholder="t('resourceGovernance.capacityRisk.riskLevel')"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option :label="t('resourceGovernance.riskLevel.CRITICAL')" value="CRITICAL" />
          <el-option :label="t('resourceGovernance.riskLevel.WARNING')" value="WARNING" />
          <el-option :label="t('resourceGovernance.riskLevel.NORMAL')" value="NORMAL" />
          <el-option :label="t('resourceGovernance.riskLevel.UNKNOWN')" value="UNKNOWN" />
        </el-select>
        <el-button @click="handleRefresh">
          {{ t('common.refresh') }}
        </el-button>
      </div>
    </el-card>

    <!-- Overview Metrics -->
    <el-card class="metrics-card">
      <div class="metrics-grid">
        <div class="metric-item">
          <div class="metric-value">{{ metrics.poolCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.poolCount') }}</div>
        </div>
        <div class="metric-item">
          <div class="metric-value">{{ metrics.hostCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.hostCount') }}</div>
        </div>
        <div class="metric-item metric-success">
          <div class="metric-value">{{ metrics.onlineHostCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.onlineHostCount') }}</div>
        </div>
        <div class="metric-item metric-danger">
          <div class="metric-value">{{ metrics.offlineHostCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.offlineHostCount') }}</div>
        </div>
        <div class="metric-item metric-warning">
          <div class="metric-value">{{ metrics.unknownHostCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.unknownHostCount') }}</div>
        </div>
        <div class="metric-item">
          <div class="metric-value">{{ metrics.instanceCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.instanceCount') }}</div>
        </div>
        <div class="metric-item metric-danger">
          <div class="metric-value">{{ metrics.highRiskPoolCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.highRiskPoolCount') }}</div>
        </div>
        <div class="metric-item metric-danger">
          <div class="metric-value">{{ metrics.criticalRiskPoolCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.criticalRiskPoolCount') }}</div>
        </div>
        <div class="metric-item metric-warning">
          <div class="metric-value">{{ metrics.warningRiskPoolCount }}</div>
          <div class="metric-label">{{ t('resourceGovernance.metrics.warningRiskPoolCount') }}</div>
        </div>
      </div>
    </el-card>

    <!-- Main Content Tabs -->
    <el-card class="content-card">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane :label="t('resourceGovernance.tabs.alertWorkbench')" name="alertWorkbench">
          <AlertWorkbenchTab
            ref="alertWorkbenchRef"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.poolCapacity')" name="poolCapacity">
          <PoolCapacityTab
            ref="poolCapacityRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.hostHealth')" name="hostHealth">
          <HostHealthTab
            ref="hostHealthRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :host-id="queryParams.hostId"
            :status="queryParams.status"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.runtimeInstances')" name="runtimeInstances">
          <RuntimeInstancesTab
            ref="runtimeInstancesRef"
            :env-id="queryParams.envId"
            :host-id="queryParams.hostId"
            :service-id="queryParams.serviceId"
            @loaded="handleInstancesLoaded"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.governanceRecords')" name="governanceRecords">
          <GovernanceRecordsTab
            ref="governanceRecordsRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :host-id="queryParams.hostId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.capacityRisk')" name="capacityRisk">
          <CapacityRiskTab
            ref="capacityRiskRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :risk-level="queryParams.riskLevel as RiskLevel"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.governanceSuggestions')" name="governanceSuggestions">
          <GovernanceSuggestionsTab
            ref="governanceSuggestionsRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :risk-level="queryParams.riskLevel as RiskLevel"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.governanceOps')" name="governanceOps">
          <GovernanceOpsTab
            ref="governanceOpsRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.alertRules')" name="alertRules">
          <AlertRulesTab
            ref="alertRulesRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.alertRecords')" name="alertRecords">
          <AlertRecordsTab
            ref="alertRecordsRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.reports')" name="reports">
          <ReportsTab
            ref="reportsRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.tabs.followupReview')" name="followupReview">
          <NotificationFollowupReviewTab
            ref="followupReviewRef"
            :env-id="queryParams.envId"
            :resource-pool-id="queryParams.resourcePoolId"
            :service-id="queryParams.serviceId"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { pageResourcePools, pageAssetHosts, getResourcePoolCapacity, pageResourcePoolRisks, type ResourcePool, type AssetHost, type RiskLevel } from '@/api/asset'
import { serviceApi, envApi } from '@/api/resource'
import PoolCapacityTab from './components/PoolCapacityTab.vue'
import AlertWorkbenchTab from './components/AlertWorkbenchTab.vue'
import HostHealthTab from './components/HostHealthTab.vue'
import RuntimeInstancesTab from './components/RuntimeInstancesTab.vue'
import GovernanceRecordsTab from './components/GovernanceRecordsTab.vue'
import CapacityRiskTab from './components/CapacityRiskTab.vue'
import GovernanceSuggestionsTab from './components/GovernanceSuggestionsTab.vue'
import GovernanceOpsTab from './components/GovernanceOpsTab.vue'
import AlertRulesTab from './components/AlertRulesTab.vue'
import AlertRecordsTab from './components/AlertRecordsTab.vue'
import ReportsTab from './components/ReportsTab.vue'
import NotificationFollowupReviewTab from './components/NotificationFollowupReviewTab.vue'

interface Env {
  id: number;
  envCode: string;
  envName: string;
}

interface Service {
  id: number;
  serviceCode: string;
  serviceName: string;
}

const { t } = useI18n()
const route = useRoute()

const activeTab = ref('alertWorkbench')

// Lookup data
const envOptions = ref<Env[]>([])
const poolOptions = ref<ResourcePool[]>([])
const hostOptions = ref<AssetHost[]>([])
const serviceOptions = ref<Service[]>([])

// Pool capacity data for metrics
const poolCapacities = ref<Map<number, { cpuUsagePercent?: number; memoryUsagePercent?: number }>>(new Map())

const queryParams = reactive({
  envId: undefined as number | undefined,
  resourcePoolId: undefined as number | undefined,
  hostId: undefined as number | undefined,
  serviceId: undefined as number | undefined,
  status: '',
  riskLevel: undefined as string | undefined
})

// Metrics computed from loaded data
const instanceCount = ref(0)
const criticalRiskPoolCount = ref(0)
const warningRiskPoolCount = ref(0)

const metrics = computed(() => {
  let onlineHostCount = 0
  let offlineHostCount = 0
  let unknownHostCount = 0

  hostOptions.value.forEach(host => {
    if (queryParams.envId && host.envId !== queryParams.envId) return
    if (queryParams.resourcePoolId && host.resourcePoolId !== queryParams.resourcePoolId) return

    if (host.agentStatus === 'ONLINE') onlineHostCount++
    else if (host.agentStatus === 'OFFLINE') offlineHostCount++
    else unknownHostCount++
  })

  // Calculate high risk pools (usage > 80%)
  let highRiskPoolCount = 0
  poolCapacities.value.forEach(cap => {
    if ((cap.cpuUsagePercent ?? 0) >= 80 || (cap.memoryUsagePercent ?? 0) >= 80) {
      highRiskPoolCount++
    }
  })

  return {
    poolCount: queryParams.resourcePoolId ? 1 : poolOptions.value.length,
    hostCount: hostOptions.value.filter(h =>
      (!queryParams.envId || h.envId === queryParams.envId) &&
      (!queryParams.resourcePoolId || h.resourcePoolId === queryParams.resourcePoolId)
    ).length,
    onlineHostCount,
    offlineHostCount,
    unknownHostCount,
    instanceCount: instanceCount.value,
    highRiskPoolCount,
    criticalRiskPoolCount: criticalRiskPoolCount.value,
    warningRiskPoolCount: warningRiskPoolCount.value
  }
})

// Tab refs for refresh
const poolCapacityRef = ref<InstanceType<typeof PoolCapacityTab> | null>(null)
const alertWorkbenchRef = ref<InstanceType<typeof AlertWorkbenchTab> | null>(null)
const hostHealthRef = ref<InstanceType<typeof HostHealthTab> | null>(null)
const runtimeInstancesRef = ref<InstanceType<typeof RuntimeInstancesTab> | null>(null)
const governanceRecordsRef = ref<InstanceType<typeof GovernanceRecordsTab> | null>(null)
const capacityRiskRef = ref<InstanceType<typeof CapacityRiskTab> | null>(null)
const governanceSuggestionsRef = ref<InstanceType<typeof GovernanceSuggestionsTab> | null>(null)
const governanceOpsRef = ref<InstanceType<typeof GovernanceOpsTab> | null>(null)
const alertRulesRef = ref<InstanceType<typeof AlertRulesTab> | null>(null)
const alertRecordsRef = ref<InstanceType<typeof AlertRecordsTab> | null>(null)
const reportsRef = ref<InstanceType<typeof ReportsTab> | null>(null)
const followupReviewRef = ref<InstanceType<typeof NotificationFollowupReviewTab> | null>(null)

const loadLookupData = async () => {
  try {
    const [envRes, poolRes, hostRes, svcRes] = await Promise.all([
      envApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Env[] }>,
      pageResourcePools({ pageNum: 1, pageSize: 200 }),
      pageAssetHosts({ pageNum: 1, pageSize: 200 }),
      serviceApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Service[] }>
    ])

    envOptions.value = envRes.records || []
    poolOptions.value = poolRes.records || []
    hostOptions.value = hostRes.records || []
    serviceOptions.value = svcRes.records || []
  } catch (err) {
    console.error('Failed to load lookup data:', err)
  }
}

const loadPoolCapacitiesForMetrics = async () => {
  const pools = queryParams.resourcePoolId
    ? poolOptions.value.filter(p => p.id === queryParams.resourcePoolId)
    : poolOptions.value

  for (const pool of pools) {
    try {
      const cap = await getResourcePoolCapacity(pool.id)
      poolCapacities.value.set(pool.id, {
        cpuUsagePercent: cap.cpuUsagePercent,
        memoryUsagePercent: cap.memoryUsagePercent
      })
    } catch {
      // Ignore errors for metrics
    }
  }
}

const loadRiskMetrics = async () => {
  try {
    const result = await pageResourcePoolRisks({ pageSize: 200 })
    let critical = 0
    let warning = 0
    result.records?.forEach(risk => {
      if (risk.riskLevel === 'CRITICAL') critical++
      else if (risk.riskLevel === 'WARNING') warning++
    })
    criticalRiskPoolCount.value = critical
    warningRiskPoolCount.value = warning
  } catch {
    // Ignore errors for metrics
  }
}

const handleFilterChange = () => {
  // Refresh current tab
  handleRefresh()
}

const handleRefresh = () => {
  loadPoolCapacitiesForMetrics()
  loadRiskMetrics()

  switch (activeTab.value) {
    case 'alertWorkbench':
      alertWorkbenchRef.value?.refresh()
      break
    case 'poolCapacity':
      poolCapacityRef.value?.refresh()
      break
    case 'hostHealth':
      hostHealthRef.value?.refresh()
      break
    case 'runtimeInstances':
      runtimeInstancesRef.value?.refresh()
      break
    case 'governanceRecords':
      governanceRecordsRef.value?.refresh()
      break
    case 'capacityRisk':
      capacityRiskRef.value?.refresh()
      break
    case 'governanceSuggestions':
      governanceSuggestionsRef.value?.refresh()
      break
    case 'governanceOps':
      governanceOpsRef.value?.refresh()
      break
    case 'alertRules':
      alertRulesRef.value?.refresh()
      break
    case 'alertRecords':
      alertRecordsRef.value?.refresh()
      break
    case 'reports':
      reportsRef.value?.refresh()
      break
    case 'followupReview':
      followupReviewRef.value?.refresh()
      break
  }
}

const handleTabChange = () => {
  // Trigger refresh for the new tab
  handleRefresh()
}

const handleInstancesLoaded = (count: number) => {
  instanceCount.value = count
}

const initFromQuery = () => {
  const { envId, resourcePoolId, hostId, serviceId, status } = route.query
  if (typeof envId === 'string') {
    const parsed = parseInt(envId, 10)
    if (!isNaN(parsed)) queryParams.envId = parsed
  }
  if (typeof resourcePoolId === 'string') {
    const parsed = parseInt(resourcePoolId, 10)
    if (!isNaN(parsed)) queryParams.resourcePoolId = parsed
  }
  if (typeof hostId === 'string') {
    const parsed = parseInt(hostId, 10)
    if (!isNaN(parsed)) queryParams.hostId = parsed
  }
  if (typeof serviceId === 'string') {
    const parsed = parseInt(serviceId, 10)
    if (!isNaN(parsed)) queryParams.serviceId = parsed
  }
  if (typeof status === 'string') {
    queryParams.status = status
  }
}

onMounted(async () => {
  await loadLookupData()
  initFromQuery()
  loadPoolCapacitiesForMetrics()
  loadRiskMetrics()
})
</script>

<style scoped>
.governance-view {
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

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
  align-items: center;
}

.metrics-card {
  margin-bottom: var(--space-md);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 0;
}

.metric-item {
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.metric-item:first-child {
  border-left: 0;
  padding-left: 0;
}

.metric-value {
  font-family: var(--font-outlier);
  font-size: var(--text-2xl);
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.metric-item.metric-success .metric-value {
  color: var(--color-ok);
}

.metric-item.metric-danger .metric-value {
  color: var(--color-bad);
}

.metric-item.metric-warning .metric-value {
  color: var(--color-accent);
}

.metric-label {
  margin-top: var(--space-3xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.content-card {
  min-height: 400px;
}
</style>
