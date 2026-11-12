<template>
  <div class="notification-records-tab">
    <!-- Header -->
    <div class="tab-header">
      <div class="header-filters">
        <el-select
          v-model="queryParams.channelType"
          :placeholder="t('resourceGovernance.notification.channelType')"
          clearable
          size="small"
          style="width: 140px"
          @change="loadRecords"
        >
          <el-option label="INTERNAL" value="INTERNAL" />
          <el-option label="EMAIL_DRAFT" value="EMAIL_DRAFT" />
          <el-option label="WEBHOOK_DRAFT" value="WEBHOOK_DRAFT" />
        </el-select>
        <el-select
          v-model="queryParams.notificationStatus"
          :placeholder="t('resourceGovernance.notification.notificationStatus')"
          clearable
          size="small"
          style="width: 140px"
          @change="loadRecords"
        >
          <el-option :label="t('resourceGovernance.notification.statuses.DRAFTED')" value="DRAFTED" />
          <el-option :label="t('resourceGovernance.notification.statuses.MANUALLY_CONFIRMED')" value="MANUALLY_CONFIRMED" />
          <el-option :label="t('resourceGovernance.notification.statuses.CANCELLED')" value="CANCELLED" />
        </el-select>
      </div>
      <el-button size="small" @click="loadRecords">{{ t('common.refresh') }}</el-button>
    </div>

    <!-- Loading state -->
    <div v-if="loading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- Error state -->
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />

    <!-- Records table -->
    <el-table v-else :data="records" stripe size="small">
      <el-table-column prop="notificationNo" :label="t('resourceGovernance.notification.notificationNo')" width="140" />
      <el-table-column prop="alertNo" :label="t('resourceGovernance.alertRecords.alertNo')" width="140" />
      <el-table-column prop="alertTitle" :label="t('resourceGovernance.notification.alertTitle')" min-width="150" show-overflow-tooltip />
      <el-table-column prop="channelType" :label="t('resourceGovernance.notification.channelType')" width="120">
        <template #default="{ row }">
          <el-tag :type="getChannelTypeTagType(row.channelType)" size="small">
            {{ row.channelType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="notificationStatus" :label="t('resourceGovernance.notification.notificationStatus')" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusTagType(row.notificationStatus)" size="small">
            {{ t(`resourceGovernance.notification.statuses.${row.notificationStatus}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetUserName" :label="t('resourceGovernance.notification.targetUserName')" width="140" show-overflow-tooltip />
      <el-table-column prop="notifyReason" :label="t('resourceGovernance.notification.notifyReason')" min-width="150" show-overflow-tooltip />
      <el-table-column prop="createdByName" :label="t('resourceGovernance.notification.createdBy')" width="140" show-overflow-tooltip />
      <el-table-column prop="createdAt" :label="t('resourceGovernance.notification.createdAt')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">
            {{ t('common.actions') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Pagination -->
    <div v-if="!loading && !error && total > 0" class="pagination-wrapper">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        size="small"
        @size-change="loadRecords"
        @current-change="loadRecords"
      />
    </div>

    <!-- Manual only notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.notification.manualOnlyNotice') }}</p>

    <!-- Detail Drawer -->
    <el-drawer
      v-model="detailDrawerVisible"
      :title="t('resourceGovernance.notification.detailTitle')"
      size="600px"
    >
      <el-tabs v-if="selectedRecord" v-model="detailTabActive">
        <el-tab-pane :label="t('resourceGovernance.notification.detailTab')" name="detail">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item :label="t('resourceGovernance.notification.notificationNo')">
              {{ selectedRecord.notificationNo }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.alertRecords.alertNo')">
              {{ selectedRecord.alertNo }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.alertTitle')">
              {{ selectedRecord.alertTitle }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.channelType')">
              <el-tag :type="getChannelTypeTagType(selectedRecord.channelType)" size="small">
                {{ selectedRecord.channelType }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.notificationStatus')">
              <el-tag :type="getStatusTagType(selectedRecord.notificationStatus)" size="small">
                {{ t(`resourceGovernance.notification.statuses.${selectedRecord.notificationStatus}`) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.targetUserName')">
              {{ selectedRecord.targetUserName }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.subject')">
              {{ selectedRecord.subject }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.content')">
              <div class="content-detail">{{ selectedRecord.content }}</div>
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.notifyReason')">
              {{ selectedRecord.notifyReason || '-' }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.evidencePayload')">
              <pre v-if="selectedRecord.evidencePayload" class="evidence-content">{{ formatEvidencePayload(selectedRecord.evidencePayload) }}</pre>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.createdBy')">
              {{ selectedRecord.createdByName }} (ID: {{ selectedRecord.createdBy }})
            </el-descriptions-item>
            <el-descriptions-item :label="t('resourceGovernance.notification.createdAt')">
              {{ formatDateTime(selectedRecord.createdAt) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane :label="t('resourceGovernance.followup.followupRecordsTab')" name="followups">
          <NotificationFollowupsTab
            v-if="detailTabActive === 'followups'"
            :notification-record-id="selectedRecord.id"
          />
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import {
  listGovernanceAlertNotificationRecords,
  type GovernanceAlertNotificationRecordResponse,
  type GovernanceAlertNotificationChannelType,
  type GovernanceAlertNotificationStatus
} from '@/api/asset'
import NotificationFollowupsTab from './NotificationFollowupsTab.vue'

const props = defineProps<{
  alertRecordId?: number
}>()

const { t } = useI18n()

// State
const records = ref<GovernanceAlertNotificationRecordResponse[]>([])
const loading = ref(false)
const error = ref('')
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const queryParams = reactive({
  channelType: undefined as GovernanceAlertNotificationChannelType | undefined,
  notificationStatus: undefined as GovernanceAlertNotificationStatus | undefined
})

// Detail drawer
const detailDrawerVisible = ref(false)
const detailTabActive = ref('detail')
const selectedRecord = ref<GovernanceAlertNotificationRecordResponse | null>(null)

// Helper functions
function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function getChannelTypeTagType(channelType: GovernanceAlertNotificationChannelType): 'info' | 'warning' | 'success' {
  switch (channelType) {
    case 'EMAIL_DRAFT': return 'warning'
    case 'WEBHOOK_DRAFT': return 'success'
    default: return 'info'
  }
}

function getStatusTagType(status: GovernanceAlertNotificationStatus): 'info' | 'success' | 'danger' {
  switch (status) {
    case 'MANUALLY_CONFIRMED': return 'success'
    case 'CANCELLED': return 'danger'
    default: return 'info'
  }
}

function formatEvidencePayload(payload: string): string {
  try {
    const parsed = JSON.parse(payload)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return payload
  }
}

// Load records
async function loadRecords() {
  loading.value = true
  error.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: pageNum.value,
      pageSize: Math.min(pageSize.value, 200) // Enforce max 200
    }
    if (props.alertRecordId) params.alertRecordId = props.alertRecordId
    if (queryParams.channelType) params.channelType = queryParams.channelType
    if (queryParams.notificationStatus) params.notificationStatus = queryParams.notificationStatus

    const result = await listGovernanceAlertNotificationRecords(params)
    records.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    error.value = t('resourceGovernance.notification.loadFailed')
    console.error('Failed to load notification records:', err)
  } finally {
    loading.value = false
  }
}

// View detail
function viewDetail(record: GovernanceAlertNotificationRecordResponse) {
  selectedRecord.value = record
  detailTabActive.value = 'detail'
  detailDrawerVisible.value = true
}

// Public refresh method
function refresh() {
  pageNum.value = 1
  loadRecords()
}

// Watch props
watch(() => props.alertRecordId, () => {
  refresh()
})

onMounted(() => {
  loadRecords()
})

defineExpose({ refresh })
</script>

<style scoped>
.notification-records-tab {
  padding: 0;
}

.tab-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header-filters {
  display: flex;
  gap: 12px;
}

.section-loading {
  padding: 16px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.manual-only-notice {
  margin-top: var(--space-md);
  padding-top: var(--space-xs);
  border-top: var(--rule-hair) solid var(--color-rule);
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
}

.content-detail {
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
}

.evidence-content {
  background: var(--ih-bg-subtle);
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
}
</style>
