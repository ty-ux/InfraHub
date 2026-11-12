<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('resourceGovernance.followup.createTitle')"
    width="520px"
    @update:model-value="$emit('update:modelValue', $event)"
    @close="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" size="small">
      <el-form-item :label="t('resourceGovernance.followup.followUpType')" prop="followUpType">
        <el-select v-model="form.followUpType" :placeholder="t('resourceGovernance.followup.selectFollowUpType')">
          <el-option :label="t('resourceGovernance.followup.types.FOLLOW_UP')" value="FOLLOW_UP" />
          <el-option :label="t('resourceGovernance.followup.types.REPLY_RECEIVED')" value="REPLY_RECEIVED" />
          <el-option :label="t('resourceGovernance.followup.types.CALL_MADE')" value="CALL_MADE" />
          <el-option :label="t('resourceGovernance.followup.types.VISIT_MADE')" value="VISIT_MADE" />
          <el-option :label="t('resourceGovernance.followup.types.OTHER')" value="OTHER" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('resourceGovernance.followup.followUpContent')" prop="followUpContent">
        <el-input
          v-model="form.followUpContent"
          type="textarea"
          :rows="3"
          :placeholder="t('resourceGovernance.followup.followUpContentPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('resourceGovernance.followup.followUpResult')" prop="followUpResult">
        <el-input
          v-model="form.followUpResult"
          type="textarea"
          :rows="2"
          :placeholder="t('resourceGovernance.followup.followUpResultPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('resourceGovernance.followup.receiptStatus')" prop="receiptStatus">
        <el-select v-model="form.receiptStatus" :placeholder="t('resourceGovernance.followup.selectReceiptStatus')" clearable>
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.PENDING')" value="PENDING" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.CONFIRMED')" value="CONFIRMED" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.FAILED')" value="FAILED" />
          <el-option :label="t('resourceGovernance.followup.receiptStatuses.NO_RESPONSE')" value="NO_RESPONSE" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('resourceGovernance.followup.evidencePayload')" prop="evidencePayload">
        <el-input
          v-model="form.evidencePayload"
          type="textarea"
          :rows="2"
          :placeholder="t('resourceGovernance.followup.evidencePayloadPlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="$emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" size="small" :loading="submitting" @click="handleSubmit">
        {{ t('resourceGovernance.followup.createFollowup') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  createGovernanceAlertNotificationFollowup,
  type GovernanceAlertNotificationFollowupType,
  type GovernanceAlertReceiptStatus
} from '@/api/asset'

const props = defineProps<{
  modelValue: boolean
  notificationRecordId: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'created'): void
}>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  followUpType: '' as GovernanceAlertNotificationFollowupType | '',
  followUpContent: '',
  followUpResult: '',
  receiptStatus: undefined as GovernanceAlertReceiptStatus | undefined,
  evidencePayload: ''
})

const rules: FormRules = {
  followUpType: [{ required: true, message: t('resourceGovernance.followup.followUpTypeRequired'), trigger: 'change' }],
  followUpContent: [{ required: true, message: t('resourceGovernance.followup.followUpContentRequired'), trigger: 'blur' }]
}

function resetForm() {
  form.followUpType = ''
  form.followUpContent = ''
  form.followUpResult = ''
  form.receiptStatus = undefined
  form.evidencePayload = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()

  submitting.value = true
  try {
    await createGovernanceAlertNotificationFollowup({
      notificationRecordId: props.notificationRecordId,
      followUpType: form.followUpType as GovernanceAlertNotificationFollowupType,
      followUpContent: form.followUpContent,
      followUpResult: form.followUpResult || undefined,
      receiptStatus: form.receiptStatus || undefined,
      evidencePayload: form.evidencePayload || undefined
    })
    ElMessage.success(t('resourceGovernance.followup.createSuccess'))
    emit('update:modelValue', false)
    emit('created')
  } catch (err) {
    ElMessage.error(t('resourceGovernance.followup.createFailed'))
    console.error('Failed to create followup:', err)
  } finally {
    submitting.value = false
  }
}
</script>
