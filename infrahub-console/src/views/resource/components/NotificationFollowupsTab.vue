<template>
  <div class="notification-followups-tab">
    <!-- Header -->
    <div class="tab-header">
      <div class="header-filters">
        <el-select
          v-model="queryParams.followUpType"
          :placeholder="t('resourceGovernance.followup.followUpType')"
          clearable
          size="small"
          style="width: 140px"
          @change="loadRecords"
        >
          <el-option :label="t('resourceGovernance.followup.types.FOLLOW_UP')" value="FOLLOW_UP" />
          <el-option :label="t('resourceGovernance.followup.types.REPLY_RECEIVED')" value="REPLY_RECEIVED" />
          <el-option :label="t('resourceGovernance.followup.types.CALL_MADE')" value="CALL_MADE" />
          <el-option :label="t('resourceGovernance.followup.types.VISIT_MADE')" value="VISIT_MADE" />
          <el-option :label="t('resourceGovernance.followup.types.OTHER')" value="OTHER" />
        </el-select>
        <el-select
          v-model="queryParams.followUpStatus"
          :placeholder="t('resourceGovernance.followup.followUpStatus')"
          clearable
          size="small"
          style="width: 140px"
          @change="loadRecords"
        >
          <el-option :label="t('resourceGovernance.followup.statuses.IN_PROGRESS')" value="IN_PROGRESS" />
          <el-option :label="t('resourceGovernance.followup.statuses.COMPLETED')" value="COMPLETED" />
          <el-option :label="t('resourceGovernance.followup.statuses.CANCELLED')" value="CANCELLED" />
        </el-select>
        <el-select
          v-model="queryParams.receiptStatus"
          :placeholder="t('resourceGovernance.followup.receiptStatus')"
          clearable
          size="small"
          style="width: 140px"
          @change="loadRecords"
        >
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.PENDING')" value="PENDING" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.CONFIRMED')" value="CONFIRMED" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.FAILED')" value="FAILED" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.NO_RESPONSE')" value="NO_RESPONSE" />
        </el-select>
      </div>
      <div class="header-actions">
        <el-button type="primary" size="small" @click="openCreateDialog">
          {{ t('resourceGovernance.followup.createFollowup') }}
        </el-button>
        <el-button size="small" @click="loadRecords">{{ t('common.refresh') }}</el-button>
      </div>
    </div>

    <!-- Loading state -->
    <div v-if="loading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- Error state -->
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />

    <!-- Records table -->
    <el-table v-else :data="records" stripe size="small">
      <el-table-column prop="followUpNo" :label="t('resourceGovernance.followup.followUpNo')" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="viewDetail(row)">
            {{ row.followUpNo }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="notificationNo" :label="t('resourceGovernance.followup.notificationNo')" min-width="160" show-overflow-tooltip />
      <el-table-column prop="alertNo" :label="t('resourceGovernance.followup.alertNo')" min-width="160" show-overflow-tooltip />
      <el-table-column prop="followUpType" :label="t('resourceGovernance.followup.followUpType')" width="140" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ t(`resourceGovernance.followup.types.${row.followUpType}`) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="followUpStatus" :label="t('resourceGovernance.followup.followUpStatus')" width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="getFollowUpStatusTagType(row.followUpStatus)" size="small">
            {{ t(`resourceGovernance.followup.statuses.${row.followUpStatus}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="receiptStatus" :label="t('resourceGovernance.followup.receiptStatus')" width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="getReceiptStatusTagType(row.receiptStatus)" size="small">
            {{ t(`resourceGovernance.followup.receiptStatuses.${row.receiptStatus}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdByName" :label="t('resourceGovernance.followup.createdBy')" min-width="140" show-overflow-tooltip />
      <el-table-column prop="createdAt" :label="t('resourceGovernance.followup.createdAt')" min-width="180">
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
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
    <p class="manual-only-notice">{{ t('resourceGovernance.followup.manualOnlyNotice') }}</p>

    <!-- Create Followup Dialog -->
    <FollowupCreateDialog
      v-if="createDialogVisible"
      v-model="createDialogVisible"
      :notification-record-id="notificationRecordId"
      @created="onFollowupCreated"
    />

    <!-- Detail Drawer -->
    <FollowupDetailDrawer
      v-if="detailDrawerVisible"
      v-model="detailDrawerVisible"
      :followup="selectedRecord"
      @refresh="loadRecords"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import {
  listGovernanceAlertNotificationFollowups,
  listFollowupsByNotification,
  type GovernanceAlertNotificationFollowupResponse,
  type GovernanceAlertNotificationFollowupType,
  type GovernanceAlertNotificationFollowupStatus,
  type GovernanceAlertReceiptStatus
} from '@/api/asset'
import FollowupCreateDialog from './FollowupCreateDialog.vue'
import FollowupDetailDrawer from './FollowupDetailDrawer.vue'

const props = defineProps<{
  notificationRecordId?: number
  alertRecordId?: number
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
}>()

const { t } = useI18n()

const records = ref<GovernanceAlertNotificationFollowupResponse[]>([])
const loading = ref(false)
const error = ref('')
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const queryParams = reactive({
  followUpType: undefined as GovernanceAlertNotificationFollowupType | undefined,
  followUpStatus: undefined as GovernanceAlertNotificationFollowupStatus | undefined,
  receiptStatus: undefined as GovernanceAlertReceiptStatus | undefined
})

const createDialogVisible = ref(false)
const detailDrawerVisible = ref(false)
const selectedRecord = ref<GovernanceAlertNotificationFollowupResponse | null>(null)

function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function getFollowUpStatusTagType(status: GovernanceAlertNotificationFollowupStatus): '' | 'success' | 'info' {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'CANCELLED': return 'info'
    default: return ''
  }
}

function getReceiptStatusTagType(status: GovernanceAlertReceiptStatus): 'warning' | 'success' | 'danger' | 'info' {
  switch (status) {
    case 'PENDING': return 'warning'
    case 'CONFIRMED': return 'success'
    case 'FAILED': return 'danger'
    case 'NO_RESPONSE': return 'info'
    default: return 'info'
  }
}

async function loadRecords() {
  loading.value = true
  error.value = ''
  try {
    if (props.notificationRecordId) {
      const result = await listFollowupsByNotification(props.notificationRecordId, {
        pageNum: pageNum.value,
        pageSize: Math.min(pageSize.value, 200)
      })
      records.value = result.records || []
      total.value = result.total || 0
    } else {
      const params: Record<string, unknown> = {
        pageNum: pageNum.value,
        pageSize: Math.min(pageSize.value, 200)
      }
      if (props.alertRecordId) params.alertRecordId = props.alertRecordId
      if (queryParams.followUpType) params.followUpType = queryParams.followUpType
      if (queryParams.followUpStatus) params.followUpStatus = queryParams.followUpStatus
      if (queryParams.receiptStatus) params.receiptStatus = queryParams.receiptStatus
      const result = await listGovernanceAlertNotificationFollowups(params)
      records.value = result.records || []
      total.value = result.total || 0
    }
  } catch (err) {
    error.value = t('resourceGovernance.followup.loadFailed')
    console.error('Failed to load followup records:', err)
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createDialogVisible.value = true
}

function onFollowupCreated() {
  pageNum.value = 1
  loadRecords()
  emit('refresh')
}

function viewDetail(record: GovernanceAlertNotificationFollowupResponse) {
  selectedRecord.value = record
  detailDrawerVisible.value = true
}

function refresh() {
  pageNum.value = 1
  loadRecords()
}

watch(() => props.notificationRecordId, () => { refresh() })
watch(() => props.alertRecordId, () => { refresh() })

onMounted(() => { loadRecords() })

defineExpose({ refresh })
</script>

<style scoped>
.notification-followups-tab {
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

.header-actions {
  display: flex;
  gap: 8px;
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
</style>
