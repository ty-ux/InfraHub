<template>
  <div class="monitor-snapshots-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('monitor.snapshots.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.snapshots.list') }}</span>
          <div>
            <el-button type="primary" size="small" @click="handleCreateManual" style="margin-right: 8px;">
              {{ t('monitor.snapshots.createManual') }}
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
          :placeholder="t('monitor.snapshots.targetType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HOST" value="HOST" />
          <el-option label="SERVICE" value="SERVICE" />
          <el-option label="INSTANCE" value="INSTANCE" />
        </el-select>
        <el-input
          v-model="queryParams.source"
          :placeholder="t('monitor.snapshots.sourcePlaceholder')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" :fit="true" table-layout="fixed" style="width: 100%">
        <el-table-column prop="id" :label="t('common.id')" min-width="72" align="center" />
        <el-table-column prop="targetType" :label="t('monitor.snapshots.targetType')" min-width="110" align="center" />
        <el-table-column prop="targetId" :label="t('monitor.snapshots.targetId')" min-width="100" align="center" />
        <el-table-column prop="snapshotTime" :label="t('monitor.snapshots.snapshotTime')" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.snapshotTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="source" :label="t('monitor.snapshots.source')" min-width="220" show-overflow-tooltip />
        <el-table-column prop="createdAt" :label="t('common.createdAt')" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" min-width="120" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showPayload(row)">
              {{ t('monitor.snapshots.viewPayload') }}
            </el-button>
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

    <el-dialog v-model="payloadDialogVisible" :title="t('monitor.snapshots.payloadDetail')" width="600px">
      <pre class="json-pre">{{ payloadContent }}</pre>
    </el-dialog>

    <!-- Create Manual Snapshot Dialog -->
    <el-dialog
      v-model="createDialogVisible"
      :title="t('monitor.snapshots.createManual')"
      width="500px"
      destroy-on-close
    >
      <el-form
        ref="createFormRef"
        :model="createFormData"
        :rules="createFormRules"
        label-width="100px"
      >
        <el-form-item :label="t('monitor.snapshots.targetType')" prop="targetType">
          <el-select v-model="createFormData.targetType" style="width: 100%">
            <el-option label="HOST" value="HOST" />
            <el-option label="SERVICE" value="SERVICE" />
            <el-option label="INSTANCE" value="INSTANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('monitor.snapshots.targetId')" prop="targetId">
          <el-input-number v-model="createFormData.targetId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('monitor.snapshots.metricPayload')" prop="metricPayload">
          <el-input
            v-model="createFormData.metricPayload"
            type="textarea"
            :rows="6"
            :placeholder="t('monitor.snapshots.metricPayloadPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('monitor.snapshots.source')" prop="source">
          <el-input v-model="createFormData.source" :placeholder="t('monitor.snapshots.sourcePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="handleCreateSubmit" :loading="createLoading">
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { pageMonitorSnapshots, createManualSnapshot, type MonitorSnapshot } from '@/api/monitor'

const { t } = useI18n()
const route = useRoute()

const loading = ref(false)
const createLoading = ref(false)
const tableData = ref<MonitorSnapshot[]>([])
const total = ref(0)
const payloadDialogVisible = ref(false)
const payloadContent = ref('')
const createDialogVisible = ref(false)
const createFormRef = ref<FormInstance>()

const createFormData = reactive({
  targetType: 'HOST',
  targetId: 1,
  metricPayload: '',
  source: ''
})

const createFormRules: FormRules = {
  targetType: [{ required: true, message: '目标类型不能为空', trigger: 'change' }],
  targetId: [{ required: true, message: '目标ID不能为空', trigger: 'blur' }],
  metricPayload: [{ required: true, message: '指标数据不能为空', trigger: 'blur' }]
}

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  snapshotId: undefined as number | undefined,
  targetType: '',
  source: ''
})

const applyRouteQuery = () => {
  const snapshotIdRaw = route.query.snapshotId
  const snapshotId = Number(Array.isArray(snapshotIdRaw) ? snapshotIdRaw[0] : snapshotIdRaw)
  queryParams.snapshotId = Number.isFinite(snapshotId) && snapshotId > 0 ? snapshotId : undefined
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageMonitorSnapshots(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load monitor snapshots:', err)
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

const showPayload = (row: MonitorSnapshot) => {
  if (row.metricPayload) {
    try {
      payloadContent.value = JSON.stringify(JSON.parse(row.metricPayload), null, 2)
    } catch {
      payloadContent.value = row.metricPayload
    }
  } else {
    payloadContent.value = '{}'
  }
  payloadDialogVisible.value = true
}

const handleCreateManual = () => {
  createFormData.targetType = 'HOST'
  createFormData.targetId = 1
  createFormData.metricPayload = ''
  createFormData.source = ''
  createDialogVisible.value = true
}

const handleCreateSubmit = async () => {
  if (!createFormRef.value) return

  await createFormRef.value.validate(async (valid) => {
    if (!valid) return

    createLoading.value = true
    try {
      await createManualSnapshot({
        targetType: createFormData.targetType,
        targetId: createFormData.targetId,
        metricPayload: createFormData.metricPayload,
        source: createFormData.source || undefined
      })
      ElMessage.success(t('common.createSuccess'))
      createDialogVisible.value = false
      loadData()
    } catch (err) {
      console.error('Failed to create manual snapshot:', err)
      ElMessage.error(t('common.createFailed'))
    } finally {
      createLoading.value = false
    }
  })
}

onMounted(() => {
  applyRouteQuery()
  loadData()
})

watch(() => route.query.snapshotId, () => {
  applyRouteQuery()
  queryParams.pageNum = 1
  loadData()
})
</script>

<style scoped>
.monitor-snapshots-view {
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

.json-pre {
  background: var(--ih-bg-subtle);
  padding: 16px;
  border-radius: 4px;
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow: auto;
}
</style>
