<template>
  <div class="alert-rules-tab">
    <!-- Header with create button -->
    <div class="tab-header">
      <div class="header-actions">
        <el-button type="primary" @click="handleCreate">
          {{ t('common.create') }}
        </el-button>
        <el-button @click="loadAlertRules">
          {{ t('common.refresh') }}
        </el-button>
      </div>
    </div>

    <!-- Loading state -->
    <div v-if="loading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- Error state -->
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />

    <!-- Alert rules table -->
    <el-table v-else :data="alertRules" stripe size="small">
      <el-table-column prop="ruleName" :label="t('resourceGovernance.alertRules.ruleName')" min-width="150" show-overflow-tooltip />
      <el-table-column prop="metricType" :label="t('resourceGovernance.alertRules.metricType')" width="180">
        <template #default="{ row }">
          {{ t(`resourceGovernance.alertRules.metricTypes.${row.metricType}`) }}
        </template>
      </el-table-column>
      <el-table-column prop="operator" :label="t('resourceGovernance.alertRules.operator')" width="160">
        <template #default="{ row }">
          {{ t(`resourceGovernance.alertRules.operators.${row.operator}`) }}
        </template>
      </el-table-column>
      <el-table-column prop="thresholdValue" :label="t('resourceGovernance.alertRules.thresholdValue')" width="110" align="right" />
      <el-table-column prop="alertLevel" :label="t('resourceGovernance.alertRules.alertLevel')" width="110">
        <template #default="{ row }">
          <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">
            {{ t(`resourceGovernance.alertRules.alertLevels.${row.alertLevel}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="enabled" :label="t('resourceGovernance.alertRules.enabled')" width="110">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? t('common.enabled') : t('common.disabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleEdit(row)">
            {{ t('common.edit') }}
          </el-button>
          <el-button
            :type="row.enabled ? 'warning' : 'success'"
            link
            size="small"
            @click="handleToggle(row)"
          >
            {{ row.enabled ? t('common.disable') : t('common.enable') }}
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
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        size="small"
        @size-change="loadAlertRules"
        @current-change="loadAlertRules"
      />
    </div>

    <!-- Manual only notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.alertRules.manualOnlyNotice') }}</p>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('resourceGovernance.alertRules.editRule') : t('resourceGovernance.alertRules.createRule')"
      width="600px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px">
        <el-form-item :label="t('resourceGovernance.alertRules.ruleName')" prop="ruleName">
          <el-input v-model="formData.ruleName" :placeholder="t('common.pleaseEnter', { label: t('resourceGovernance.alertRules.ruleName') })" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.metricType')" prop="metricType">
          <el-select v-model="formData.metricType" :placeholder="t('resourceGovernance.alertRules.selectMetricType')" style="width: 100%">
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.CRITICAL_RISK_COUNT')" value="CRITICAL_RISK_COUNT" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.WARNING_RISK_COUNT')" value="WARNING_RISK_COUNT" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.OPEN_SUGGESTION_COUNT')" value="OPEN_SUGGESTION_COUNT" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.FAILED_EXECUTION_COUNT')" value="FAILED_EXECUTION_COUNT" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.SLA_HIT_RATE')" value="SLA_HIT_RATE" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.OVERDUE_REVIEW_ITEM_COUNT')" value="OVERDUE_REVIEW_ITEM_COUNT" />
            <el-option :label="t('resourceGovernance.alertRules.metricTypes.IMPACT_RECORDED_COUNT')" value="IMPACT_RECORDED_COUNT" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.operator')" prop="operator">
          <el-select v-model="formData.operator" :placeholder="t('resourceGovernance.alertRules.selectOperator')" style="width: 100%">
            <el-option :label="t('resourceGovernance.alertRules.operators.GT')" value="GT" />
            <el-option :label="t('resourceGovernance.alertRules.operators.GTE')" value="GTE" />
            <el-option :label="t('resourceGovernance.alertRules.operators.LT')" value="LT" />
            <el-option :label="t('resourceGovernance.alertRules.operators.LTE')" value="LTE" />
            <el-option :label="t('resourceGovernance.alertRules.operators.EQ')" value="EQ" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.thresholdValue')" prop="thresholdValue">
          <el-input-number v-model="formData.thresholdValue" :precision="2" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.alertLevel')" prop="alertLevel">
          <el-select v-model="formData.alertLevel" :placeholder="t('resourceGovernance.alertRules.selectAlertLevel')" style="width: 100%">
            <el-option :label="t('resourceGovernance.alertRules.alertLevels.INFO')" value="INFO" />
            <el-option :label="t('resourceGovernance.alertRules.alertLevels.WARNING')" value="WARNING" />
            <el-option :label="t('resourceGovernance.alertRules.alertLevels.CRITICAL')" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.enabled')" prop="enabled">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.alertRules.description')" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="2" :placeholder="t('common.pleaseEnter', { label: t('resourceGovernance.alertRules.description') })" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  listGovernanceAlertRules,
  createGovernanceAlertRule,
  updateGovernanceAlertRule,
  toggleGovernanceAlertRule,
  type GovernanceAlertRuleResponse,
  type GovernanceAlertRuleRequest,
  type GovernanceOpsMetricType,
  type AlertOperator,
  type GovernanceAlertLevel
} from '@/api/asset'

const props = defineProps<{
  envId?: number
  resourcePoolId?: number
  serviceId?: number
}>()

const { t } = useI18n()

// State
const alertRules = ref<GovernanceAlertRuleResponse[]>([])
const loading = ref(false)
const error = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

// Dialog state
const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance | null>(null)

// Form data
const formData = reactive<GovernanceAlertRuleRequest>({
  ruleName: '',
  metricType: 'CRITICAL_RISK_COUNT' as GovernanceOpsMetricType,
  operator: 'GT' as AlertOperator,
  thresholdValue: 0,
  alertLevel: 'WARNING' as GovernanceAlertLevel,
  enabled: true,
  description: ''
})

// Form rules
const formRules: FormRules = {
  ruleName: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.ruleName') }), trigger: 'blur' }],
  metricType: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.metricType') }), trigger: 'change' }],
  operator: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.operator') }), trigger: 'change' }],
  thresholdValue: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.thresholdValue') }), trigger: 'blur' }],
  alertLevel: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.alertLevel') }), trigger: 'change' }],
  enabled: [{ required: true, message: t('common.required', { label: t('resourceGovernance.alertRules.enabled') }), trigger: 'change' }]
}

// Helper functions
function getAlertLevelTagType(level: string): 'info' | 'warning' | 'danger' {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    default: return 'info'
  }
}

// Load alert rules
async function loadAlertRules() {
  loading.value = true
  error.value = ''
  try {
    const params: Record<string, unknown> = {
      pageNum: pageNum.value,
      pageSize: pageSize.value
    }
    if (props.envId) params.envId = props.envId
    if (props.resourcePoolId) params.resourcePoolId = props.resourcePoolId
    if (props.serviceId) params.serviceId = props.serviceId

    const result = await listGovernanceAlertRules(params)
    alertRules.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    error.value = t('resourceGovernance.alertRules.loadFailed')
    console.error('Failed to load alert rules:', err)
  } finally {
    loading.value = false
  }
}

// Create/Edit handlers
function handleCreate() {
  isEdit.value = false
  editingId.value = null
  Object.assign(formData, {
    ruleName: '',
    metricType: 'CRITICAL_RISK_COUNT',
    operator: 'GT',
    thresholdValue: 0,
    alertLevel: 'WARNING',
    enabled: true,
    description: ''
  })
  dialogVisible.value = true
}

function handleEdit(row: GovernanceAlertRuleResponse) {
  isEdit.value = true
  editingId.value = row.id
  Object.assign(formData, {
    ruleName: row.ruleName,
    metricType: row.metricType,
    operator: row.operator,
    thresholdValue: row.thresholdValue,
    alertLevel: row.alertLevel,
    enabled: row.enabled,
    description: row.description || ''
  })
  dialogVisible.value = true
}

async function handleToggle(row: GovernanceAlertRuleResponse) {
  try {
    await toggleGovernanceAlertRule(row.id, !row.enabled)
    ElMessage.success(row.enabled ? t('common.disableSuccess') : t('common.enableSuccess'))
    loadAlertRules()
  } catch (err) {
    ElMessage.error(t('common.operationFailed'))
    console.error('Failed to toggle alert rule:', err)
  }
}

async function handleSubmit() {
  if (!formRef.value) return

  try {
    await formRef.value.validate()
    submitting.value = true

    if (isEdit.value && editingId.value) {
      await updateGovernanceAlertRule(editingId.value, formData)
      ElMessage.success(t('common.updateSuccess'))
    } else {
      await createGovernanceAlertRule(formData)
      ElMessage.success(t('common.createSuccess'))
    }

    dialogVisible.value = false
    loadAlertRules()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(isEdit.value ? t('common.updateFailed') : t('common.createFailed'))
      console.error('Failed to submit alert rule:', err)
    }
  } finally {
    submitting.value = false
  }
}

// Public refresh method
function refresh() {
  pageNum.value = 1
  loadAlertRules()
}

// Watch props changes
watch(() => [props.envId, props.resourcePoolId, props.serviceId], () => {
  refresh()
})

onMounted(() => {
  loadAlertRules()
})

defineExpose({ refresh })
</script>

<style scoped>
.alert-rules-tab {
  padding: 0;
}

.tab-header {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.section-loading {
  padding: 16px;
  background: var(--ih-bg-content);
  border-radius: 4px;
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
