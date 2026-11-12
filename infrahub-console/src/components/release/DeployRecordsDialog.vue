<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('deployRecords.title')"
    class="deploy-records-dialog"
    width="min(1200px, 94vw)"
    top="4vh"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- 读取失败：明确表达失败原因，不与“没有记录”混淆 -->
    <div v-if="error" class="read-error-block" role="alert">
      <el-icon class="read-error-icon"><WarningFilled /></el-icon>
      <div class="read-error-main">
        <div class="read-error-title">{{ t('deployRecords.readFailed') }}</div>
        <div class="read-error-text">{{ error }}</div>
        <div class="read-error-hint">
          {{ t('deployRecords.readFailedHint') }}
        </div>
      </div>
      <el-button size="small" :loading="loading" @click="emit('retry')">{{ t('deployRecords.retryReadOnly') }}</el-button>
    </div>

    <!-- T-C: Summary bar per Contract 8.7 —— 总览与批次共用同一状态判据 -->
    <div v-if="hasRecords" class="deploy-summary-bar">
      <el-tag :type="summary.tagType" size="large">{{ summary.headline }}</el-tag>
      <span class="summary-detail">{{ t('deployRecords.summaryDetail', { total: summary.total, batchCount: batchGroups.length }) }}</span>
      <span v-if="summary.total > 1" class="summary-breakdown">{{ summary.breakdownText }}</span>
      <el-button v-if="summary.failed > 0" size="small" type="primary" text @click="copyFailedLogs">
        <el-icon><DocumentCopy /></el-icon>
        {{ t('deployRecords.copyFailedLogs') }}
      </el-button>
    </div>

    <!-- T-C: Grouped by batch per Contract 2.7 -->
    <div v-if="hasRecords" class="deploy-groups">
      <el-collapse v-model="expandedBatches" class="batch-collapse">
        <el-collapse-item
          v-for="batch in batchGroups"
          :key="batch.batchNo"
          :name="batch.batchNo"
        >
          <template #title>
            <div class="batch-header">
              <el-tag :type="batch.summary.tagType" size="small">
                {{ batch.summary.headline }}
              </el-tag>
              <span class="batch-name">{{ t('deployRecords.batchName', { batchNo: batch.batchNo }) }}</span>
              <span class="batch-summary">{{ t('deployRecords.batchHostCount', { count: batch.records.length }) }}</span>
              <span
                v-if="batch.records.length > 1"
                class="batch-breakdown"
              >{{ batch.summary.breakdownText }}</span>
              <span v-if="batch.startedAt" class="batch-time">
                {{ formatTime(batch.startedAt) }}
              </span>
            </div>
          </template>

          <el-table
            :data="batch.records"
            highlight-current-row
            :row-class-name="deployRowClassName"
            @current-change="(row: DeployRecordItem | null) => selectRecord(row, batch.batchNo)"
          >
            <el-table-column prop="deployStatus" :label="t('deployRecords.columnStatus')" width="120" align="center">
              <template #default="{ row }">
                <DeployStatusTag :status="row.deployStatus" size="small" />
              </template>
            </el-table-column>
            <el-table-column prop="hostId" :label="t('deployRecords.columnHost')" width="130" align="center">
              <template #default="{ row }">
                <span>{{ row.hostId ?? t('deployRecords.unknownHost') }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="versionId" :label="t('deployRecords.columnVersion')" width="130" align="center">
              <template #default="{ row }">
                <span>{{ row.versionId ?? t('deployRecords.unknownVersion') }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="startedAt" :label="t('deployRecords.columnStartedAt')" min-width="180">
              <template #default="{ row }">
                {{ formatTime(row.startedAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="endedAt" :label="t('deployRecords.columnEndedAt')" min-width="180">
              <template #default="{ row }">
                {{ formatTime(row.endedAt) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('deployRecords.columnDuration')" width="100">
              <template #default="{ row }">
                {{ calculateDuration(row.startedAt, row.endedAt) }}
              </template>
            </el-table-column>
            <el-table-column :label="t('deployRecords.columnAction')" width="110" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="row.deployLog"
                  link
                  type="primary"
                  size="small"
                  @click.stop="selectRecord(row, batch.batchNo)"
                >
                  {{ t('deployRecords.viewLog') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 加载中：与“暂无记录”和“读取失败”分开表达 -->
    <div v-else-if="loading && !error" class="deploy-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('deployRecords.loading') }}</span>
    </div>

    <el-empty v-else-if="!error" :description="t('deployRecords.empty')" />

    <!-- T-C: Log panel with copy per Contract 2.7 -->
    <section v-if="selectedRecord" class="deploy-log-panel">
      <div class="deploy-log-header">
        <span class="deploy-log-title">
          {{ t('deployRecords.logPanelTitle') }}
          <span v-if="selectedBatchNo" class="log-subtitle">{{ t('deployRecords.logPanelSubtitle', { batchNo: selectedBatchNo, hostId: selectedRecord.hostId }) }}</span>
        </span>
        <div class="log-actions">
          <el-button size="small" text @click="logWrap = !logWrap">
            {{ logWrap ? t('deployRecords.wrapOff') : t('deployRecords.wrapOn') }}
          </el-button>
          <el-button v-if="selectedRecord.deployLog" size="small" text type="primary" @click="copyLog">
            <el-icon><DocumentCopy /></el-icon>
            {{ t('deployRecords.copyLog') }}
          </el-button>
          <el-button size="small" text @click="selectedRecord = null">
            <el-icon><Close /></el-icon>
            {{ t('deployRecords.close') }}
          </el-button>
        </div>
      </div>

      <!-- 选中记录摘要 -->
      <div class="record-summary">
        <span><b>{{ t('deployRecords.recordId') }}</b> {{ selectedRecord.id ?? t('deployRecords.unknownValue') }}</span>
        <span><b>{{ t('deployRecords.recordStatus') }}</b> <DeployStatusTag :status="selectedRecord.deployStatus" size="small" /></span>
        <span v-if="isStatusUnconfirmed(selectedRecord.deployStatus)" class="record-unconfirmed">
          {{ t('deployRecords.statusUnconfirmed') }}
        </span>
        <span><b>{{ t('deployRecords.recordHost') }}</b> {{ selectedRecord.hostId ?? t('deployRecords.unknownHost') }}</span>
        <span><b>{{ t('deployRecords.recordVersion') }}</b> {{ selectedRecord.versionId ?? t('deployRecords.unknownVersion') }}</span>
        <span><b>{{ t('deployRecords.recordStartedAt') }}</b> {{ formatTime(selectedRecord.startedAt) }}</span>
        <span><b>{{ t('deployRecords.recordEndedAt') }}</b> {{ formatTime(selectedRecord.endedAt) }}</span>
        <span><b>{{ t('deployRecords.recordDuration') }}</b> {{ calculateDuration(selectedRecord.startedAt, selectedRecord.endedAt) }}</span>
        <span class="record-nav-actions">
          <el-button link type="primary" size="small" @click="navigateToRuntimeInstance(selectedRecord)">{{ t('deployRecords.viewRuntimeInstance') }}</el-button>
          <el-button link type="primary" size="small" @click="navigateToCurrentVersion(selectedRecord)">{{ t('deployRecords.viewCurrentVersion') }}</el-button>
        </span>
      </div>

      <!-- 失败定位提示 -->
      <el-alert
        v-if="selectedRecord.deployStatus === 'FAILED' && failureHint"
        :title="failureHint"
        type="error"
        show-icon
        :closable="false"
        class="failure-hint"
      />
      <!-- 结果未知：远端动作可能仍在运行，不能按“已失败”处理 -->
      <el-alert
        v-else-if="selectedRecord.deployStatus === 'UNKNOWN'"
        :title="t('deployRecords.unknownResultHint')"
        type="warning"
        show-icon
        :closable="false"
        class="failure-hint"
      />

      <pre class="deploy-log" :class="{ 'deploy-log-nowrap': !logWrap }">{{ selectedRecord.deployLog || t('deployRecords.noLog') }}</pre>
    </section>
    <el-empty v-else-if="hasRecords" :description="t('deployRecords.selectRecordHint')" :image-size="60" />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('deployRecords.close') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { DocumentCopy, Close, Loading, WarningFilled } from '@element-plus/icons-vue';
import DeployStatusTag from '@/components/common/DeployStatusTag.vue';

interface DeployRecordItem {
  id: number;
  releaseOrderId?: number;
  rollbackOrderId?: number;
  operationType: string;
  serviceId: number;
  envId: number;
  hostId: number;
  versionId: number;
  deployBatch: number;
  deployStatus: string;
  deployLog?: string;
  startedAt?: string;
  endedAt?: string;
  createdAt?: string;
}

/**
 * 部署记录状态只能落在四类明确语义上：
 * SUCCESS / FAILED / RUNNING 为后端明确返回的状态，其余（缺失、空串、未知取值）一律为 UNKNOWN，
 * 既不能算作成功，也不能被折叠成“失败”。
 */
type RecordStatusClass = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'UNKNOWN';

function classifyDeployStatus(status: unknown): RecordStatusClass {
  const value = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (value === 'SUCCESS') return 'SUCCESS';
  if (value === 'FAILED') return 'FAILED';
  if (value === 'RUNNING') return 'RUNNING';
  return 'UNKNOWN';
}

interface StatusSummary {
  total: number;
  success: number;
  failed: number;
  running: number;
  unknown: number;
  /** 仅当每一条记录都被后端明确标记为 SUCCESS 时才为 true */
  allSuccess: boolean;
  tagType: 'primary' | 'success' | 'warning' | 'danger' | 'info';
  headline: string;
  breakdownText: string;
}

/**
 * 总览与每个批次共用的唯一状态判据：先报失败，其次报执行中，再报未确认，
 * 只有全部为 SUCCESS 时才能出现“全部成功”。
 */
function summarize(records: DeployRecordItem[]): StatusSummary {
  let success = 0;
  let failed = 0;
  let running = 0;
  let unknown = 0;

  records.forEach((record) => {
    switch (classifyDeployStatus(record.deployStatus)) {
      case 'SUCCESS':
        success += 1;
        break;
      case 'FAILED':
        failed += 1;
        break;
      case 'RUNNING':
        running += 1;
        break;
      default:
        unknown += 1;
        break;
    }
  });

  const total = records.length;
  const allSuccess = total > 0 && success === total;
  // 汇总文案随语言切换重算：这里按当前语言取值，不在初始化时冻结
  const breakdownText = [
    success > 0 ? t('deployRecords.summarySuccessCount', { count: success }) : '',
    failed > 0 ? t('deployRecords.summaryFailedCount', { count: failed }) : '',
    running > 0 ? t('deployRecords.summaryRunningCount', { count: running }) : '',
    unknown > 0 ? t('deployRecords.summaryUnknownCount', { count: unknown }) : '',
  ].filter(Boolean).join(' · ');

  let headline = t('deployRecords.summaryNone');
  let tagType: StatusSummary['tagType'] = 'info';
  if (failed > 0) {
    headline = t('deployRecords.summaryFailed', { count: failed });
    tagType = 'danger';
  } else if (running > 0) {
    headline = t('deployRecords.summaryRunning', { count: running });
    tagType = 'primary';
  } else if (unknown > 0) {
    headline = t('deployRecords.summaryUnknown', { count: unknown });
    tagType = 'warning';
  } else if (allSuccess) {
    headline = t('deployRecords.summaryAllSuccess');
    tagType = 'success';
  }

  return { total, success, failed, running, unknown, allSuccess, tagType, headline, breakdownText };
}

interface BatchGroup {
  batchNo: number;
  records: DeployRecordItem[];
  summary: StatusSummary;
  startedAt?: string;
}

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  records: DeployRecordItem[];
  selectedRecordId?: number | null;
  /** 只读读取失败信息；失败时展示错误而不是“暂无记录” */
  error?: string | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'retry'): void;
}>();

const { t, locale } = useI18n();
const router = useRouter();
const selectedRecord = ref<DeployRecordItem | null>(null);
const selectedBatchNo = ref<number | null>(null);
const expandedBatches = ref<number[]>([]);
const logWrap = ref(true);

// T-C: Group by batch per Contract 2.7
const batchGroups = computed<BatchGroup[]>(() => {
  const groupMap = new Map<number, DeployRecordItem[]>();

  props.records.forEach((record) => {
    const batchNo = record.deployBatch || 1;
    if (!groupMap.has(batchNo)) {
      groupMap.set(batchNo, []);
    }
    groupMap.get(batchNo)!.push(record);
  });

  const groups: BatchGroup[] = [];
  groupMap.forEach((records, batchNo) => {
    const startedAt = records.reduce((min: string | undefined, r) => {
      if (!r.startedAt) return min;
      if (!min) return r.startedAt;
      return r.startedAt < min ? r.startedAt : min;
    }, undefined);
    groups.push({
      batchNo,
      records,
      summary: summarize(records),
      startedAt,
    });
  });

  // Sort by batch number
  return groups.sort((a, b) => a.batchNo - b.batchNo);
});

/** 与批次标签同源的记录总览 */
const summary = computed<StatusSummary>(() => summarize(props.records));

const hasRecords = computed(() => props.records.length > 0);

function isStatusUnconfirmed(status: unknown): boolean {
  return classifyDeployStatus(status) === 'UNKNOWN';
}

// 失败分类工具函数
// 注意：这里的判据是后端返回的原始日志文本，日志原文与匹配片段一律不改写、不翻译；
// 只有"匹配不到片段"时的兜底说明取自 i18n。
function categorizeFailure(log: string): { category: string; hint: string } | null {
  if (!log) return null;
  if (log.includes('前置检查失败') || log.includes('precheck failed') || log.includes('__INFRAHUB_PRECHECK_SUCCESS__=false')) {
    const match = log.match(/前置检查失败[^\n]*|precheck failed[^\n]*/i);
    return { category: 'PRECHECK_FAILED', hint: match ? match[0] : t('deployRecords.failureDetailPrecheckFailed') };
  }
  if (log.includes('Artifact file not found') || log.includes('artifact not found')) {
    const match = log.match(/Artifact file not found: [^\n]+/i);
    return { category: 'ARTIFACT_MISSING', hint: match ? match[0] : t('deployRecords.failureDetailArtifactMissing') };
  }
  if (log.includes('端口已被占用') || log.includes('port already in use') || log.includes('Address already in use')) {
    const match = log.match(/端口已被占用[^\n]*|port already in use[^\n]*|Address already in use[^\n]*/i);
    return { category: 'PORT_IN_USE', hint: match ? match[0] : t('deployRecords.failureDetailPortInUse') };
  }
  if (log.includes('Connection refused') || log.includes('Permission denied') || (log.includes('SSH') && log.includes('failed'))) {
    const match = log.match(/Connection refused[^\n]*|Permission denied[^\n]*/i);
    return { category: 'SSH_CONNECTION_FAILED', hint: match ? match[0] : t('deployRecords.failureDetailSshConnectionFailed') };
  }
  if (log.includes('启动失败') || log.includes('start failed') || log.includes('Failed to start')) {
    const match = log.match(/启动失败[^\n]*|start failed[^\n]*|Failed to start[^\n]*/i);
    return { category: 'START_FAILED', hint: match ? match[0] : t('deployRecords.failureDetailStartFailed') };
  }
  if (log.includes('Health check failed') || log.includes('健康检查失败') || log.includes('health check')) {
    const match = log.match(/Health check failed[^\n]*|健康检查失败[^\n]*/i);
    return { category: 'HEALTH_CHECK_FAILED', hint: match ? match[0] : t('deployRecords.failureDetailHealthCheckFailed') };
  }
  return { category: 'UNKNOWN', hint: t('deployRecords.failureDetailUnknown') };
}

/** 失败归类标签：与后端日志无关的展示文案，随语言切换重算 */
const FAILURE_CATEGORY_KEYS: Record<string, string> = {
  PRECHECK_FAILED: 'deployRecords.failureCategoryPrecheckFailed',
  ARTIFACT_MISSING: 'deployRecords.failureCategoryArtifactMissing',
  PORT_IN_USE: 'deployRecords.failureCategoryPortInUse',
  SSH_CONNECTION_FAILED: 'deployRecords.failureCategorySshConnectionFailed',
  START_FAILED: 'deployRecords.failureCategoryStartFailed',
  HEALTH_CHECK_FAILED: 'deployRecords.failureCategoryHealthCheckFailed',
  UNKNOWN: 'deployRecords.failureCategoryUnknown',
};

const failureHint = computed(() => {
  if (!selectedRecord.value || selectedRecord.value.deployStatus !== 'FAILED') {
    return '';
  }
  const log = selectedRecord.value.deployLog || '';
  const result = categorizeFailure(log);
  if (!result) return t('deployRecords.failureHintFallback');
  const label = t(FAILURE_CATEGORY_KEYS[result.category] || 'deployRecords.failureCategoryUnknown');
  // 分类外框与详情抽屉共用同一条 zh/en 条目（中文「【分类】说明」，英文「[Category] detail」），
  // 不在组件里写死中文括号；result.hint 可能是后端日志原文，照原样嵌入、不翻译。
  return t('releaseDetail.failureItem', { source: label, text: result.hint });
});

function selectRecord(row: DeployRecordItem | null, batchNo?: number) {
  selectedRecord.value = row;
  if (batchNo !== undefined) {
    selectedBatchNo.value = batchNo;
  }
}

// V-D: navigateToRuntimeInstance — jump to runtime instances filtered by service/env
function navigateToRuntimeInstance(record: DeployRecordItem) {
  const query: Record<string, string> = {};
  if (record.serviceId) query.serviceId = String(record.serviceId);
  if (record.envId) query.envId = String(record.envId);
  router.push({ path: '/resource/runtime-instances', query });
}

// V-D: navigateToCurrentVersion — jump to current versions filtered by app/env
function navigateToCurrentVersion(record: DeployRecordItem) {
  const query: Record<string, string> = {};
  if (record.envId) query.envId = String(record.envId);
  router.push({ path: '/release/current-versions', query });
}

/** 失败行保持红底；当前查看的记录额外高亮，便于深链定位后仍然可辨认 */
function deployRowClassName({ row }: { row: DeployRecordItem }): string {
  const classes: string[] = [];
  if (classifyDeployStatus(row.deployStatus) === 'FAILED') {
    classes.push('deploy-failed-row');
  }
  if (selectedRecord.value && row.id === selectedRecord.value.id) {
    classes.push('deploy-target-row');
  }
  return classes.join(' ');
}

/** 时间按当前控制台语言区域格式化，不写死某一语言 */
function formatTime(time?: string): string {
  if (!time) return '-';
  try {
    const date = new Date(time);
    return date.toLocaleString(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return time;
  }
}

function calculateDuration(started?: string, ended?: string): string {
  if (!started || !ended) return '-';
  try {
    const start = new Date(started);
    const end = new Date(ended);
    const diffMs = end.getTime() - start.getTime();
    if (diffMs < 0) return '-';
    const seconds = Math.floor(diffMs / 1000);
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    if (minutes > 0) {
      return t('deployRecords.durationMinutesSeconds', { minutes, seconds: secs });
    }
    return t('deployRecords.durationSeconds', { seconds: secs });
  } catch {
    return '-';
  }
}

async function copyLog() {
  const log = selectedRecord.value?.deployLog;
  if (!log) {
    // 没有可复制的日志时明确告知，不静默丢弃
    ElMessage.warning(t('deployRecords.toastNoLogToCopy'));
    return;
  }
  try {
    await navigator.clipboard.writeText(log);
    ElMessage.success(t('deployRecords.toastLogCopied'));
  } catch {
    ElMessage.error(t('deployRecords.toastCopyFailed'));
  }
}

// T-C: Copy all failed logs per Contract 2.7
async function copyFailedLogs() {
  const failedRecords = props.records.filter((r) => classifyDeployStatus(r.deployStatus) === 'FAILED');
  if (failedRecords.length === 0) {
    ElMessage.warning(t('deployRecords.toastNoFailedRecord'));
    return;
  }

  const lines = failedRecords.map((r) => {
    return [
      `${t('deployRecords.copyRecordId')}${r.id ?? t('deployRecords.unknownValue')}`,
      `${t('deployRecords.copyBatch')}${r.deployBatch}`,
      `${t('deployRecords.copyHost')}${r.hostId}`,
      `${t('deployRecords.copyStatus')}${r.deployStatus ?? t('deployRecords.statusUnconfirmed')}`,
      `${t('deployRecords.copyStartedAt')}${formatTime(r.startedAt)}`,
      `${t('deployRecords.copyEndedAt')}${formatTime(r.endedAt)}`,
      `${t('deployRecords.copyDuration')}${calculateDuration(r.startedAt, r.endedAt)}`,
      t('deployRecords.copyLogSection'),
      r.deployLog || t('deployRecords.noLog'),
      t('deployRecords.copySeparator'),
    ].join('\n');
  });

  const text = `${t('deployRecords.copyHeader', { count: failedRecords.length })}\n\n${lines.join('\n')}`;

  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(t('deployRecords.toastFailedLogsCopied'));
  } catch {
    ElMessage.error(t('deployRecords.toastCopyFailed'));
  }
}

watch(
  () => props.records,
  (records) => {
    selectedRecord.value = null;
    selectedBatchNo.value = null;
    // 默认展开所有“并非全部明确成功”的批次
    const attentionBatchNos = batchGroups.value.filter((g) => !g.summary.allSuccess).map((g) => g.batchNo);
    expandedBatches.value = attentionBatchNos.length > 0
      ? attentionBatchNos
      : [records[0]?.deployBatch].filter(Boolean);
    // Auto-select first failed or first record
    if (props.selectedRecordId) {
      const target = records.find((r) => r.id === props.selectedRecordId);
      if (target) {
        selectedRecord.value = target;
        selectedBatchNo.value = target.deployBatch;
      }
    } else if (attentionBatchNos.length > 0) {
      const firstFailed = records.find((r) => classifyDeployStatus(r.deployStatus) === 'FAILED');
      if (firstFailed) {
        selectedRecord.value = firstFailed;
        selectedBatchNo.value = firstFailed.deployBatch;
      }
    }
  },
  { immediate: true },
);
</script>

<style scoped lang="scss">
.read-error-block {
  display: flex;
  align-items: flex-start;
  gap: var(--ih-spacing-sm);
  padding: var(--ih-spacing-sm);
  margin-bottom: var(--ih-section-gap);
  border: 1px solid var(--ih-danger);
  border-radius: var(--ih-radius-md);
  background: var(--ih-danger-bg);
}

.read-error-icon {
  color: var(--ih-danger);
  margin-top: 2px;
}

.read-error-main {
  flex: 1;
  min-width: 0;
}

.read-error-title {
  font-weight: 500;
  color: var(--ih-text-primary);
}

.read-error-text {
  font-size: 12px;
  margin-top: 2px;
  word-break: break-word;
  color: var(--ih-text-primary);
}

.read-error-hint {
  font-size: 12px;
  margin-top: 2px;
  color: var(--ih-text-secondary);
}

.deploy-summary-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--ih-spacing-sm);
  margin-bottom: var(--ih-section-gap);
}

.summary-detail {
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.summary-breakdown {
  font-size: 13px;
  color: var(--ih-text-secondary);
  padding: 2px var(--ih-spacing-3);
  border: 1px solid var(--ih-border-light);
  border-radius: var(--ih-radius-sm);
  background: var(--ih-bg-subtle);
}

.deploy-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ih-spacing-3);
  padding: var(--ih-spacing-xl) 0;
  color: var(--ih-text-secondary);
  font-size: 13px;
}

.deploy-groups {
  max-height: min(400px, 42vh);
  overflow: auto;
}

.batch-collapse {
  border: none;
}

.batch-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
  width: 100%;
  min-width: 0;
}

.batch-name {
  font-weight: 500;
}

.batch-summary {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.batch-breakdown {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.batch-time {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-left: auto;
}

.deploy-log-panel {
  margin-top: var(--ih-section-gap);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  padding: var(--ih-spacing-sm);
  background: transparent;
}

.deploy-log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-3);
}

.deploy-log-title {
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.log-subtitle {
  font-weight: 400;
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.log-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ih-spacing-3);
}

.record-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  padding: var(--space-xs) 0;
  margin-bottom: var(--space-sm);
  border-top: var(--rule-hair) solid var(--color-rule);
  border-bottom: var(--rule-hair) solid var(--color-rule);
  background: transparent;
  font-size: var(--text-xs);

  span {
    white-space: nowrap;

    b {
      color: var(--ih-text-secondary);
    }
  }
}

.record-unconfirmed {
  color: var(--ih-warning);
}

.record-nav-actions {
  margin-left: auto;
  display: flex;
  gap: var(--ih-spacing-3);
}

.failure-hint {
  margin-bottom: var(--ih-spacing-3);
}

.deploy-log {
  min-height: 180px;
  max-height: min(320px, 30vh);
  overflow: auto;
  padding: var(--ih-spacing-sm);
  margin: 0;
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-row-hover);
  color: var(--ih-text-primary);
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

// 关闭自动换行时保留原始列对齐，长行改为横向滚动，仍可复制完整原文
.deploy-log-nowrap {
  white-space: pre;
  overflow-wrap: normal;
  word-break: normal;
}
</style>

<style lang="scss">
// 弹窗高度受控：滚动限制在批次表格与日志区域内，避免在 1024 宽 / 低视口下整页滚动
.deploy-records-dialog .el-dialog__body {
  max-height: calc(88vh - 150px);
  overflow: hidden;
}

// Failed row highlight (unscoped for el-table row class)
.el-table .deploy-failed-row {
  background-color: var(--ih-danger-bg);
}

// 当前查看记录的高亮（深链 selectedRecordId 定位后的落点）
.el-table .deploy-target-row > td.el-table__cell {
  background-color: var(--ih-primary-bg);
}
</style>