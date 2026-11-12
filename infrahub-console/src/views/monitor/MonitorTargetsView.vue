<template>
  <div class="monitor-targets-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('monitor.targets.title') }}</h2>
    </div>

    <!-- Schedule Collection Status Card -->
    <el-card class="schedule-status-card" v-loading="scheduleStatusLoading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.targets.scheduleStatus') }}</span>
          <el-button size="small" @click="loadScheduleStatus">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>
      <div class="status-content" v-if="scheduleStatus">
        <div class="status-item">
          <span class="status-label">{{ t('monitor.targets.enabled') }}:</span>
          <el-tag :type="scheduleStatus.enabled ? 'success' : 'info'" effect="dark">
            {{ scheduleStatus.enabled ? t('common.enabled') : t('common.disabled') }}
          </el-tag>
        </div>
        <div class="status-item">
          <span class="status-label">{{ t('monitor.targets.period') }}:</span>
          <span>{{ scheduleStatus.fixedDelayMs / 1000 }}s</span>
        </div>
        <div class="status-item">
          <span class="status-label">{{ t('monitor.targets.lastRun') }}:</span>
          <span>{{ formatDate(scheduleStatus.lastRunAt) }}</span>
        </div>
        <div class="status-item" v-if="scheduleStatus.lastRunAt">
          <span class="status-label">{{ t('monitor.targets.lastResult') }}:</span>
          <el-tag type="success" v-if="scheduleStatus.lastFailureCount === 0">{{ scheduleStatus.lastSuccessCount }}</el-tag>
          <el-tag type="danger" v-else>{{ scheduleStatus.lastFailureCount }} {{ t('monitor.targets.failures') }}</el-tag>
          <span class="status-message">{{ scheduleStatus.lastMessage }}</span>
        </div>
      </div>
      <div v-else class="status-empty">
        {{ t('monitor.targets.noStatus') }}
      </div>
    </el-card>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.targets.list') }}</span>
          <div class="header-actions">
            <el-button type="primary" size="small" @click="handleRunOnce" :loading="runOnceLoading">
              {{ t('monitor.targets.runOnceCollection') }}
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
          :placeholder="t('monitor.targets.searchPlaceholder')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.targetType"
          :placeholder="t('monitor.targets.targetType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HOST" value="HOST" />
          <el-option label="SERVICE" value="SERVICE" />
          <el-option label="INSTANCE" value="INSTANCE" />
        </el-select>
        <el-select
          v-model="queryParams.status"
          :placeholder="t('monitor.targets.status')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option :label="t('common.enabled')" value="ENABLED" />
          <el-option :label="t('common.disabled')" value="DISABLED" />
        </el-select>
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" :fit="true" table-layout="fixed" style="width: 100%">
        <el-table-column prop="id" :label="t('common.id')" min-width="72" align="center" />
        <el-table-column prop="targetType" :label="t('monitor.targets.targetType')" min-width="110" align="center" />
        <el-table-column prop="targetId" :label="t('monitor.targets.targetId')" min-width="110" align="center" />
        <el-table-column prop="monitorKey" :label="t('monitor.targets.monitorKey')" min-width="360" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('monitor.targets.status')" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" effect="dark">
              {{ row.status === 'ENABLED' ? t('common.enabled') : t('common.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('common.createdAt')" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" min-width="220" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" size="small" @click="showConfig(row)">
                {{ t('monitor.targets.viewConfig') }}
              </el-button>
              <el-button
                link
                type="success"
                size="small"
                @click="handleCollect(row)"
                :loading="collectLoading[row.id]"
              >
                {{ t('monitor.targets.collectSnapshot') }}
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

    <el-dialog v-model="configDialogVisible" :title="t('monitor.targets.configDetail')" width="600px">
      <pre class="json-pre">{{ configContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { pageMonitorTargets, collectMonitorTarget, runOnceMonitorCollection, getScheduleCollectionStatus, type MonitorMetricTarget, type MonitorCollectionScheduleStatus } from '@/api/monitor'

const { t } = useI18n()

const loading = ref(false)
const tableData = ref<MonitorMetricTarget[]>([])
const total = ref(0)
const configDialogVisible = ref(false)
const configContent = ref('')
const collectLoading = reactive<Record<number, boolean>>({})
const runOnceLoading = ref(false)
const scheduleStatus = ref<MonitorCollectionScheduleStatus | null>(null)
const scheduleStatusLoading = ref(false)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  targetType: '',
  status: ''
})

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageMonitorTargets(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load monitor targets:', err)
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

const loadScheduleStatus = async () => {
  scheduleStatusLoading.value = true
  try {
    const status = await getScheduleCollectionStatus()
    scheduleStatus.value = status
  } catch (err) {
    console.error('Failed to load schedule status:', err)
  } finally {
    scheduleStatusLoading.value = false
  }
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

const showConfig = (row: MonitorMetricTarget) => {
  if (row.scrapeConfig) {
    try {
      configContent.value = JSON.stringify(JSON.parse(row.scrapeConfig), null, 2)
    } catch {
      configContent.value = row.scrapeConfig
    }
  } else {
    configContent.value = '{}'
  }
  configDialogVisible.value = true
}

const handleCollect = async (row: MonitorMetricTarget) => {
  collectLoading[row.id] = true
  try {
    const response = await collectMonitorTarget(row.id)
    if (response.success) {
      ElMessage.success(t('monitor.targets.collectSuccess'))
    } else {
      ElMessage.warning(response.message)
    }
  } catch (err) {
    console.error('Failed to collect metrics:', err)
    ElMessage.error(t('monitor.targets.collectFailed'))
  } finally {
    collectLoading[row.id] = false
  }
}

const handleRunOnce = async () => {
  runOnceLoading.value = true
  try {
    const response = await runOnceMonitorCollection()
    const { totalCount, successCount, failureCount } = response
    ElMessage.success(t('monitor.targets.runOnceResult', { total: totalCount, success: successCount, failure: failureCount }))
    if (totalCount > 0) {
      loadData()
    }
  } catch (err) {
    console.error('Failed to run once collection:', err)
    ElMessage.error(t('monitor.targets.runOnceFailed'))
  } finally {
    runOnceLoading.value = false
  }
}

onMounted(() => {
  loadData()
  loadScheduleStatus()
})
</script>

<style scoped>
.monitor-targets-view {
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

.schedule-status-card {
  margin-bottom: var(--space-md);
}

.status-content {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-label {
  font-weight: 500;
  color: var(--ih-text-secondary);
}

.status-message {
  color: var(--ih-text-secondary);
  font-size: 13px;
}

.status-empty {
  color: var(--ih-text-secondary);
  text-align: center;
  padding: 20px;
}

.table-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 8px;
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
