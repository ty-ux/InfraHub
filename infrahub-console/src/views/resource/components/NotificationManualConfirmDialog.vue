<template>
  <el-dialog
    v-model="visible"
    :title="t('resourceGovernance.notification.manualConfirmTitle')"
    width="600px"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <!-- Manual Only Notice -->
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
      {{ t('resourceGovernance.notification.manualConfirmNotice') }}
    </el-alert>

    <!-- Draft Info -->
    <el-descriptions :column="2" border size="small" class="draft-info">
      <el-descriptions-item :label="t('resourceGovernance.notification.channelType')">
        <el-tag :type="getChannelTypeTagType(draft?.channelType)" size="small">
          {{ draft?.channelType || '-' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.notification.targetUserName')">
        {{ form?.targetUserName || '-' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.notification.subject')" :span="2">
        {{ form?.subject || '-' }}
      </el-descriptions-item>
      <el-descriptions-item :label="t('resourceGovernance.notification.content')" :span="2">
        <div class="content-preview">{{ form?.content || '-' }}</div>
      </el-descriptions-item>
    </el-descriptions>

    <el-divider />

    <!-- Confirm Form -->
    <el-form :model="confirmForm" label-width="120px" :rules="confirmRules" ref="confirmFormRef">
      <el-form-item :label="t('resourceGovernance.notification.notifyReason')" prop="notifyReason">
        <el-input
          v-model="confirmForm.notifyReason"
          type="textarea"
          :rows="3"
          :placeholder="t('resourceGovernance.notification.notifyReasonPlaceholder')"
          maxlength="512"
          show-word-limit
        />
      </el-form-item>

      <el-form-item :label="t('resourceGovernance.notification.evidencePayload')">
        <el-input
          v-model="evidencePayloadInput"
          type="textarea"
          :rows="3"
          :placeholder="t('resourceGovernance.notification.evidencePayloadPlaceholder')"
        />
        <div class="evidence-hint">
          {{ t('resourceGovernance.notification.evidencePayloadHint') }}
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="handleConfirm" :loading="submitting">
        {{ t('resourceGovernance.notification.confirmReminded') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  confirmGovernanceAlertNotification,
  type GovernanceAlertNotificationDraftResponse,
  type GovernanceAlertNotificationChannelType,
  type GovernanceAlertNotificationRecordResponse
} from '@/api/asset'

interface DraftFormData {
  channelType: GovernanceAlertNotificationChannelType
  targetUserId: number
  targetUserName: string
  subject: string
  content: string
}

const props = defineProps<{
  modelValue: boolean
  draft: GovernanceAlertNotificationDraftResponse | null
  form: DraftFormData | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirmed', record: GovernanceAlertNotificationRecordResponse): void
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const submitting = ref(false)
const confirmFormRef = ref<FormInstance>()
const evidencePayloadInput = ref('')

const confirmForm = reactive({
  notifyReason: ''
})

const confirmRules: FormRules = {
  notifyReason: [{ required: true, message: t('resourceGovernance.notification.notifyReasonRequired'), trigger: 'blur' }]
}

// Helper functions
function getChannelTypeTagType(channelType: GovernanceAlertNotificationChannelType | undefined): 'info' | 'warning' | 'success' {
  switch (channelType) {
    case 'EMAIL_DRAFT': return 'warning'
    case 'WEBHOOK_DRAFT': return 'success'
    default: return 'info'
  }
}

// Parse evidence payload
function parseEvidencePayload(input: string): string | undefined {
  if (!input || !input.trim()) {
    return undefined
  }
  return input.trim()
}

// Confirm notification
async function handleConfirm() {
  if (!props.draft || !props.form) return
  if (!confirmFormRef.value) return

  try {
    await confirmFormRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const result = await confirmGovernanceAlertNotification({
      alertRecordId: props.draft.alertRecordId,
      channelType: props.form.channelType,
      targetUserId: props.form.targetUserId,
      targetUserName: props.form.targetUserName,
      subject: props.form.subject,
      content: props.form.content,
      notifyReason: confirmForm.notifyReason,
      evidencePayload: parseEvidencePayload(evidencePayloadInput.value)
    })

    ElMessage.success(t('resourceGovernance.notification.confirmSuccess'))
    emit('confirmed', result)
    handleClose()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to confirm notification:', err)
  } finally {
    submitting.value = false
  }
}

// Close dialog
function handleClose() {
  visible.value = false
  confirmForm.notifyReason = ''
  evidencePayloadInput.value = ''
}
</script>

<style scoped>
.draft-info {
  margin-bottom: 16px;
}

.content-preview {
  max-height: 100px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.evidence-hint {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-top: 4px;
}
</style>