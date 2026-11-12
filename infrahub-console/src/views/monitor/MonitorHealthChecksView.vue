<template>
  <div class="health-checks-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('monitor.healthChecks.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.healthChecks.list') }}</span>
          <div>
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
        <el-select
          v-model="queryParams.targetType"
          :placeholder="t('monitor.healthChecks.targetType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HOST" value="HOST" />
          <el-option label="SERVICE" value="SERVICE" />
          <el-option label="INSTANCE" value="INSTANCE" />
        </el-select>
        <el-select
          v-model="queryParams.checkType"
          :placeholder="t('monitor.healthChecks.checkType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HTTP" value="HTTP" />
          <el-option label="TCP" value="TCP" />
          <el-option label="ICMP" value="ICMP" />
        </el-select>
        <el-select
          v-model="queryParams.enabled"
          :placeholder="t('monitor.healthChecks.enabled')"
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
        <el-table-column prop="targetType" :label="t('monitor.healthChecks.targetType')" min-width="110" align="center" />
        <el-table-column prop="targetId" :label="t('monitor.healthChecks.targetId')" min-width="110" align="center" />
        <el-table-column prop="checkType" :label="t('monitor.healthChecks.checkType')" min-width="110" align="center" />
        <el-table-column prop="checkEndpoint" :label="t('monitor.healthChecks.checkEndpoint')" min-width="360" show-overflow-tooltip />
        <el-table-column prop="expectedResult" :label="t('monitor.healthChecks.expectedResult')" min-width="130" align="center" show-overflow-tooltip />
        <el-table-column prop="timeoutSeconds" :label="t('monitor.healthChecks.timeout')" min-width="100" align="center">
          <template #default="{ row }">
            {{ row.timeoutSeconds }}s
          </template>
        </el-table-column>
        <el-table-column prop="enabled" :label="t('monitor.healthChecks.enabled')" min-width="80" align="center">
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
        <el-form-item :label="t('monitor.healthChecks.targetType')" prop="targetType">
          <el-select v-model="formData.targetType" style="width: 100%">
            <el-option label="HOST" value="HOST" />
            <el-option label="SERVICE" value="SERVICE" />
            <el-option label="INSTANCE" value="INSTANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.targetId')" prop="targetId">
          <el-input-number v-model="formData.targetId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.checkType')" prop="checkType">
          <el-select v-model="formData.checkType" style="width: 100%">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="TCP" value="TCP" />
            <el-option label="ICMP" value="ICMP" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.checkEndpoint')" prop="checkEndpoint">
          <el-input v-model="formData.checkEndpoint" placeholder="例如: http://localhost:8080/health" />
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.expectedResult')" prop="expectedResult">
          <el-input v-model="formData.expectedResult" placeholder="可选，例如: 200 或 OK" />
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.timeout')" prop="timeoutSeconds">
          <el-input-number v-model="formData.timeoutSeconds" :min="1" :max="300" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.healthChecks.enabled')" prop="enabled">
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  pageMonitorHealthChecks,
  createHealthCheck,
  updateHealthCheck,
  deleteHealthCheck,
  toggleHealthCheckEnabled,
  type MonitorHealthCheck
} from '@/api/monitor'

const { t } = useI18n()

const loading = ref(false)
const submitLoading = ref(false)
const tableData = ref<MonitorHealthCheck[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const currentId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  targetType: '',
  checkType: '',
  enabled: undefined as boolean | undefined
})

const formData = reactive({
  targetType: 'HOST',
  targetId: 1 as number,
  checkType: 'HTTP',
  checkEndpoint: '',
  expectedResult: '',
  timeoutSeconds: 30,
  enabled: true
})

const formRules: FormRules = {
  targetType: [{ required: true, message: '目标类型不能为空', trigger: 'change' }],
  targetId: [{ required: true, message: '目标ID不能为空', trigger: 'blur' }],
  checkType: [{ required: true, message: '检查类型不能为空', trigger: 'change' }],
  timeoutSeconds: [{ required: true, message: '超时时间不能为空', trigger: 'blur' }]
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageMonitorHealthChecks(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load health checks:', err)
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
  formData.targetType = 'HOST'
  formData.targetId = 1
  formData.checkType = 'HTTP'
  formData.checkEndpoint = ''
  formData.expectedResult = ''
  formData.timeoutSeconds = 30
  formData.enabled = true
}

const handleCreate = () => {
  isEdit.value = false
  currentId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: MonitorHealthCheck) => {
  isEdit.value = true
  currentId.value = row.id
  formData.targetType = row.targetType
  formData.targetId = row.targetId
  formData.checkType = row.checkType
  formData.checkEndpoint = row.checkEndpoint || ''
  formData.expectedResult = row.expectedResult || ''
  formData.timeoutSeconds = row.timeoutSeconds
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
        targetType: formData.targetType,
        targetId: formData.targetId,
        checkType: formData.checkType,
        checkEndpoint: formData.checkEndpoint || undefined,
        expectedResult: formData.expectedResult || undefined,
        timeoutSeconds: formData.timeoutSeconds,
        enabled: formData.enabled
      }
      
      if (isEdit.value && currentId.value) {
        await updateHealthCheck(currentId.value, requestData)
        ElMessage.success(t('common.updateSuccess'))
      } else {
        await createHealthCheck(requestData)
        ElMessage.success(t('common.createSuccess'))
      }
      
      dialogVisible.value = false
      loadData()
    } catch (err) {
      console.error('Failed to save health check:', err)
      ElMessage.error(isEdit.value ? t('common.updateFailed') : t('common.createFailed'))
    } finally {
      submitLoading.value = false
    }
  })
}

const handleToggleEnabled = async (row: MonitorHealthCheck) => {
  try {
    const result = await toggleHealthCheckEnabled(row.id, !row.enabled)
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

const handleDelete = async (row: MonitorHealthCheck) => {
  try {
    await ElMessageBox.confirm(
      t('common.deleteConfirm', { name: `${row.targetType}-${row.targetId}` }),
      t('common.confirm'),
      {
        type: 'warning',
        confirmButtonText: t('common.deleteConfirmButton'),
        cancelButtonText: t('common.cancel'),
      }
    )
    
    await deleteHealthCheck(row.id)
    ElMessage.success(t('common.deleteSuccess'))
    loadData()
  } catch (err: any) {
    if (err === 'cancel') return
    console.error('Failed to delete health check:', err)
    ElMessage.error(t('common.deleteFailed'))
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.health-checks-view {
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
