<template>
  <div class="alert-events-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('monitor.alertEvents.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('monitor.alertEvents.list') }}</span>
          <el-button size="small" @click="handleRefresh">
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-select
          v-model="queryParams.targetType"
          :placeholder="t('monitor.alertEvents.targetType')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="HOST" value="HOST" />
          <el-option label="SERVICE" value="SERVICE" />
          <el-option label="INSTANCE" value="INSTANCE" />
        </el-select>
        <el-select
          v-model="queryParams.alertStatus"
          :placeholder="t('monitor.alertEvents.alertStatus')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option label="FIRING" value="FIRING" />
          <el-option label="RESOLVED" value="RESOLVED" />
          <el-option label="ACKNOWLEDGED" value="ACKNOWLEDGED" />
        </el-select>
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <el-table :data="tableData" :fit="true" table-layout="fixed" style="width: 100%">
        <el-table-column prop="id" :label="t('common.id')" min-width="72" align="center" />
        <el-table-column prop="ruleId" :label="t('monitor.alertEvents.ruleId')" min-width="100" align="center" />
        <el-table-column prop="targetType" :label="t('monitor.alertEvents.targetType')" min-width="110" align="center" />
        <el-table-column prop="targetId" :label="t('monitor.alertEvents.targetId')" min-width="100" align="center" />
        <el-table-column prop="alertStatus" :label="t('monitor.alertEvents.alertStatus')" min-width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.alertStatus)" effect="dark">
              {{ row.alertStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="alertValue" :label="t('monitor.alertEvents.alertValue')" min-width="100" align="center" />
        <el-table-column prop="summary" :label="t('monitor.alertEvents.summary')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="firedAt" :label="t('monitor.alertEvents.firedAt')" min-width="170">
          <template #default="{ row }">
            {{ formatDate(row.firedAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="recoveredAt" :label="t('monitor.alertEvents.recoveredAt')" min-width="170">
          <template #default="{ row }">
            {{ formatDate(row.recoveredAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="handleNote" :label="t('monitor.alertEvents.handleNote')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.handleNote || '-' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" min-width="120" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.alertStatus === 'FIRING'"
              type="primary"
              size="small"
              @click="openResolveDialog(row)"
            >
              {{ t('monitor.alertEvents.resolve') }}
            </el-button>
            <span v-else class="text-muted">-</span>
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

    <!-- Resolve Dialog -->
    <el-dialog
      v-model="resolveDialogVisible"
      :title="t('monitor.alertEvents.resolveTitle')"
      width="400px"
    >
      <el-form :model="resolveForm" label-width="80px">
        <el-form-item :label="t('monitor.alertEvents.handleNote')">
          <el-input
            v-model="resolveForm.handleNote"
            type="textarea"
            :rows="3"
            :placeholder="t('monitor.alertEvents.handleNotePlaceholder')"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialogVisible = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button type="primary" :loading="resolveLoading" @click="handleResolve">
          {{ t('monitor.alertEvents.confirmResolve') }}
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
import { pageMonitorAlertEvents, resolveAlertEvent, type MonitorAlertEvent } from '@/api/monitor'

const { t } = useI18n()
const route = useRoute()

const loading = ref(false)
const tableData = ref<MonitorAlertEvent[]>([])
const total = ref(0)

// Resolve dialog state
const resolveDialogVisible = ref(false)
const resolveLoading = ref(false)
const resolveForm = reactive({
  eventId: 0,
  handleNote: ''
})

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  eventId: undefined as number | undefined,
  targetType: '',
  alertStatus: '',
  ruleId: undefined as number | undefined
})

const applyRouteQuery = () => {
  const eventIdRaw = route.query.eventId
  const eventId = Number(Array.isArray(eventIdRaw) ? eventIdRaw[0] : eventIdRaw)
  queryParams.eventId = Number.isFinite(eventId) && eventId > 0 ? eventId : undefined
}

const formatDate = (dateStr: string) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getStatusType = (status: string) => {
  switch (status) {
    case 'FIRING': return 'danger'
    case 'RESOLVED': return 'success'
    case 'ACKNOWLEDGED': return 'warning'
    default: return 'info'
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const response = await pageMonitorAlertEvents(queryParams)
    tableData.value = response.records
    total.value = response.total
  } catch (err) {
    console.error('Failed to load alert events:', err)
    ElMessage.error(t('common.loadFailed'))
  } finally {
    loading.value = false
  }
}

const openResolveDialog = (row: MonitorAlertEvent) => {
  resolveForm.eventId = row.id
  resolveForm.handleNote = ''
  resolveDialogVisible.value = true
}

const handleResolve = async () => {
  resolveLoading.value = true
  try {
    await resolveAlertEvent(resolveForm.eventId, { handleNote: resolveForm.handleNote || undefined })
    ElMessage.success(t('monitor.alertEvents.resolveSuccess'))
    resolveDialogVisible.value = false
    loadData()
  } catch (err) {
    console.error('Failed to resolve alert event:', err)
    ElMessage.error(t('monitor.alertEvents.resolveFailed'))
  } finally {
    resolveLoading.value = false
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

onMounted(() => {
  applyRouteQuery()
  loadData()
})

watch(() => route.query.eventId, () => {
  applyRouteQuery()
  queryParams.pageNum = 1
  loadData()
})
</script>

<style scoped>
.alert-events-view {
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
</style>
