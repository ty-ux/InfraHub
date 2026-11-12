<template>
  <div class="alert-rules-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('monitor.alertRules.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.alertRules.list') }}</span>
          <div>
            <el-button size="small" @click="handleEvaluate" style="margin-right: 8px;">
              {{ t('monitor.alertRules.evaluate') }}
            </el-button>
            <el-button type="primary" size="small" @click="handleCreate" style="margin-right: 8px;">
              {{ t('common.create') }}
            </el-button>
            <el-button size="small" @click="handleRefresh">
              {{ t('common.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="filter-bar">
        <el-input
          v-model="queryParams.keyword"
          :placeholder="t('monitor.alertRules.searchPlaceholder')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.targetType"
          :placeholder="t('monitor.alertRules.targetType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HOST" value="HOST" />
          <el-option label="SERVICE" value="SERVICE" />
          <el-option label="INSTANCE" value="INSTANCE" />
        </el-select>
        <el-select
          v-model="queryParams.level"
          :placeholder="t('monitor.alertRules.level')"
          clearable
          style="width: 100px"
          @change="handleSearch"
        >
          <el-option label="CRITICAL" value="CRITICAL" />
          <el-option label="WARNING" value="WARNING" />
          <el-option label="INFO" value="INFO" />
        </el-select>
        <el-select
          v-model="queryParams.enabled"
          :placeholder="t('monitor.alertRules.enabled')"
          clearable
          style="width: 100px"
          @change="handleSearch"
        >
          <el-option :label="t('common.yes')" :value="true" />
          <el-option :label="t('common.no')" :value="false" />
        </el-select>
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" :fit="true" table-layout="fixed" style="width: 100%">
        <el-table-column prop="id" :label="t('common.id')" min-width="72" align="center" />
        <el-table-column prop="ruleName" :label="t('monitor.alertRules.ruleName')" min-width="260" show-overflow-tooltip />
        <el-table-column prop="targetType" :label="t('monitor.alertRules.targetType')" min-width="110" align="center" />
        <el-table-column prop="metricName" :label="t('monitor.alertRules.metricName')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('monitor.alertRules.condition')" min-width="120" align="center">
          <template #default="{ row }">
            {{ row.compareOp }} {{ row.thresholdValue }}
          </template>
        </el-table-column>
        <el-table-column prop="durationSeconds" :label="t('monitor.alertRules.duration')" min-width="100" align="center">
          <template #default="{ row }">
            {{ row.durationSeconds }}s
          </template>
        </el-table-column>
        <el-table-column prop="level" :label="t('monitor.alertRules.level')" min-width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getLevelType(row.level)" effect="dark">
              {{ row.level }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" :label="t('monitor.alertRules.enabled')" min-width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="dark">
              {{ row.enabled ? t('common.yes') : t('common.no') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('common.createdAt')" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" min-width="190" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" size="small" @click="handleEdit(row)">
                {{ t('common.edit') }}
              </el-button>
              <el-button
                link
                :type="row.enabled ? 'warning' : 'success'"
                size="small"
                @click="handleToggleEnabled(row)"
              >
                {{ row.enabled ? t('common.disable') : t('common.enable') }}
              </el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">
                {{ t('common.delete') }}
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('common.edit') : t('common.create')"
      width="600px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
      >
        <el-form-item :label="t('monitor.alertRules.ruleName')" prop="ruleName">
          <el-input v-model="formData.ruleName" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.targetType')" prop="targetType">
          <el-select v-model="formData.targetType" style="width: 100%">
            <el-option label="HOST" value="HOST" />
            <el-option label="SERVICE" value="SERVICE" />
            <el-option label="INSTANCE" value="INSTANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.targetId')" prop="targetId">
          <el-input-number v-model="formData.targetId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.targetGroupCode')" prop="targetGroupCode">
          <el-input v-model="formData.targetGroupCode" placeholder="可选" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.metricName')" prop="metricName">
          <el-input v-model="formData.metricName" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.compareOp')" prop="compareOp">
          <el-select v-model="formData.compareOp" style="width: 100%">
            <el-option label=">" value="GT" />
            <el-option label="<" value="LT" />
            <el-option label="=" value="EQ" />
            <el-option label=">=" value="GE" />
            <el-option label="<=" value="LE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.thresholdValue')" prop="thresholdValue">
          <el-input-number v-model="formData.thresholdValue" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.duration')" prop="durationSeconds">
          <el-input-number v-model="formData.durationSeconds" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.level')" prop="level">
          <el-select v-model="formData.level" style="width: 100%">
            <el-option label="CRITICAL" value="CRITICAL" />
            <el-option label="WARNING" value="WARNING" />
            <el-option label="INFO" value="INFO" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.notifyChannel')" prop="notifyChannel">
          <el-input v-model="formData.notifyChannel" placeholder="Webhook URL，可选" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.enabled')" prop="enabled">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitLoading">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Evaluate Rules Dialog -->
    <el-dialog
      v-model="evaluateDialogVisible"
      :title="t('monitor.alertRules.evaluate')"
      width="500px"
      destroy-on-close
    >
      <el-form
        ref="evaluateFormRef"
        :model="evaluateFormData"
        :rules="evaluateFormRules"
        label-width="100px"
      >
        <el-form-item :label="t('monitor.alertRules.targetType')" prop="targetType">
          <el-select v-model="evaluateFormData.targetType" style="width: 100%">
            <el-option label="HOST" value="HOST" />
            <el-option label="SERVICE" value="SERVICE" />
            <el-option label="INSTANCE" value="INSTANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.targetId')" prop="targetId">
          <el-input-number v-model="evaluateFormData.targetId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.alertRules.metricPayload')" prop="metricPayload">
          <el-input
            v-model="evaluateFormData.metricPayload"
            type="textarea"
            :rows="6"
            :placeholder="t('monitor.alertRules.metricPayloadPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evaluateDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleEvaluateSubmit" :loading="evaluateLoading">
          {{ t('monitor.alertRules.evaluate') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Evaluation Result Dialog -->
    <el-dialog
      v-model="resultDialogVisible"
      :title="t('monitor.alertRules.evaluationResult')"
      width="600px"
    >
      <el-descriptions :column="1" border>
        <el-descriptions-item :label="t('monitor.alertRules.targetType')">
          {{ evaluationResult?.targetType }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('monitor.alertRules.targetId')">
          {{ evaluationResult?.targetId }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('monitor.alertRules.hitCount')">
          <el-tag :type="evaluationResult?.hitCount > 0 ? 'danger' : 'success'">
            {{ evaluationResult?.hitCount }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('monitor.alertRules.recoveredCount')">
          <el-tag :type="evaluationResult?.recoveredCount > 0 ? 'success' : 'info'">
            {{ evaluationResult?.recoveredCount }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="evaluationResult?.hitRules?.length" style="margin-top: 16px">
        <h4>{{ t('monitor.alertRules.hitRules') }}</h4>
        <el-table :data="evaluationResult.hitRules" size="small">
          <el-table-column prop="ruleName" :label="t('monitor.alertRules.ruleName')" />
          <el-table-column prop="metricName" :label="t('monitor.alertRules.metricName')" />
          <el-table-column :label="t('monitor.alertRules.condition')">
            <template #default="{ row }">
              {{ row.compareOp }} {{ row.thresholdValue }} (actual: {{ row.actualValue }})
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-if="evaluationResult?.recoveredEventIds?.length" style="margin-top: 16px">
        <h4>{{ t('monitor.alertRules.recoveredEvents') }}</h4>
        <el-tag type="success" effect="plain">
          {{ t('monitor.alertRules.recoveredEventsHint', { count: evaluationResult.recoveredEventIds.length }) }}
        </el-tag>
      </div>
      <template #footer>
        <el-button type="primary" @click="resultDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  pageMonitorAlertRules,
  createAlertRule,
  updateAlertRule,
  deleteAlertRule,
  toggleAlertRuleEnabled,
  evaluateRules,
  type MonitorAlertRule,
  type MonitorEvaluationResult
} from '@/api/monitor'

const { t } = useI18n()

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<MonitorAlertRule[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const evaluateDialogVisible = ref(false)
const evaluateLoading = ref(false)
const evaluateFormRef = ref<FormInstance>()
const resultDialogVisible = ref(false)
const evaluationResult = ref<MonitorEvaluationResult | null>(null)

const evaluateFormData = reactive({
  targetType: 'HOST',
  targetId: 1,
  metricPayload: ''
})

const evaluateFormRules: FormRules = {
  targetType: [{ required: true, message: '目标类型不能为空', trigger: 'change' }],
  targetId: [{ required: true, message: '目标ID不能为空', trigger: 'blur' }],
  metricPayload: [{ required: true, message: '指标数据不能为空', trigger: 'blur' }]
}

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  targetType: '',
  level: '',
  enabled: undefined as boolean | undefined
})

const formData = reactive({
  ruleName: '',
  targetType: 'HOST',
  targetId: undefined as number | undefined,
  targetGroupCode: '',
  metricName: '',
  compareOp: 'GT',
  thresholdValue: 0,
  durationSeconds: 60,
  level: 'WARNING',
  notifyChannel: '',
  enabled: true
})

const formRules: FormRules = {
  ruleName: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
  targetType: [{ required: true, message: '目标类型不能为空', trigger: 'change' }],
  metricName: [{ required: true, message: '指标名称不能为空', trigger: 'blur' }],
  compareOp: [{ required: true, message: '比较操作符不能为空', trigger: 'change' }],
  thresholdValue: [{ required: true, message: '阈值不能为空', trigger: 'blur' }],
  durationSeconds: [{ required: true, message: '持续时间不能为空', trigger: 'blur' }],
  level: [{ required: true, message: '告警等级不能为空', trigger: 'change' }]
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getLevelType = (level: string) => {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    case 'INFO': return 'info'
    default: return 'info'
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageMonitorAlertRules(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load alert rules:', err)
    ElMessage.error(t('common.loadFailed'))
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  loadData()
}

const handleRefresh = () => {
  loadData()
}

const handleSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  loadData()
}

const handleCurrentChange = (page: number) => {
  queryParams.pageNum = page
  loadData()
}

const resetForm = () => {
  formData.ruleName = ''
  formData.targetType = 'HOST'
  formData.targetId = undefined
  formData.targetGroupCode = ''
  formData.metricName = ''
  formData.compareOp = 'GT'
  formData.thresholdValue = 0
  formData.durationSeconds = 60
  formData.level = 'WARNING'
  formData.notifyChannel = ''
  formData.enabled = true
}

const handleCreate = () => {
  isEdit.value = false
  currentId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: MonitorAlertRule) => {
  isEdit.value = true
  currentId.value = row.id
  formData.ruleName = row.ruleName
  formData.targetType = row.targetType
  formData.targetId = row.targetId || undefined
  formData.targetGroupCode = row.targetGroupCode || ''
  formData.metricName = row.metricName
  formData.compareOp = row.compareOp
  formData.thresholdValue = row.thresholdValue
  formData.durationSeconds = row.durationSeconds
  formData.level = row.level
  formData.notifyChannel = row.notifyChannel || ''
  formData.enabled = row.enabled
  dialogVisible.value = true
}

const handleSubmit = async () => {
  if (!formRef.value) return
  
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    
    submitLoading.value = true
    try {
      const requestData = {
        ruleName: formData.ruleName,
        targetType: formData.targetType,
        targetId: formData.targetId,
        targetGroupCode: formData.targetGroupCode || undefined,
        metricName: formData.metricName,
        compareOp: formData.compareOp,
        thresholdValue: formData.thresholdValue,
        durationSeconds: formData.durationSeconds,
        level: formData.level,
        notifyChannel: formData.notifyChannel || undefined,
        enabled: formData.enabled
      }
      
      if (isEdit.value && currentId.value) {
        await updateAlertRule(currentId.value, requestData)
        ElMessage.success(t('common.updateSuccess'))
      } else {
        await createAlertRule(requestData)
        ElMessage.success(t('common.createSuccess'))
      }
      
      dialogVisible.value = false
      loadData()
    } catch (err) {
      console.error('Failed to save alert rule:', err)
      ElMessage.error(isEdit.value ? t('common.updateFailed') : t('common.createFailed'))
    } finally {
      submitLoading.value = false
    }
  })
}

const handleToggleEnabled = async (row: MonitorAlertRule) => {
  try {
    const result = await toggleAlertRuleEnabled(row.id, !row.enabled)
    // Check if approval was created (approvalId returned)
    if (result && result.approvalId) {
      ElMessage.info(t('approval.messages.approvalSubmitted'))
    } else {
      // Direct execution
      ElMessage.success(row.enabled ? t('common.disableSuccess') : t('common.enableSuccess'))
      loadData()
    }
  } catch (err: any) {
    console.error('Failed to toggle enabled:', err)
    ElMessage.error(t('common.operationFailed'))
  }
}

const handleDelete = async (row: MonitorAlertRule) => {
  try {
    await ElMessageBox.confirm(
      t('common.deleteConfirm', { name: row.ruleName }),
      t('common.confirm'),
      {
        type: 'warning',
        confirmButtonText: t('common.deleteConfirmButton'),
        cancelButtonText: t('common.cancel'),
      }
    )

    await deleteAlertRule(row.id)
    ElMessage.success(t('common.deleteSuccess'))
    loadData()
  } catch (err: any) {
    if (err === 'cancel') return
    console.error('Failed to delete alert rule:', err)
    ElMessage.error(t('common.deleteFailed'))
  }
}

const handleEvaluate = () => {
  evaluateFormData.targetType = 'HOST'
  evaluateFormData.targetId = 1
  evaluateFormData.metricPayload = ''
  evaluateDialogVisible.value = true
}

const handleEvaluateSubmit = async () => {
  if (!evaluateFormRef.value) return

  await evaluateFormRef.value.validate(async (valid) => {
    if (!valid) return

    evaluateLoading.value = true
    try {
      const result = await evaluateRules({
        targetType: evaluateFormData.targetType,
        targetId: evaluateFormData.targetId,
        metricPayload: evaluateFormData.metricPayload
      })
      evaluationResult.value = result
      evaluateDialogVisible.value = false
      resultDialogVisible.value = true
      if (result.hitCount > 0) {
        ElMessage.success(t('monitor.alertRules.evaluateSuccessWithHits', { count: result.hitCount }))
      } else {
        ElMessage.success(t('monitor.alertRules.evaluateSuccessNoHits'))
      }
    } catch (err) {
      console.error('Failed to evaluate rules:', err)
      ElMessage.error(t('common.operationFailed'))
    } finally {
      evaluateLoading.value = false
    }
  })
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.alert-rules-view {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
  min-width: 0;
  overflow-wrap: break-word;
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.table-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--color-ink);
}

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.table-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  white-space: nowrap;

  .el-button {
    margin-left: 0;
  }
}
</style>
