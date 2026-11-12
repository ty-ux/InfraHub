<template>
  <div class="reports-tab">
    <!-- Header with generate button -->
    <div class="tab-header">
      <div class="header-actions">
        <el-button type="primary" @click="handleOpenGenerateDialog">
          {{ t('resourceGovernance.reports.generateReport') }}
        </el-button>
        <el-button @click="loadReports">
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

    <!-- Reports table -->
    <el-table v-else :data="reports" stripe size="small">
      <el-table-column prop="reportNo" :label="t('resourceGovernance.reports.reportNo')" width="140" />
      <el-table-column prop="reportType" :label="t('resourceGovernance.reports.reportType')" width="80">
        <template #default="{ row }">
          {{ t(`resourceGovernance.reports.reportTypes.${row.reportType}`) }}
        </template>
      </el-table-column>
      <el-table-column prop="title" :label="t('resourceGovernance.reports.titleField')" min-width="200" show-overflow-tooltip />
      <el-table-column prop="startTime" :label="t('resourceGovernance.reports.startTime')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.startTime) }}
        </template>
      </el-table-column>
      <el-table-column prop="endTime" :label="t('resourceGovernance.reports.endTime')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.endTime) }}
        </template>
      </el-table-column>
      <el-table-column prop="generatedAt" :label="t('resourceGovernance.reports.generatedAt')" width="160">
        <template #default="{ row }">
          {{ formatDateTime(row.generatedAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleViewDetail(row)">
            {{ t('resourceGovernance.reports.viewDetail') }}
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
        @size-change="loadReports"
        @current-change="loadReports"
      />
    </div>

    <!-- Manual only notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.reports.manualOnlyNotice') }}</p>

    <!-- Generate Report Dialog -->
    <el-dialog
      v-model="generateDialogVisible"
      :title="t('resourceGovernance.reports.generateReport')"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form ref="generateFormRef" :model="generateFormData" :rules="generateFormRules" label-width="120px">
        <el-form-item :label="t('resourceGovernance.reports.reportType')" prop="reportType">
          <el-select v-model="generateFormData.reportType" :placeholder="t('resourceGovernance.reports.selectReportType')" style="width: 100%">
            <el-option :label="t('resourceGovernance.reports.reportTypes.DAILY')" value="DAILY" />
            <el-option :label="t('resourceGovernance.reports.reportTypes.WEEKLY')" value="WEEKLY" />
            <el-option :label="t('resourceGovernance.reports.reportTypes.MONTHLY')" value="MONTHLY" />
            <el-option :label="t('resourceGovernance.reports.reportTypes.CUSTOM')" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.reports.startTime')" prop="startTime">
          <el-date-picker
            v-model="generateFormData.startTime"
            type="datetime"
            :placeholder="t('resourceGovernance.reports.selectStartTime')"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.reports.endTime')" prop="endTime">
          <el-date-picker
            v-model="generateFormData.endTime"
            type="datetime"
            :placeholder="t('resourceGovernance.reports.selectEndTime')"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
        <el-form-item :label="t('resourceGovernance.reports.title')" prop="title">
          <el-input v-model="generateFormData.title" :placeholder="t('resourceGovernance.reports.titlePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="generating" @click="handleGenerate">{{ t('resourceGovernance.reports.generate') }}</el-button>
      </template>
    </el-dialog>

    <!-- Report Detail Drawer -->
    <el-drawer
      v-model="detailDrawerVisible"
      :title="t('resourceGovernance.reports.reportDetail')"
      size="60%"
    >
      <div v-if="reportDetail" class="report-detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item :label="t('resourceGovernance.reports.reportNo')">
            {{ reportDetail.reportNo }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.reports.reportType')">
            {{ t(`resourceGovernance.reports.reportTypes.${reportDetail.reportType}`) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.reports.title')" :span="2">
            {{ reportDetail.title }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.reports.startTime')">
            {{ formatDateTime(reportDetail.startTime) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.reports.endTime')">
            {{ formatDateTime(reportDetail.endTime) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('resourceGovernance.reports.generatedAt')">
            {{ formatDateTime(reportDetail.generatedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- Payload sections -->
        <div class="payload-sections">
          <el-collapse v-model="activePayloads">
            <el-collapse-item name="summaryPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.summaryPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.summaryPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="overviewPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.overviewPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.overviewPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="trendPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.trendPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.trendPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="slaPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.slaPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.slaPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="effectivenessPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.effectivenessPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.effectivenessPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="reviewItemPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.reviewItemPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.reviewItemPayload) }}</pre>
            </el-collapse-item>
            <el-collapse-item name="alertPayload">
              <template #title>
                <span class="payload-title">{{ t('resourceGovernance.reports.alertPayload') }}</span>
              </template>
              <pre class="payload-content">{{ formatPayload(reportDetail.alertPayload) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
      <div v-else class="empty-data">
        {{ t('common.noData') }}
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import dayjs from 'dayjs'
import {
  listGovernanceReports,
  getGovernanceReport,
  generateGovernanceReport,
  type GovernanceReportResponse,
  type GovernanceReportGenerateRequest,
  type GovernanceReportType
} from '@/api/asset'

const props = defineProps<{
  envId?: number
  resourcePoolId?: number
  serviceId?: number
}>()

const { t } = useI18n()

// State
const reports = ref<GovernanceReportResponse[]>([])
const loading = ref(false)
const error = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

// Generate dialog state
const generateDialogVisible = ref(false)
const generating = ref(false)
const generateFormRef = ref<FormInstance | null>(null)
const generateFormData = reactive<GovernanceReportGenerateRequest>({
  reportType: 'DAILY' as GovernanceReportType,
  startTime: '',
  endTime: '',
  title: ''
})

// Form rules
const generateFormRules: FormRules = {
  reportType: [{ required: true, message: t('common.required', { label: t('resourceGovernance.reports.reportType') }), trigger: 'change' }],
  startTime: [{ required: true, message: t('common.required', { label: t('resourceGovernance.reports.startTime') }), trigger: 'change' }],
  endTime: [{ required: true, message: t('common.required', { label: t('resourceGovernance.reports.endTime') }), trigger: 'change' }]
}

// Detail drawer state
const detailDrawerVisible = ref(false)
const reportDetail = ref<GovernanceReportResponse | null>(null)
const activePayloads = ref(['summaryPayload', 'overviewPayload', 'trendPayload', 'slaPayload', 'effectivenessPayload', 'reviewItemPayload', 'alertPayload'])

// Helper functions
function formatDateTime(dateStr: string): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function formatPayload(payload: Record<string, unknown> | undefined): string {
  if (!payload) return '{}'
  try {
    return JSON.stringify(payload, null, 2)
  } catch {
    return '{}'
  }
}

// Load reports
async function loadReports() {
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

    const result = await listGovernanceReports(params)
    reports.value = result.records || []
    total.value = result.total || 0
  } catch (err) {
    error.value = t('resourceGovernance.reports.loadFailed')
    console.error('Failed to load reports:', err)
  } finally {
    loading.value = false
  }
}

// Generate report handlers
function handleOpenGenerateDialog() {
  // Set default time range based on report type
  const now = dayjs()
  generateFormData.reportType = 'DAILY'
  generateFormData.startTime = now.subtract(1, 'day').format('YYYY-MM-DDTHH:mm:ss')
  generateFormData.endTime = now.format('YYYY-MM-DDTHH:mm:ss')
  generateFormData.title = ''
  generateDialogVisible.value = true
}

async function handleGenerate() {
  if (!generateFormRef.value) return

  try {
    await generateFormRef.value.validate()
    generating.value = true

    const data: GovernanceReportGenerateRequest = {
      reportType: generateFormData.reportType,
      startTime: generateFormData.startTime,
      endTime: generateFormData.endTime,
      title: generateFormData.title || undefined
    }
    if (props.envId) data.envId = props.envId
    if (props.resourcePoolId) data.resourcePoolId = props.resourcePoolId
    if (props.serviceId) data.serviceId = props.serviceId

    await generateGovernanceReport(data)
    ElMessage.success(t('resourceGovernance.reports.generateSuccess'))
    generateDialogVisible.value = false
    loadReports()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(t('resourceGovernance.reports.generateFailed'))
      console.error('Failed to generate report:', err)
    }
  } finally {
    generating.value = false
  }
}

// View detail handler
async function handleViewDetail(row: GovernanceReportResponse) {
  try {
    const detail = await getGovernanceReport(row.id)
    reportDetail.value = detail
    detailDrawerVisible.value = true
  } catch (err) {
    ElMessage.error(t('resourceGovernance.reports.loadDetailFailed'))
    console.error('Failed to load report detail:', err)
  }
}

// Public refresh method
function refresh() {
  pageNum.value = 1
  loadReports()
}

// Watch props changes
watch(() => [props.envId, props.resourcePoolId, props.serviceId], () => {
  refresh()
})

onMounted(() => {
  loadReports()
})

defineExpose({ refresh })
</script>

<style scoped>
.reports-tab {
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

.empty-data {
  text-align: center;
  padding: 24px;
  color: var(--ih-text-secondary);
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

.report-detail {
  padding: 16px;
}

.payload-sections {
  margin-top: 16px;
}

.payload-title {
  font-weight: 500;
}

.payload-content {
  background: var(--color-paper-3);
  padding: var(--space-sm);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow: auto;
}
</style>