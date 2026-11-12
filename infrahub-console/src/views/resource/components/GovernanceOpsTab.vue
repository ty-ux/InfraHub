<template>
  <div class="governance-ops-tab">
    <!-- Overview Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.ops.overview.title') }}</span>
    </div>
    <div v-if="overviewLoading" class="section-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert v-else-if="overviewError" :title="overviewError" type="error" :closable="false" show-icon />
    <div v-else-if="overview" class="overview-grid">
      <div class="overview-card">
        <div class="overview-value">{{ overview.totalRiskCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.totalRiskCount') }}</div>
      </div>
      <div class="overview-card overview-card-danger">
        <div class="overview-value">{{ overview.criticalRiskCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.criticalRiskCount') }}</div>
      </div>
      <div class="overview-card overview-card-warning">
        <div class="overview-value">{{ overview.warningRiskCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.warningRiskCount') }}</div>
      </div>
      <div class="overview-card">
        <div class="overview-value">{{ overview.openSuggestionCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.openSuggestionCount') }}</div>
      </div>
      <div class="overview-card overview-card-info">
        <div class="overview-value">{{ overview.confirmedPlanCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.confirmedPlanCount') }}</div>
      </div>
      <div class="overview-card overview-card-running">
        <div class="overview-value">{{ overview.runningExecutionCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.runningExecutionCount') }}</div>
      </div>
      <div class="overview-card overview-card-success">
        <div class="overview-value">{{ overview.completedExecutionCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.completedExecutionCount') }}</div>
      </div>
      <div class="overview-card overview-card-danger">
        <div class="overview-value">{{ overview.failedExecutionCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.failedExecutionCount') }}</div>
      </div>
      <div class="overview-card">
        <div class="overview-value">{{ overview.impactRecordedCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.impactRecordedCount') }}</div>
      </div>
      <div class="overview-card overview-card-info">
        <div class="overview-value">{{ formatDuration(overview.avgCloseDurationMs) }}</div>
        <div class="overview-label">{{ t('resourceGovernance.ops.overview.avgCloseDuration') }}</div>
      </div>
    </div>

    <!-- SLA Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.ops.sla.title') }}</span>
    </div>
    <div v-if="slaLoading" class="section-loading">
      <el-skeleton :rows="2" animated />
    </div>
    <el-alert v-else-if="slaError" :title="slaError" type="error" :closable="false" show-icon />
    <div v-else-if="sla" class="sla-grid">
      <div class="sla-card">
        <div class="sla-value">{{ formatDuration(sla.avgSuggestionAckDurationMs) }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.avgSuggestionAckDuration') }}</div>
      </div>
      <div class="sla-card">
        <div class="sla-value">{{ formatDuration(sla.avgPlanConfirmDurationMs) }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.avgPlanConfirmDuration') }}</div>
      </div>
      <div class="sla-card">
        <div class="sla-value">{{ formatDuration(sla.avgExecutionCompleteDurationMs) }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.avgExecutionCompleteDuration') }}</div>
      </div>
      <div class="sla-card sla-card-danger">
        <div class="sla-value">{{ sla.overdueSuggestionCount }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.overdueSuggestionCount') }}</div>
      </div>
      <div class="sla-card sla-card-danger">
        <div class="sla-value">{{ sla.overduePlanCount }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.overduePlanCount') }}</div>
      </div>
      <div class="sla-card sla-card-danger">
        <div class="sla-value">{{ sla.overdueExecutionCount }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.overdueExecutionCount') }}</div>
      </div>
      <div class="sla-card">
        <div class="sla-value">{{ formatDuration(sla.slaTargetMs) }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.slaTarget') }}</div>
      </div>
      <div class="sla-card" :class="sla.slaHitRate >= 0.8 ? 'sla-card-success' : 'sla-card-warning'">
        <div class="sla-value">{{ formatPercent(sla.slaHitRate) }}</div>
        <div class="sla-label">{{ t('resourceGovernance.ops.sla.slaHitRate') }}</div>
      </div>
    </div>

    <!-- Trend Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.ops.trend.title') }}</span>
      <el-select v-model="trendInterval" size="small" style="width: 100px; margin-left: 12px;" @change="loadTrends">
        <el-option :label="t('resourceGovernance.ops.interval.HOUR')" value="HOUR" />
        <el-option :label="t('resourceGovernance.ops.interval.DAY')" value="DAY" />
        <el-option :label="t('resourceGovernance.ops.interval.WEEK')" value="WEEK" />
        <el-option :label="t('resourceGovernance.ops.interval.MONTH')" value="MONTH" />
      </el-select>
    </div>
    <div v-if="trendLoading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>
    <el-alert v-else-if="trendError" :title="trendError" type="error" :closable="false" show-icon />
    <el-table v-else :data="trends" stripe size="small">
      <el-table-column prop="timeBucket" :label="t('resourceGovernance.ops.trend.timeBucket')" min-width="120" />
      <el-table-column prop="riskCount" :label="t('resourceGovernance.ops.trend.riskCount')" width="110" align="right" />
      <el-table-column prop="suggestionCount" :label="t('resourceGovernance.ops.trend.suggestionCount')" width="110" align="right" />
      <el-table-column prop="actionPlanCount" :label="t('resourceGovernance.ops.trend.actionPlanCount')" width="110" align="right" />
      <el-table-column prop="executionCount" :label="t('resourceGovernance.ops.trend.executionCount')" width="110" align="right" />
      <el-table-column prop="impactCount" :label="t('resourceGovernance.ops.trend.impactCount')" width="110" align="right" />
      <el-table-column :label="t('resourceGovernance.ops.trend.successFailed')" width="120" align="center">
        <template #default="{ row }">
          <span class="success-count">{{ row.successCount }}</span>
          <span class="separator">/</span>
          <span class="failed-count">{{ row.failedCount }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- Effectiveness Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.ops.effectiveness.title') }}</span>
    </div>
    <div v-if="effectivenessLoading" class="section-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert v-else-if="effectivenessError" :title="effectivenessError" type="error" :closable="false" show-icon />
    <template v-else-if="effectivenessList.length > 0">
      <div v-for="(item, index) in effectivenessList" :key="index" class="effectiveness-section">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item :label="t('resourceGovernance.ops.effectiveness.impactType')">
            {{ item.impactType }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.ops.effectiveness.beforeRiskLevel')">
            <el-tag :type="getRiskTagType(item.beforeRiskLevel)" size="small">
              {{ t(`resourceGovernance.riskLevel.${item.beforeRiskLevel}`) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.ops.effectiveness.afterRiskLevel')">
            <el-tag :type="getRiskTagType(item.afterRiskLevel)" size="small">
              {{ t(`resourceGovernance.riskLevel.${item.afterRiskLevel}`) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.ops.effectiveness.sampleSize')">
            {{ item.sampleSize }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="effectiveness-stats">
          <div class="stat-item stat-improved">
            <span class="stat-label">{{ t('resourceGovernance.ops.effectiveness.improvedCount') }}</span>
            <span class="stat-value">{{ item.improvedCount }}</span>
          </div>
          <div class="stat-item stat-unchanged">
            <span class="stat-label">{{ t('resourceGovernance.ops.effectiveness.unchangedCount') }}</span>
            <span class="stat-value">{{ item.unchangedCount }}</span>
          </div>
          <div class="stat-item stat-worsened">
            <span class="stat-label">{{ t('resourceGovernance.ops.effectiveness.worsenedCount') }}</span>
            <span class="stat-value">{{ item.worsenedCount }}</span>
          </div>
          <div class="stat-item" :class="item.improvementRate >= 0.5 ? 'stat-improved' : 'stat-unchanged'">
            <span class="stat-label">{{ t('resourceGovernance.ops.effectiveness.improvementRate') }}</span>
            <span class="stat-value">{{ formatPercent(item.improvementRate) }}</span>
          </div>
        </div>
      </div>
    </template>
    <div v-if="!effectivenessLoading && !effectivenessError && effectivenessList.length === 0" class="empty-data">
      {{ t('common.noData') }}
    </div>

    <!-- Review Items Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.ops.reviewItems.title') }}</span>
      <el-pagination
        v-model:current-page="reviewPageNum"
        v-model:page-size="reviewPageSize"
        :total="reviewTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        size="small"
        style="margin-left: auto;"
        @size-change="loadReviewItems"
        @current-change="loadReviewItems"
      />
    </div>
    <div v-if="reviewLoading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>
    <el-alert v-else-if="reviewError" :title="reviewError" type="error" :closable="false" show-icon />
    <el-table v-else :data="reviewItems" stripe size="small">
      <el-table-column prop="itemType" :label="t('resourceGovernance.ops.reviewItems.itemType')" width="120">
        <template #default="{ row }">
          {{ t(`resourceGovernance.ops.reviewItems.itemTypes.${row.itemType}`) }}
        </template>
      </el-table-column>
      <el-table-column prop="itemNo" :label="t('resourceGovernance.ops.reviewItems.itemNo')" width="140" />
      <el-table-column prop="title" :label="t('resourceGovernance.ops.reviewItems.title')" min-width="200" show-overflow-tooltip />
      <el-table-column prop="riskLevel" :label="t('resourceGovernance.ops.reviewItems.riskLevel')" width="110">
        <template #default="{ row }">
          <el-tag :type="getRiskTagType(row.riskLevel)" size="small">
            {{ t(`resourceGovernance.riskLevel.${row.riskLevel}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" :label="t('resourceGovernance.ops.reviewItems.status')" width="140">
        <template #default="{ row }">
          <el-tag :type="getStatusTagType(row.status)" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="ownerName" :label="t('resourceGovernance.ops.reviewItems.ownerName')" width="140" show-overflow-tooltip />
      <el-table-column prop="createdAt" :label="t('resourceGovernance.ops.reviewItems.createdAt')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column prop="dueAt" :label="t('resourceGovernance.ops.reviewItems.dueAt')" width="160">
        <template #default="{ row }">
          {{ row.dueAt ? formatDateTime(row.dueAt) : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="reason" :label="t('resourceGovernance.ops.reviewItems.reason')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.reason || '-' }}
        </template>
      </el-table-column>
    </el-table>

    <!-- Manual Only Notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.ops.manualOnlyNotice') }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import {
  getGovernanceOpsOverview,
  getGovernanceOpsTrends,
  getGovernanceOpsSla,
  getGovernanceOpsEffectiveness,
  listGovernanceOpsReviewItems,
  type GovernanceOpsOverviewResponse,
  type GovernanceTrendPoint,
  type GovernanceSlaResponse,
  type GovernanceEffectivenessResponse,
  type GovernanceReviewItemResponse,
  type OpsInterval,
  type RiskLevel
} from '@/api/asset'
import type { SuggestionStatus, ActionPlanStatus, ActionExecutionStatus, ImpactType } from '@/api/asset'

const props = defineProps<{
  envId?: number
  resourcePoolId?: number
  serviceId?: number
}>()

const { t } = useI18n()

// Overview state
const overview = ref<GovernanceOpsOverviewResponse | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')

// Trend state
const trends = ref<GovernanceTrendPoint[]>([])
const trendLoading = ref(false)
const trendError = ref('')
const trendInterval = ref<OpsInterval>('DAY')

// SLA state
const sla = ref<GovernanceSlaResponse | null>(null)
const slaLoading = ref(false)
const slaError = ref('')

// Effectiveness state
const effectivenessList = ref<GovernanceEffectivenessResponse[]>([])
const effectivenessLoading = ref(false)
const effectivenessError = ref('')

// Review items state
const reviewItems = ref<GovernanceReviewItemResponse[]>([])
const reviewLoading = ref(false)
const reviewError = ref('')
const reviewPageNum = ref(1)
const reviewPageSize = ref(10)
const reviewTotal = ref(0)

// Helper functions
function formatDuration(ms: number): string {
  if (!ms || ms === 0) return '-'
  const hours = Math.floor(ms / (1000 * 60 * 60))
  const minutes = Math.floor((ms % (1000 * 60 * 60)) / (1000 * 60))
  if (hours > 24) {
    const days = Math.floor(hours / 24)
    return `${days}d ${hours % 24}h`
  }
  return `${hours}h ${minutes}m`
}

function formatPercent(rate: number): string {
  if (rate === null || rate === undefined) return '-'
  return `${(rate * 100).toFixed(1)}%`
}

function formatDateTime(dateStr: string): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function getRiskTagType(level: string): 'danger' | 'warning' | 'success' | 'info' {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    case 'NORMAL': return 'success'
    default: return 'info'
  }
}

function getStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCESS' || status === 'EXECUTED' || status === 'RESOLVED') return 'success'
  if (status === 'RUNNING' || status === 'CONFIRMED' || status === 'ACKED') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  return 'info'
}

// API calls
async function loadOverview() {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    const params: Record<string, unknown> = {}
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId
    overview.value = await getGovernanceOpsOverview(params)
  } catch (err) {
    overviewError.value = t('resourceGovernance.ops.overview.loadFailed')
    console.error('Failed to load overview:', err)
  } finally {
    overviewLoading.value = false
  }
}

async function loadTrends() {
  trendLoading.value = true
  trendError.value = ''
  try {
    const params: Record<string, unknown> = { interval: trendInterval.value }
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId
    trends.value = await getGovernanceOpsTrends(params)
  } catch (err) {
    trendError.value = t('resourceGovernance.ops.trend.loadFailed')
    console.error('Failed to load trends:', err)
  } finally {
    trendLoading.value = false
  }
}

async function loadSla() {
  slaLoading.value = true
  slaError.value = ''
  try {
    const params: Record<string, unknown> = {}
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId
    sla.value = await getGovernanceOpsSla(params)
  } catch (err) {
    slaError.value = t('resourceGovernance.ops.sla.loadFailed')
    console.error('Failed to load SLA:', err)
  } finally {
    slaLoading.value = false
  }
}

async function loadEffectiveness() {
  effectivenessLoading.value = true
  effectivenessError.value = ''
  try {
    const params: Record<string, unknown> = {}
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId
    effectivenessList.value = await getGovernanceOpsEffectiveness(params)
  } catch (err) {
    effectivenessError.value = t('resourceGovernance.ops.effectiveness.loadFailed')
    console.error('Failed to load effectiveness:', err)
  } finally {
    effectivenessLoading.value = false
  }
}

async function loadReviewItems() {
  reviewLoading.value = true
  reviewError.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: reviewPageNum.value,
      pageSize: reviewPageSize.value
    }
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId
    const result = await listGovernanceOpsReviewItems(params)
    reviewItems.value = result.records || []
    reviewTotal.value = result.total || 0
  } catch (err) {
    reviewError.value = t('resourceGovernance.ops.reviewItems.loadFailed')
    console.error('Failed to load review items:', err)
  } finally {
    reviewLoading.value = false
  }
}

// Public refresh method
function refresh() {
  loadOverview()
  loadTrends()
  loadSla()
  loadEffectiveness()
  reviewPageNum.value = 1
  loadReviewItems()
}

// Watch props changes
watch(() => [props.envId, props.resourcePoolId, props.serviceId], () => {
  refresh()
})

onMounted(() => {
  refresh()
})

defineExpose({ refresh })
</script>

<style scoped>
.governance-ops-tab {
  padding: 0;
}

.section-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  margin-top: 20px;
}

.section-header:first-child {
  margin-top: 0;
}

.section-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--ih-text-primary);
}

.section-loading {
  padding: 16px;
  background: var(--ih-bg-content);
  border-radius: 4px;
}

.empty-data {
  text-align: center;
  padding: 24px;
  color: var(--ih-text-secondary);
}

/* Overview Grid */
.overview-grid,
.sla-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 0;
  margin-bottom: var(--space-md);
}

.overview-card,
.sla-card {
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.overview-card:first-child,
.sla-card:first-child {
  border-left: 0;
  padding-left: 0;
}

.overview-value,
.sla-value {
  font-family: var(--font-outlier);
  font-size: var(--text-2xl);
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.overview-card-danger .overview-value,
.sla-card-danger .sla-value {
  color: var(--color-bad);
}

.overview-card-warning .overview-value,
.overview-card-running .overview-value,
.sla-card-warning .sla-value {
  color: var(--color-accent);
}

.overview-card-success .overview-value,
.sla-card-success .sla-value {
  color: var(--color-ok);
}

.overview-card-info .overview-value {
  color: var(--color-cool);
}

.overview-label,
.sla-label {
  margin-top: var(--space-3xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.stat-item {
  display: flex;
  min-width: 110px;
  flex-direction: column-reverse;
  gap: var(--space-3xs);
  padding-left: var(--space-md);
  border-left: var(--rule-hair) solid var(--color-rule);
}

.stat-item:first-child {
  padding-left: 0;
  border-left: 0;
}

.stat-value {
  font-family: var(--font-outlier);
  font-size: var(--text-xl);
  font-weight: 400;
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.stat-improved .stat-value {
  color: var(--color-ok);
}

.stat-worsened .stat-value {
  color: var(--color-bad);
}

.stat-label {
  font-size: var(--text-xs);
  color: var(--color-muted);
}

/* Manual Only Notice */
.manual-only-notice {
  margin-top: var(--space-md);
  padding-top: var(--space-xs);
  border-top: var(--rule-hair) solid var(--color-rule);
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
}
</style>
