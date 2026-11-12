<template>
  <el-drawer
    v-model="visible"
    :title="t('resourceGovernance.timeline.title')"
    direction="rtl"
    size="600px"
    @close="handleClose"
  >
    <div v-loading="loading" class="timeline-content">
      <div v-if="error" class="error-message">
        <el-alert :title="error" type="error" show-icon :closable="false" />
      </div>

      <div v-if="timelineData" class="timeline-data">
        <!-- Association Chain -->
        <div class="association-chain">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item :label="t('resourceGovernance.timeline.actionPlanNo')">
              {{ timelineData.subjectType === 'ACTION_PLAN' ? timelineData.subjectNo : (linkedInfo?.actionPlanNo || '-') }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.timeline.actionExecutionNo')">
              {{ timelineData.subjectType === 'ACTION_EXECUTION' ? timelineData.subjectNo : (linkedInfo?.actionExecutionNo || '-') }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.timeline.suggestionNo')">
              {{ linkedInfo?.suggestionNo || '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- Timeline Nodes -->
        <div class="timeline-nodes-section">
          <h4>{{ t('resourceGovernance.timeline.timelineNodes') }}</h4>
          <el-timeline>
            <el-timeline-item
              v-for="node in timelineData.nodes"
              :key="node.key"
              :timestamp="formatDate(node.time)"
              :type="getNodeTimelineType(node.status)"
              placement="top"
            >
              <div class="timeline-node-card">
                <div class="node-header">
                  <span class="node-title">{{ node.title || '-' }}</span>
                  <el-tag :type="getNodeStatusType(node.status)" size="small">
                    {{ getNodeStatusLabel(node.status) }}
                  </el-tag>
                </div>
                <div v-if="node.summary" class="node-summary">
                  {{ node.summary }}
                </div>
                <div v-if="node.actionType" class="node-action-type">
                  <el-tag size="small" type="info">{{ node.actionType }}</el-tag>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>

        <!-- Impact Section -->
        <div v-if="impactRecords.length > 0" class="impact-section">
          <h4>{{ t('resourceGovernance.timeline.impactRecords') }}</h4>
          <el-table :data="impactRecords" size="small" style="width: 100%">
            <el-table-column prop="impactType" :label="t('resourceGovernance.timeline.impactType')" min-width="180">
              <template #default="{ row }">
                <el-tag size="small">{{ getImpactTypeLabel(row.impactType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="impactSummary" :label="t('resourceGovernance.timeline.impactSummary')" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.impactSummary || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="resultStatus" :label="t('resourceGovernance.records.resultStatus')" width="110">
              <template #default="{ row }">
                <el-tag :type="getResultStatusType(row.resultStatus)" size="small">
                  {{ getResultStatusLabel(row.resultStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.timeline.evidencePayload')" width="120">
              <template #default="{ row }">
                <el-button
                  v-if="row.evidencePayload"
                  type="primary"
                  link
                  size="small"
                  @click="openPayloadDrawer(row.evidencePayload, 'evidence')"
                >
                  {{ t('resourceGovernance.timeline.viewPayload') }}
                </el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('resourceGovernance.timeline.impactPayload')" width="120">
              <template #default="{ row }">
                <el-button
                  v-if="row.impactPayload"
                  type="primary"
                  link
                  size="small"
                  @click="openPayloadDrawer(row.impactPayload, 'impact')"
                >
                  {{ t('resourceGovernance.timeline.viewPayload') }}
                </el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="!loading && timelineData?.nodes.length === 0" class="empty-message">
          <el-empty :description="t('resourceGovernance.timeline.noTimelineData')" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- Payload Detail Drawer -->
    <el-drawer
      v-model="payloadDrawerVisible"
      :title="payloadDrawerTitle"
      direction="rtl"
      size="400px"
    >
      <div class="payload-content">
        <pre class="payload-display">{{ formatPayload(currentPayload) }}</pre>
      </div>
    </el-drawer>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  getActionPlanTimeline,
  getActionExecutionTimeline,
  type TimelineResponse,
  type TimelineNodeStatus,
  type ImpactType,
  type TimelineNodeDto
} from '@/api/asset'

const props = defineProps<{
  modelValue: boolean;
  planId?: number;
  executionId?: number;
  suggestionNo?: string;
  actionPlanNo?: string;
  actionExecutionNo?: string;
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>()

const { t } = useI18n()

const visible = ref(props.modelValue)
const loading = ref(false)
const error = ref('')
const timelineData = ref<TimelineResponse | null>(null)

const linkedInfo = ref<{
  suggestionNo?: string;
  actionPlanNo?: string;
  actionExecutionNo?: string;
} | null>(null)

const payloadDrawerVisible = ref(false)
const payloadDrawerTitle = ref('')
const currentPayload = ref<Record<string, unknown> | string | null>(null)

// Extract impact records from IMPACT_RECORDED nodes (per Contract 3.1)
const impactRecords = computed(() => {
  if (!timelineData.value?.nodes) return []
  return timelineData.value.nodes
    .filter((node): node is TimelineNodeDto & { actionType: string } =>
      node.actionType === 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED'
    )
    .map((node) => ({
      key: node.key,
      impactType: node.impactType,
      impactSummary: node.impactSummary,
      resultStatus: node.resultStatus,
      impactPayload: node.impactPayload,
      evidencePayload: node.evidencePayload,
      beforePayload: node.beforePayload,
      afterPayload: node.afterPayload
    }))
})

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) {
    loadTimelineData()
  }
})

watch(visible, (val) => {
  emit('update:modelValue', val)
})

const loadTimelineData = async () => {
  loading.value = true
  error.value = ''

  linkedInfo.value = {
    suggestionNo: props.suggestionNo,
    actionPlanNo: props.actionPlanNo,
    actionExecutionNo: props.actionExecutionNo
  }

  try {
    if (props.planId) {
      timelineData.value = await getActionPlanTimeline(props.planId)
    } else if (props.executionId) {
      timelineData.value = await getActionExecutionTimeline(props.executionId)
    }
  } catch (err) {
    console.error('Failed to load timeline:', err)
    error.value = t('resourceGovernance.timeline.loadFailed')
    timelineData.value = null
  } finally {
    loading.value = false
  }
}

const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getNodeTimelineType = (status?: TimelineNodeStatus) => {
  switch (status) {
    case 'success': return 'success'
    case 'running': return 'primary'
    case 'failed': return 'danger'
    case 'pending': return 'info'
    default: return 'info'
  }
}

const getNodeStatusType = (status?: TimelineNodeStatus) => {
  switch (status) {
    case 'success': return 'success'
    case 'running': return 'warning'
    case 'failed': return 'danger'
    case 'pending': return 'info'
    default: return 'info'
  }
}

const getNodeStatusLabel = (status?: TimelineNodeStatus) => {
  switch (status) {
    case 'success': return t('resourceGovernance.timeline.nodeStatusSuccess')
    case 'running': return t('resourceGovernance.timeline.nodeStatusRunning')
    case 'failed': return t('resourceGovernance.timeline.nodeStatusFailed')
    case 'pending': return t('resourceGovernance.timeline.nodeStatusPending')
    default: return '-'
  }
}

const getImpactTypeLabel = (type?: ImpactType) => {
  switch (type) {
    case 'RESOURCE_POOL': return t('resourceGovernance.timeline.impactTypeResourcePool')
    case 'HOST': return t('resourceGovernance.timeline.impactTypeHost')
    case 'RUNTIME_INSTANCE': return t('resourceGovernance.timeline.impactTypeRuntimeInstance')
    case 'ENVIRONMENT': return t('resourceGovernance.timeline.impactTypeEnvironment')
    case 'NONE': return t('resourceGovernance.timeline.impactTypeNone')
    default: return '-'
  }
}

const getResultStatusType = (status?: string) => {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'danger'
    case 'WARNING': return 'warning'
    case 'CRITICAL': return 'danger'
    case 'INFO': return 'info'
    case 'CANCELLED': return 'info'
    default: return 'info'
  }
}

const getResultStatusLabel = (status?: string) => {
  switch (status) {
    case 'SUCCESS': return t('resourceGovernance.actionPlan.executionStatusSuccess')
    case 'FAILED': return t('resourceGovernance.actionPlan.executionStatusFailed')
    case 'WARNING': return t('resourceGovernance.riskLevel.WARNING')
    case 'CRITICAL': return t('resourceGovernance.riskLevel.CRITICAL')
    case 'INFO': return t('resourceGovernance.timeline.statusInfo')
    case 'CANCELLED': return t('resourceGovernance.actionPlan.executionStatusCancelled')
    default: return '-'
  }
}

const formatPayload = (payload?: Record<string, unknown>) => {
  if (!payload) return '-'
  try {
    return JSON.stringify(payload, null, 2)
  } catch {
    // If it's a string already or can't be stringified, show it safely
    return String(payload)
  }
}

const openPayloadDrawer = (payload: Record<string, unknown>, type: 'evidence' | 'impact') => {
  currentPayload.value = payload
  payloadDrawerTitle.value = type === 'evidence'
    ? t('resourceGovernance.timeline.evidenceDetail')
    : t('resourceGovernance.timeline.impactDetail')
  payloadDrawerVisible.value = true
}

const handleClose = () => {
  visible.value = false
  timelineData.value = null
  error.value = ''
  linkedInfo.value = null
}
</script>

<style scoped>
.timeline-content {
  padding: 0 16px;
  min-height: 200px;
}

.error-message {
  margin-bottom: 16px;
}

.association-chain {
  margin-bottom: 20px;
}

.timeline-nodes-section {
  margin-bottom: 20px;
}

.timeline-nodes-section h4,
.impact-section h4 {
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 500;
  color: var(--ih-text-secondary);
}

.timeline-node-card {
  background: var(--color-paper-3);
  border-radius: var(--radius-panel);
  padding: var(--space-sm);
}

.node-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.node-title {
  font-weight: 500;
  color: var(--ih-text-primary);
}

.node-summary {
  font-size: 13px;
  color: var(--ih-text-secondary);
  margin-bottom: 8px;
}

.node-action-type {
  margin-top: 4px;
}

.impact-section {
  margin-top: 24px;
}

.empty-message {
  margin-top: 20px;
}

.payload-content {
  padding: 16px;
}

.payload-display {
  background: var(--color-paper-3);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  padding: var(--space-sm);
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow: auto;
}
</style>