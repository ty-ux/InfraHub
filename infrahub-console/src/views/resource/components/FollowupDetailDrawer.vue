<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('resourceGovernance.followup.detailTitle')"
    size="520px"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-descriptions v-if="followup" :column="1" border size="small">
      <el-descriptions-item :label="t('resourceGovernance.followup.followUpNo')">
        {{ followup.followUpNo }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.notificationNo')">
        {{ followup.notificationNo }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.alertNo')">
        {{ followup.alertNo }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.followUpType')">
        <el-tag size="small">{{ t(`resourceGovernance.followup.types.${followup.followUpType}`) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.followUpContent')">
        <div class="content-detail">{{ followup.followUpContent }}</div>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.followUpResult')">
        {{ followup.followUpResult || '-' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.followUpStatus')">
        <el-tag :type="getFollowUpStatusTagType(followup.followUpStatus)" size="small">
          {{ t(`resourceGovernance.followup.statuses.${followup.followUpStatus}`) }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.receiptStatus')">
        <el-tag :type="getReceiptStatusTagType(followup.receiptStatus)" size="small">
          {{ t(`resourceGovernance.followup.receiptStatuses.${followup.receiptStatus}`) }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.receiptNote')">
        {{ followup.receiptNote || '-' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.evidencePayload')">
        <pre v-if="followup.evidencePayload" class="evidence-content">{{ formatEvidencePayload(followup.evidencePayload) }}</pre>
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.createdBy')">
        {{ followup.createdByName }} (ID: {{ followup.createdBy }})
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.followup.createdAt')">
        {{ formatDateTime(followup.createdAt) }}
      </el-descriptions-item>
      <el-descriptions-item v-if="followup.confirmedBy" :label="t('resourceGovernance.followup.confirmedBy')">
        {{ followup.confirmedByName }} (ID: {{ followup.confirmedBy }})
      </el-descriptions-item>
      <el-descriptions-item v-if="followup.confirmedAt" :label="t('resourceGovernance.followup.confirmedAt')">
        {{ formatDateTime(followup.confirmedAt) }}
      </el-descriptions-item>
    </el-descriptions>

    <!-- Receipt Confirm Button -->
    <div v-if="followup && followup.receiptStatus === 'PENDING'" class="drawer-footer">
      <el-button type="primary" size="small" @click="openReceiptConfirmDialog">
        {{ t('resourceGovernance.followup.confirmReceipt') }}
      </el-button>
    </div>

    <!-- Receipt Confirm Dialog -->
    <ReceiptConfirmDialog
      v-if="receiptConfirmVisible"
      v-model="receiptConfirmVisible"
      :followup-id="followup?.id || 0"
      @confirmed="onReceiptConfirmed"
    />
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import type {
  GovernanceAlertNotificationFollowupResponse,
  GovernanceAlertNotificationFollowupStatus,
  GovernanceAlertReceiptStatus
} from '@/api/asset'
import ReceiptConfirmDialog from './ReceiptConfirmDialog.vue'

const props = defineProps<{
  modelValue: boolean
  followup: GovernanceAlertNotificationFollowupResponse | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'refresh'): void
}>()

const { t } = useI18n()
const receiptConfirmVisible = ref(false)

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

function formatEvidencePayload(payload: string): string {
  try {
    const parsed = JSON.parse(payload)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return payload
  }
}

function openReceiptConfirmDialog() {
  receiptConfirmVisible.value = true
}

function onReceiptConfirmed() {
  emit('refresh')
}
</script>

<style scoped>
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

.drawer-footer {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--ih-border);
}
</style>