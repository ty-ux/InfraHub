<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('resourceGovernance.followup.confirmReceiptTitle')"
    width="480px"
    @update:model-value="$emit('update:modelValue', $event)"
    @close="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" size="small">
      <el-form-item :label="t('resourceGovernance.followup.receiptStatus')" prop="receiptStatus">
        <el-select v-model="form.receiptStatus" :placeholder="t('resourceGovernance.followup.selectConfirmReceiptStatus')">
          <el-option label="CONFIRMED" value="CONFIRMED" />
          <el-option label="FAILED" value="FAILED" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('resourceGovernance.followup.receiptNote')" prop="receiptNote">
        <el-input
          v-model="form.receiptNote"
          type="textarea"
          :rows="3"
          :placeholder="t('resourceGovernance.followup.receiptNotePlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button size="small" @click="$emit('update:modelValue', false)">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" size="small" :loading="submitting" @click="handleSubmit">
        {{ t('resourceGovernance.followup.confirmReceipt') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { confirmGovernanceAlertReceipt } from '@/api/asset'

const props = defineProps<{
  modelValue: boolean
  followupId: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirmed'): void
}>()

const { t } = useI18n()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  receiptStatus: '' as 'CONFIRMED' | 'FAILED' | '',
  receiptNote: ''
})

const rules: FormRules = {
  receiptStatus: [{ required: true, message: t('resourceGovernance.followup.receiptStatusRequired'), trigger: 'change' }]
}

function resetForm() {
  form.receiptStatus = ''
  form.receiptNote = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()

  submitting.value = true
  try {
    await confirmGovernanceAlertReceipt(props.followupId, {
      receiptStatus: form.receiptStatus as 'CONFIRMED' | 'FAILED',
      receiptNote: form.receiptNote || undefined
    })
    ElMessage.success(t('resourceGovernance.followup.confirmReceiptSuccess'))
    emit('update:modelValue', false)
    emit('confirmed')
  } catch (err) {
    ElMessage.error(t('resourceGovernance.followup.confirmReceiptFailed'))
    console.error('Failed to confirm receipt:', err)
  } finally {
    submitting.value = false
  }
}
</script>
