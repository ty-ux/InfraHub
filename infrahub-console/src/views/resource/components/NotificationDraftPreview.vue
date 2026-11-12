<template>
  <el-dialog
    v-model="visible"
    :title="t('resourceGovernance.notification.draftPreviewTitle')"
    width="600px"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <!-- Manual Only Notice -->
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      {{ t('resourceGovernance.notification.draftPreviewNotice') }}
    </el-alert>

    <!-- Loading state -->
    <div v-if="loading" class="loading-state">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- Error state -->
    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon />

    <!-- Draft content -->
    <template v-else-if="draft">
      <!-- Alert Info -->
      <el-descriptions :column="2" border size="small" class="alert-info">
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.alertNo')">
          {{ alertRecord?.alertNo || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.alertLevel')">
          <el-tag :type="getAlertLevelTagType(draft.alertLevel)" size="small">
            {{ t(`resourceGovernance.alertRules.alertLevels.${draft.alertLevel}`) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertRecords.alertStatus')">
          <el-tag :type="getAlertStatusTagType(draft.alertStatus)" size="small">
            {{ t(`resourceGovernance.alertRecords.alertStatuses.${draft.alertStatus}`) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('resourceGovernance.alertAssignment.assigneeName')">
          {{ draft.assignedToName || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-divider />

      <!-- Notification Form -->
      <el-form :model="form" label-width="100px" :rules="formRules" ref="formRef">
        <el-form-item :label="t('resourceGovernance.notification.channelType')" prop="channelType">
          <el-select v-model="form.channelType" :placeholder="t('resourceGovernance.notification.selectChannelType')" style="width: 100%" @change="handleChannelChange">
            <el-option label="INTERNAL" value="INTERNAL" />
            <el-option label="EMAIL_DRAFT" value="EMAIL_DRAFT" />
            <el-option label="WEBHOOK_DRAFT" value="WEBHOOK_DRAFT" />
          </el-select>
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.targetUserId')" prop="targetUserId">
          <el-input-number v-model="form.targetUserId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.targetUserName')" prop="targetUserName">
          <el-input v-model="form.targetUserName" :placeholder="t('resourceGovernance.notification.targetUserNamePlaceholder')" />
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.subject')" prop="subject">
          <el-input v-model="form.subject" :placeholder="t('resourceGovernance.notification.subjectPlaceholder')" maxlength="512" show-word-limit />
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.content')" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            :placeholder="t('resourceGovernance.notification.contentPlaceholder')"
            maxlength="4000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.suggestedAction')">
          <span class="suggested-action">{{ draft.suggestedAction || '-' }}</span>
        </el-form-item>

        <el-form-item :label="t('resourceGovernance.notification.generatedAt')">
          {{ formatDateTime(draft.generatedAt) }}
        </el-form-item>
      </el-form>
    </template>

    <template #footer>
      <el-button @click="handleClose">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="handleOpenConfirm" :loading="loading" :disabled="!draft">
        {{ t('resourceGovernance.notification.generateDraft') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FormInstance, FormRules } from 'element-plus'
import dayjs from 'dayjs'
import {
  getGovernanceAlertNotificationDraft,
  type GovernanceAlertNotificationDraftResponse,
  type GovernanceAlertNotificationChannelType,
  type GovernanceAlertRecordResponse
} from '@/api/asset'

const props = defineProps<{
  modelValue: boolean
  alertRecord: GovernanceAlertRecordResponse | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'open-confirm', data: { draft: GovernanceAlertNotificationDraftResponse; form: typeof form }): void
}>()

const { t } = useI18n()

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const loading = ref(false)
const error = ref('')
const draft = ref<GovernanceAlertNotificationDraftResponse | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  channelType: 'INTERNAL' as GovernanceAlertNotificationChannelType,
  targetUserId: 0,
  targetUserName: '',
  subject: '',
  content: ''
})

const formRules: FormRules = {
  channelType: [{ required: true, message: t('resourceGovernance.notification.channelTypeRequired'), trigger: 'change' }],
  targetUserId: [{ required: true, message: t('resourceGovernance.notification.targetUserIdRequired'), trigger: 'blur' }],
  targetUserName: [{ required: true, message: t('resourceGovernance.notification.targetUserNameRequired'), trigger: 'blur' }],
  subject: [{ required: true, message: t('resourceGovernance.notification.subjectRequired'), trigger: 'blur' }],
  content: [{ required: true, message: t('resourceGovernance.notification.contentRequired'), trigger: 'blur' }]
}

// Helper functions
function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function getAlertLevelTagType(level: string): 'info' | 'warning' | 'danger' {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    default: return 'info'
  }
}

function getAlertStatusTagType(status: string): 'info' | 'warning' | 'success' {
  switch (status) {
    case 'OPEN': return 'warning'
    case 'ACKED': return 'info'
    case 'RESOLVED': return 'success'
    default: return 'info'
  }
}

// Load draft
async function loadDraft() {
  if (!props.alertRecord) return

  loading.value = true
  error.value = ''
  try {
    const result = await getGovernanceAlertNotificationDraft({
      alertRecordId: props.alertRecord.id,
      channelType: form.channelType
    })
    draft.value = result

    // Populate form with draft values
    form.subject = result.subject || ''
    form.content = result.content || ''
    if (result.targetUserId) form.targetUserId = result.targetUserId
    if (result.targetUserName) form.targetUserName = result.targetUserName
  } catch (err) {
    error.value = t('resourceGovernance.notification.loadDraftFailed')
    console.error('Failed to load notification draft:', err)
  } finally {
    loading.value = false
  }
}

// Handle channel change - reload draft
function handleChannelChange() {
  if (props.alertRecord) {
    loadDraft()
  }
}

// Open confirm dialog
async function handleOpenConfirm() {
  if (!formRef.value || !draft.value) return

  try {
    await formRef.value.validate()
    emit('open-confirm', { draft: draft.value, form: { ...form } })
  } catch {
    // Validation failed
  }
}

// Close dialog
function handleClose() {
  visible.value = false
  draft.value = null
  error.value = ''
}

// Watch for dialog open
watch(visible, (val) => {
  if (val && props.alertRecord) {
    loadDraft()
  }
})
</script>

<style scoped>
.loading-state {
  padding: 16px;
}

.alert-info {
  margin-bottom: 16px;
}

.suggested-action {
  color: var(--ih-text-secondary);
  font-size: 13px;
}
</style>
