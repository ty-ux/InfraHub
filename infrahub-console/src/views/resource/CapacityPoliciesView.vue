<template>
  <div class="capacity-policies-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('capacityPolicies.title') }}</h2>
    </div>

    <el-card class="filter-card">
      <div class="filter-bar">
        <el-select
          v-model="queryParams.envId"
          :placeholder="t('capacityPolicies.filter.env')"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="env in envOptions"
            :key="env.id"
            :label="env.envName"
            :value="env.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.resourcePoolId"
          :placeholder="t('capacityPolicies.filter.resourcePool')"
          clearable
          style="width: 180px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="pool in poolOptions"
            :key="pool.id"
            :label="getPoolLabel(pool)"
            :value="pool.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.metricType"
          :placeholder="t('capacityPolicies.filter.metricType')"
          clearable
          style="width: 150px"
          @change="handleFilterChange"
        >
          <el-option
            v-for="type in metricTypeOptions"
            :key="type.value"
            :label="type.label"
            :value="type.value"
          />
        </el-select>
        <el-select
          v-model="queryParams.enabled"
          :placeholder="t('capacityPolicies.filter.status')"
          clearable
          style="width: 120px"
          @change="handleFilterChange"
        >
          <el-option :label="t('common.enabled')" :value="true" />
          <el-option :label="t('common.disabled')" :value="false" />
        </el-select>
        <el-button @click="handleRefresh">
          {{ t('common.refresh') }}
        </el-button>
        <el-button type="primary" @click="handleCreate">
          {{ t('common.create') }}
        </el-button>
      </div>
    </el-card>

    <el-card class="table-card" v-loading="loading">
      <el-table :data="tableData" style="width: 100%">
        <el-table-column prop="policyName" :label="t('capacityPolicies.policyName')" min-width="150" />
        <el-table-column prop="metricType" :label="t('capacityPolicies.metricType')" width="120" />
        <el-table-column prop="warningThreshold" :label="t('capacityPolicies.warningThreshold')" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="warning">{{ row.warningThreshold }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="criticalThreshold" :label="t('capacityPolicies.criticalThreshold')" width="120" align="center">
          <template #default="{ row }">
            <el-tag type="danger">{{ row.criticalThreshold }}%</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('capacityPolicies.scope')" min-width="180">
          <template #default="{ row }">
            <div v-if="row.envId" class="scope-item">
              <el-tag size="small" type="info">{{ t('capacityPolicies.env') }}</el-tag>
              <span class="scope-value">{{ getEnvName(row.envId) }}</span>
            </div>
            <div v-if="row.resourcePoolId" class="scope-item">
              <el-tag size="small" type="info">{{ t('capacityPolicies.resourcePool') }}</el-tag>
              <span class="scope-value">{{ getPoolName(row.resourcePoolId) }}</span>
            </div>
            <span v-if="!row.envId && !row.resourcePoolId" class="scope-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" :label="t('capacityPolicies.status')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="dark">
              {{ row.enabled ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('common.createdAt')" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleView(row)">
              {{ t('common.view') }}
            </el-button>
            <el-button link type="primary" size="small" @click="handleEdit(row)">
              {{ t('common.edit') }}
            </el-button>
            <el-switch
              v-model="row.enabled"
              size="small"
              :active-text="t('common.enabled')"
              :inactive-text="t('common.disabled')"
              @change="(val: boolean) => handleToggle(row, val)"
              style="margin-left: 8px;"
            />
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
      :title="isEdit ? t('capacityPolicies.editPolicy') : t('capacityPolicies.createPolicy')"
      width="600px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="140px"
        class="policy-form"
      >
        <el-form-item :label="t('capacityPolicies.policyName')" prop="policyName">
          <el-input v-model="formData.policyName" :placeholder="t('capacityPolicies.placeholder.policyName')" />
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.metricType')" prop="metricType">
          <el-select v-model="formData.metricType" :placeholder="t('capacityPolicies.placeholder.metricType')" style="width: 100%">
            <el-option
              v-for="type in metricTypeOptions"
              :key="type.value"
              :label="type.label"
              :value="type.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.warningThreshold')" prop="warningThreshold">
          <el-input-number
            v-model="formData.warningThreshold"
            :min="0"
            :max="100"
            :precision="0"
            :placeholder="t('capacityPolicies.placeholder.threshold')"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.criticalThreshold')" prop="criticalThreshold">
          <el-input-number
            v-model="formData.criticalThreshold"
            :min="0"
            :max="100"
            :precision="0"
            :placeholder="t('capacityPolicies.placeholder.threshold')"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.env')" prop="envId">
          <el-select v-model="formData.envId" clearable :placeholder="t('capacityPolicies.placeholder.env')" style="width: 100%">
            <el-option
              v-for="env in envOptions"
              :key="env.id"
              :label="env.envName"
              :value="env.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.resourcePool')" prop="resourcePoolId">
          <el-select v-model="formData.resourcePoolId" clearable :placeholder="t('capacityPolicies.placeholder.resourcePool')" style="width: 100%">
            <el-option
              v-for="pool in poolOptions"
              :key="pool.id"
              :label="getPoolLabel(pool)"
              :value="pool.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.enabled')" prop="enabled">
          <el-switch v-model="formData.enabled" />
        </el-form-item>
        <el-form-item :label="t('capacityPolicies.description')" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            :placeholder="t('capacityPolicies.placeholder.description')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleSubmit">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- View Detail Dialog -->
    <el-dialog v-model="viewDialogVisible" :title="t('capacityPolicies.policyDetail')" width="600px">
      <el-descriptions :column="2" border v-if="currentRow">
        <el-descriptions-item :label="t('capacityPolicies.policyName')" :span="2">{{ currentRow.policyName }}</el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.metricType')">{{ currentRow.metricType }}</el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.status')">
          <el-tag :type="currentRow.enabled ? 'success' : 'info'" effect="dark">
            {{ currentRow.enabled ? t('common.enabled') : t('common.disabled') }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.warningThreshold')">
          <el-tag type="warning">{{ currentRow.warningThreshold }}%</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.criticalThreshold')">
          <el-tag type="danger">{{ currentRow.criticalThreshold }}%</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.env')" v-if="currentRow.envId">
          {{ getEnvName(currentRow.envId) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.resourcePool')" v-if="currentRow.resourcePoolId">
          {{ getPoolName(currentRow.resourcePoolId) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('capacityPolicies.description')" :span="2">
          {{ currentRow.description || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('common.createdAt')">
          {{ formatDate(currentRow.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('common.updatedAt')">
          {{ formatDate(currentRow.updatedAt) }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="viewDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  listCapacityPolicies,
  getCapacityPolicy,
  createCapacityPolicy,
  updateCapacityPolicy,
  toggleCapacityPolicy,
  type CapacityPolicy,
  type CapacityPolicyPayload
} from '@/api/asset'
import { envApi } from '@/api/resource'
import { pageResourcePools, type ResourcePool } from '@/api/asset'

interface Env {
  id: number;
  envCode: string;
  envName: string;
}

const { t } = useI18n()

// Table data
const loading = ref(false)
const tableData = ref<CapacityPolicy[]>([])
const total = ref(0)

// Query params
const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  envId: undefined as number | undefined,
  resourcePoolId: undefined as number | undefined,
  metricType: '',
  enabled: undefined as boolean | undefined
})

// Lookup data
const envOptions = ref<Env[]>([])
const poolOptions = ref<ResourcePool[]>([])

// Metric type options
const metricTypeOptions = computed(() => [
  { label: 'CPU', value: 'CPU' },
  { label: 'MEMORY', value: 'MEMORY' },
  { label: 'DISK', value: 'DISK' },
  { label: 'HOST_OFFLINE', value: 'HOST_OFFLINE' },
  { label: 'UNKNOWN_HOST', value: 'UNKNOWN_HOST' }
])

// Dialog
const dialogVisible = ref(false)
const viewDialogVisible = ref(false)
const isEdit = ref(false)
const currentRow = ref<CapacityPolicy | null>(null)
const formRef = ref<FormInstance>()

const formData = reactive<CapacityPolicyPayload>({
  policyName: '',
  metricType: 'CPU',
  warningThreshold: 60,
  criticalThreshold: 80,
  envId: undefined,
  resourcePoolId: undefined,
  enabled: true,
  description: ''
})

// Form validation
const validateThresholds = (rule: unknown, value: number, callback: (error?: Error) => void) => {
  if (formData.warningThreshold >= formData.criticalThreshold) {
    callback(new Error(t('capacityPolicies.validation.warningLessThanCritical')))
  } else {
    callback()
  }
}

const validateScope = (rule: unknown, value: unknown, callback: (error?: Error) => void) => {
  if (!formData.envId && !formData.resourcePoolId) {
    callback(new Error(t('capacityPolicies.validation.scopeRequired')))
  } else {
    callback()
  }
}

const formRules: FormRules = {
  policyName: [
    { required: true, message: () => t('capacityPolicies.validation.policyNameRequired'), trigger: 'blur' }
  ],
  metricType: [
    { required: true, message: () => t('capacityPolicies.validation.metricTypeRequired'), trigger: 'change' }
  ],
  warningThreshold: [
    { required: true, message: () => t('capacityPolicies.validation.warningThresholdRequired'), trigger: 'blur' },
    { type: 'number', min: 0, max: 100, message: () => t('capacityPolicies.validation.thresholdRange'), trigger: 'blur' }
  ],
  criticalThreshold: [
    { required: true, message: () => t('capacityPolicies.validation.criticalThresholdRequired'), trigger: 'blur' },
    { type: 'number', min: 0, max: 100, message: () => t('capacityPolicies.validation.thresholdRange'), trigger: 'blur' },
    { validator: validateThresholds, trigger: 'blur' }
  ],
  envId: [
    { validator: validateScope, trigger: 'change' }
  ],
  resourcePoolId: [
    { validator: validateScope, trigger: 'change' }
  ]
}

// Helper functions
const formatDate = (dateStr?: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getEnvName = (envId?: number) => {
  if (!envId) return '-'
  const env = envOptions.value.find(e => e.id === envId)
  return env?.envName || String(envId)
}

const getPoolName = (poolId?: number) => {
  if (!poolId) return '-'
  const pool = poolOptions.value.find(p => p.id === poolId)
  return pool?.poolName || String(poolId)
}

const getPoolLabel = (pool: ResourcePool) => {
  const envName = pool.envId ? getEnvName(pool.envId) : null
  if (envName && envName !== '-') {
    return `${pool.poolName} (${envName})`
  }
  return pool.poolName
}

// Data loading
const loadData = async () => {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      pageNum: queryParams.pageNum,
      pageSize: queryParams.pageSize
    }
    if (queryParams.envId !== undefined) params.envId = queryParams.envId
    if (queryParams.resourcePoolId !== undefined) params.resourcePoolId = queryParams.resourcePoolId
    if (queryParams.metricType) params.metricType = queryParams.metricType
    if (queryParams.enabled !== undefined) params.enabled = queryParams.enabled

    const response = await listCapacityPolicies(params)
    tableData.value = response.records || []
    total.value = response.total || 0
  } catch (err) {
    console.error('Failed to load capacity policies:', err)
    ElMessage.error(t('common.loadFailed'))
  } finally {
    loading.value = false
  }
}

const loadLookupData = async () => {
  try {
    const [envRes, poolRes] = await Promise.all([
      envApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Env[] }>,
      pageResourcePools({ pageNum: 1, pageSize: 200 })
    ])
    envOptions.value = envRes.records || []
    poolOptions.value = poolRes.records || []
  } catch (err) {
    console.error('Failed to load lookup data:', err)
  }
}

// Event handlers
const handleFilterChange = () => {
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
  formData.policyName = ''
  formData.metricType = 'CPU'
  formData.warningThreshold = 60
  formData.criticalThreshold = 80
  formData.envId = undefined
  formData.resourcePoolId = undefined
  formData.enabled = true
  formData.description = ''
}

const handleCreate = () => {
  isEdit.value = false
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: CapacityPolicy) => {
  isEdit.value = true
  currentRow.value = row
  try {
    const detail = await getCapacityPolicy(row.id)
    formData.policyName = detail.policyName
    formData.metricType = detail.metricType
    formData.warningThreshold = detail.warningThreshold
    formData.criticalThreshold = detail.criticalThreshold
    formData.envId = detail.envId
    formData.resourcePoolId = detail.resourcePoolId
    formData.enabled = detail.enabled
    formData.description = detail.description || ''
    dialogVisible.value = true
  } catch (err) {
    console.error('Failed to get policy detail:', err)
    ElMessage.error(t('common.loadFailed'))
  }
}

const handleView = (row: CapacityPolicy) => {
  currentRow.value = row
  viewDialogVisible.value = true
}

const handleToggle = async (row: CapacityPolicy, newVal: boolean) => {
  const originalVal = !newVal
  try {
    await toggleCapacityPolicy(row.id, newVal)
    ElMessage.success(newVal ? t('capacityPolicies.enableSuccess') : t('capacityPolicies.disableSuccess'))
  } catch (err) {
    // Rollback UI state on failure
    row.enabled = originalVal
    console.error('Failed to toggle policy:', err)
    ElMessage.error(t('capacityPolicies.toggleFailed'))
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return

    try {
      const payload: CapacityPolicyPayload = {
        policyName: formData.policyName,
        metricType: formData.metricType,
        warningThreshold: formData.warningThreshold,
        criticalThreshold: formData.criticalThreshold,
        envId: formData.envId,
        resourcePoolId: formData.resourcePoolId,
        enabled: formData.enabled,
        description: formData.description
      }

      if (isEdit.value && currentRow.value) {
        await updateCapacityPolicy(currentRow.value.id, payload)
        ElMessage.success(t('capacityPolicies.updateSuccess'))
      } else {
        await createCapacityPolicy(payload)
        ElMessage.success(t('capacityPolicies.createSuccess'))
      }

      dialogVisible.value = false
      loadData()
    } catch (err) {
      console.error('Failed to save policy:', err)
      ElMessage.error(isEdit.value ? t('capacityPolicies.updateFailed') : t('capacityPolicies.createFailed'))
    }
  })
}

onMounted(() => {
  loadLookupData()
  loadData()
})
</script>

<style scoped>
.capacity-policies-view {
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

.filter-card {
  margin-bottom: var(--space-md);
}

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  flex-wrap: wrap;
  align-items: center;
}

.table-card {
  margin-bottom: var(--space-md);
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.scope-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.scope-item:last-child {
  margin-bottom: 0;
}

.scope-value {
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.scope-empty {
  color: var(--ih-text-secondary);
}

.policy-form {
  padding: var(--space-xs) var(--space-lg);
}
</style>
