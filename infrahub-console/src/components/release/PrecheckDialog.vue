<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('precheckDialog.title')"
    class="precheck-dialog"
    width="min(1100px, 94vw)"
    top="4vh"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- 检查结果读取失败：明确表达失败，不与“没有预检记录”混淆 -->
    <div v-if="error" class="read-error-block" role="alert">
      <el-icon class="read-error-icon"><WarningFilled /></el-icon>
      <div class="read-error-main">
        <div class="read-error-title">{{ t('precheckDialog.readFailed') }}</div>
        <div class="read-error-text">{{ error }}</div>
        <div class="read-error-hint">
          {{ t('precheckDialog.readFailedHint') }}
        </div>
      </div>
    </div>

    <!-- T-C: Summary bar per Contract 2.3-2.4 -->
    <div v-if="hasRecords" class="precheck-summary-bar">
      <el-tag :type="summary.tagType" size="large">{{ summary.headline }}</el-tag>
      <span class="summary-detail">{{ t('precheckDialog.summaryDetail', { total: records.length, passed: summary.passedCount, failed: summary.failedCount }) }}</span>
      <span v-if="summary.unconfirmedCount > 0" class="summary-unconfirmed">
        {{ t('precheckDialog.summaryUnconfirmed', { count: summary.unconfirmedCount }) }}
      </span>
      <el-button v-if="summary.failedCount > 0" size="small" type="primary" text @click="copyFailedRecords">
        <el-icon><DocumentCopy /></el-icon>
        {{ t('precheckDialog.copyFailedRecords') }}
      </el-button>
    </div>

    <!-- 结构化摘要读取失败：与“没有摘要”区分，只提供只读重试 -->
    <div v-if="summaryError" class="summary-error-bar" role="alert">
      <el-icon><WarningFilled /></el-icon>
      <span class="summary-error-text">
        {{ t('precheckDialog.summaryReadFailed', { reason: summaryError }) }}
      </span>
      <el-button size="small" text type="primary" :loading="loading" @click="emit('retrySummary')">
        {{ t('precheckDialog.retryReadOnly') }}
      </el-button>
    </div>

    <!-- V-B: Structured Summary (Contract 4.2.1) -->
    <div v-if="precheckSummary" class="structured-summary">
      <el-tabs v-model="activeTab" class="summary-tabs">
        <!-- Host Summaries Tab -->
        <el-tab-pane :label="t('precheckDialog.tabHosts')" name="hosts">
          <div v-if="precheckSummary.hostSummaries.length > 0" class="host-summaries">
            <div class="host-summary-stats">
              <el-tag :type="precheckSummary.overallStatus === 'SUCCESS' ? 'success' : precheckSummary.overallStatus === 'FAILED' ? 'danger' : 'warning'" size="small">
                {{ getOverallStatusLabel(precheckSummary.overallStatus) }}
              </el-tag>
              <span class="stat-text">
                {{ t('precheckDialog.hostStats', { total: precheckSummary.totalHosts, success: precheckSummary.successHosts, failed: precheckSummary.failedHosts }) }}
              </span>
            </div>
            <el-table :data="precheckSummary.hostSummaries" size="small" class="host-summary-table">
              <el-table-column prop="hostName" :label="t('precheckDialog.columnHost')" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <span>{{ row.hostName || t('precheckDialog.hostFallback', { id: row.hostId }) || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="instanceName" :label="t('precheckDialog.columnInstance')" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <span>{{ row.instanceName || (row.instanceId ? t('precheckDialog.instanceFallback', { id: row.instanceId }) : '-') }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="status" :label="t('precheckDialog.columnStatus')" width="140">
                <template #default="{ row }">
                  <el-tag :type="getHostStatusTagType(row.status)" size="small">
                    {{ getHostStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('precheckDialog.columnSteps')" width="140">
                <template #default="{ row }">
                  <span class="step-count">
                    <span class="step-success">{{ t('precheckDialog.stepsSuccess', { count: row.successSteps }) }}</span>
                    <span v-if="row.failedSteps > 0" class="step-failed">{{ t('precheckDialog.stepsFailed', { count: row.failedSteps }) }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column prop="lastErrorMessage" :label="t('precheckDialog.columnError')" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">
                  <span :class="{ 'text-danger': row.lastErrorMessage }">{{ row.lastErrorMessage || '-' }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty v-else :description="t('precheckDialog.emptyHostSummaries')" />
        </el-tab-pane>

        <!-- Failure Categories Tab -->
        <el-tab-pane :label="t('precheckDialog.tabFailures')" name="failures">
          <div v-if="precheckSummary.failureCategories.length > 0" class="failure-categories">
            <div
              v-for="cat in precheckSummary.failureCategories"
              :key="cat.category"
              class="failure-category-item"
            >
              <div class="category-header">
                <el-tag :type="getFailureCategoryTagType(cat.category)" size="small">
                  {{ getFailureCategoryLabel(cat.category) }}
                </el-tag>
                <span class="category-count">{{ t('precheckDialog.categoryCount', { count: cat.count }) }}</span>
              </div>
              <div v-if="cat.message" class="category-message">{{ cat.message }}</div>
            </div>
          </div>
          <el-empty v-else :description="t('precheckDialog.emptyFailures')" />
        </el-tab-pane>

        <!-- Suggestions Tab -->
        <el-tab-pane :label="t('precheckDialog.tabSuggestions')" name="suggestions">
          <div v-if="precheckSummary.suggestions.length > 0" class="suggestions-list">
            <div
              v-for="(suggestion, index) in precheckSummary.suggestions"
              :key="index"
              class="suggestion-item"
            >
              <div class="suggestion-header">
                <el-tag :type="getSuggestionTagType(suggestion.suggestionType)" size="small">
                  {{ getSuggestionTypeLabel(suggestion.suggestionType) }}
                </el-tag>
                <span class="suggestion-title">{{ suggestion.title }}</span>
              </div>
              <div v-if="suggestion.description" class="suggestion-desc">{{ suggestion.description }}</div>
              <el-button
                v-if="suggestion.targetRoute"
                size="small"
                type="primary"
                text
                class="suggestion-action"
                @click="navigateToSuggestion(suggestion)"
              >
                {{ t('precheckDialog.suggestionAction') }}
              </el-button>
            </div>
          </div>
          <el-empty v-else :description="t('precheckDialog.emptySuggestions')" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- T-C: Grouped by host per Contract 2.3 -->
    <div v-if="hasRecords" class="precheck-groups">
      <el-collapse v-model="expandedHosts" class="host-collapse">
        <el-collapse-item
          v-for="group in hostGroups"
          :key="group.hostId"
          :name="group.hostId"
        >
          <template #title>
            <div class="host-header">
              <el-tag :type="group.summary.tagType" size="small">
                {{ group.summary.headline }}
              </el-tag>
              <span class="host-name">{{ t('precheckDialog.groupHostLabel', { id: group.hostId }) }}</span>
              <span class="host-summary">{{ t('precheckDialog.groupInstanceCount', { count: group.instances.length }) }}</span>
              <span
                v-if="group.instances.length > 1"
                class="host-breakdown"
              >{{ group.summary.breakdownText }}</span>
            </div>
          </template>

          <el-table
            :data="group.instances"
            highlight-current-row
            :row-class-name="precheckRowClassName"
            @current-change="(row: ReleasePrecheckInstanceResult | null) => selectRecord(row, group.hostId)"
          >
            <el-table-column prop="success" :label="t('precheckDialog.columnStatus')" width="140">
              <template #default="{ row }">
                <el-tag v-if="classifyPrecheck(row) === 'UNKNOWN'" type="warning" size="small">{{ t('precheckDialog.statusUnconfirmed') }}</el-tag>
                <DeployStatusTag v-else :status="row.success ? 'SUCCESS' : 'FAILED'" size="small" />
              </template>
            </el-table-column>
            <el-table-column prop="instanceId" :label="t('precheckDialog.columnInstanceId')" width="120">
              <template #default="{ row }">
                <span>{{ row.instanceId ?? t('precheckDialog.unknownInstance') }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="artifactUri" :label="t('precheckDialog.columnArtifact')" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.artifactUri || t('precheckDialog.artifactMissing') }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="errorMessage" :label="t('precheckDialog.columnFailureReason')" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="{ 'text-danger': row.success === false && row.errorMessage }">{{ row.errorMessage || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('precheckDialog.columnAction')" width="100">
              <template #default="{ row }">
                <el-button
                  v-if="row.stdout || row.stderr || row.errorMessage"
                  link
                  type="primary"
                  size="small"
                  @click.stop="selectRecord(row, group.hostId)"
                >
                  {{ t('precheckDialog.actionViewLog') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 加载中：与“暂无预检记录”和“读取失败”分开表达 -->
    <div v-else-if="loading && !error" class="precheck-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('precheckDialog.loading') }}</span>
    </div>

    <el-empty v-else-if="!error && !precheckSummary" :description="t('precheckDialog.emptyRecords')" />

    <!-- 全部通过提示：只代表前置检查这一环节通过 -->
    <el-alert
      v-if="allPassed && hasRecords"
      :title="t('precheckDialog.allPassedTitle')"
      :description="t('precheckDialog.allPassedDescription')"
      type="success"
      show-icon
      :closable="false"
      class="precheck-alert"
    />

    <!-- 失败汇总 -->
    <el-alert
      v-else-if="summary.failedCount > 0"
      :title="t('precheckDialog.failedTitle', { count: summary.failedCount })"
      type="error"
      show-icon
      :closable="false"
      class="precheck-alert"
    />

    <!-- 未确认汇总：未确认不算通过 -->
    <el-alert
      v-else-if="summary.unconfirmedCount > 0"
      :title="t('precheckDialog.unconfirmedTitle', { count: summary.unconfirmedCount })"
      :description="t('precheckDialog.unconfirmedDescription')"
      type="warning"
      show-icon
      :closable="false"
      class="precheck-alert"
    />

    <!-- T-C: Log panel with copy per Contract 2.3 -->
    <section v-if="selectedRecord" class="precheck-log-panel">
      <div class="precheck-log-header">
        <span class="precheck-log-title">
          {{ t('precheckDialog.logPanelTitle') }}
          <span v-if="selectedHostId" class="log-subtitle">{{ t('precheckDialog.logPanelSubtitle', { hostId: selectedHostId, instanceId: selectedRecord.instanceId }) }}</span>
        </span>
        <div class="log-actions">
          <el-button size="small" text @click="logWrap = !logWrap">
            {{ logWrap ? t('precheckDialog.wrapOff') : t('precheckDialog.wrapOn') }}
          </el-button>
          <el-button v-if="hasOutput" size="small" text type="primary" @click="copyOutput">
            <el-icon><DocumentCopy /></el-icon>
            {{ t('precheckDialog.copyLog') }}
          </el-button>
          <el-button size="small" text @click="selectedRecord = null">
            <el-icon><Close /></el-icon>
            {{ t('precheckDialog.close') }}
          </el-button>
        </div>
      </div>
      <template v-if="hasOutput">
        <pre class="precheck-log" :class="{ 'precheck-log-nowrap': !logWrap }">{{ selectedOutput }}</pre>
      </template>
      <template v-else>
        <div class="no-output">{{ t('precheckDialog.noOutput') }}</div>
      </template>
    </section>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('precheckDialog.close') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { DocumentCopy, Close, Loading, WarningFilled } from '@element-plus/icons-vue';
import type { ReleasePrecheckInstanceResult, PrecheckSummaryResponse, PrecheckSuggestionItem } from '@/api/release';
import DeployStatusTag from '@/components/common/DeployStatusTag.vue';

/**
 * 前置检查结果只有三种明确语义：
 * PASS（success === true）、FAIL（success === false）、UNKNOWN（缺失或非布尔值）。
 * UNKNOWN 既不归入通过，也不谎称为失败。
 */
type PrecheckState = 'PASS' | 'FAIL' | 'UNKNOWN';

function classifyPrecheck(record: ReleasePrecheckInstanceResult): PrecheckState {
  const success = (record as { success?: unknown }).success;
  if (success === true) return 'PASS';
  if (success === false) return 'FAIL';
  return 'UNKNOWN';
}

interface PrecheckSummary {
  passedCount: number;
  failedCount: number;
  unconfirmedCount: number;
  /** 仅当每一条结果都被明确标记为 success === true 时才为 true */
  allPassed: boolean;
  tagType: 'success' | 'warning' | 'danger' | 'info';
  headline: string;
  breakdownText: string;
}

/** 总览与主机分组共用的唯一判据 */
function summarize(records: ReleasePrecheckInstanceResult[]): PrecheckSummary {
  let passedCount = 0;
  let failedCount = 0;
  let unconfirmedCount = 0;

  records.forEach((record) => {
    switch (classifyPrecheck(record)) {
      case 'PASS':
        passedCount += 1;
        break;
      case 'FAIL':
        failedCount += 1;
        break;
      default:
        unconfirmedCount += 1;
        break;
    }
  });

  const allPassed = records.length > 0 && passedCount === records.length;
  const breakdownText = [
    passedCount > 0 ? t('precheckDialog.breakdownPassed', { count: passedCount }) : '',
    failedCount > 0 ? t('precheckDialog.breakdownFailed', { count: failedCount }) : '',
    unconfirmedCount > 0 ? t('precheckDialog.breakdownUnconfirmed', { count: unconfirmedCount }) : '',
  ].filter(Boolean).join(' · ');

  let headline = t('precheckDialog.summaryNone');
  let tagType: PrecheckSummary['tagType'] = 'info';
  if (failedCount > 0) {
    headline = t('precheckDialog.summaryFailed', { count: failedCount });
    tagType = 'danger';
  } else if (unconfirmedCount > 0) {
    headline = t('precheckDialog.summaryUnconfirmedHeadline', { count: unconfirmedCount });
    tagType = 'warning';
  } else if (allPassed) {
    headline = t('precheckDialog.summaryAllPassed');
    tagType = 'success';
  }

  return { passedCount, failedCount, unconfirmedCount, allPassed, tagType, headline, breakdownText };
}

interface HostGroup {
  hostId: number;
  instances: ReleasePrecheckInstanceResult[];
  summary: PrecheckSummary;
  passedCount: number;
  failedCount: number;
}

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  records: ReleasePrecheckInstanceResult[];
  precheckSummary?: PrecheckSummaryResponse | null;
  /** 检查结果读取/执行失败信息；失败时展示错误而不是“暂无预检记录” */
  error?: string | null;
  /** 结构化摘要读取失败信息；与“没有摘要”区分 */
  summaryError?: string | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  /** 只读重试结构化摘要（GET /precheck-summary）；不会重新执行前置检查 */
  (event: 'retrySummary'): void;
}>();

const router = useRouter();
const { t } = useI18n();
const selectedRecord = ref<ReleasePrecheckInstanceResult | null>(null);
const selectedHostId = ref<number | null>(null);
const expandedHosts = ref<number[]>([]);
const activeTab = ref('hosts');
const logWrap = ref(true);

const hasRecords = computed(() => props.records.length > 0);

/** 与主机分组标签同源的总览 */
const summary = computed<PrecheckSummary>(() => summarize(props.records));
const allPassed = computed(() => summary.value.allPassed);

// T-C: Group by host per Contract 2.3
const hostGroups = computed<HostGroup[]>(() => {
  const groupMap = new Map<number, ReleasePrecheckInstanceResult[]>();

  props.records.forEach((record) => {
    const hostId = record.hostId;
    if (!groupMap.has(hostId)) {
      groupMap.set(hostId, []);
    }
    groupMap.get(hostId)!.push(record);
  });

  const groups: HostGroup[] = [];
  groupMap.forEach((instances, hostId) => {
    const groupSummary = summarize(instances);
    groups.push({
      hostId,
      instances,
      summary: groupSummary,
      passedCount: groupSummary.passedCount,
      failedCount: groupSummary.failedCount,
    });
  });

  // Sort: failed hosts first
  return groups.sort((a, b) => {
    if (a.summary.allPassed !== b.summary.allPassed) {
      return a.summary.allPassed ? 1 : -1;
    }
    return a.hostId - b.hostId;
  });
});

const hasOutput = computed(() => {
  if (!selectedRecord.value) {
    return false;
  }
  const r = selectedRecord.value;
  return Boolean(r.stdout || r.stderr || r.errorMessage);
});

const selectedOutput = computed(() => {
  if (!selectedRecord.value) {
    return '';
  }
  return [
    selectedRecord.value.stdout ? `STDOUT\n${selectedRecord.value.stdout}` : '',
    selectedRecord.value.stderr ? `STDERR\n${selectedRecord.value.stderr}` : '',
    selectedRecord.value.errorMessage ? `ERROR\n${selectedRecord.value.errorMessage}` : '',
  ].filter(Boolean).join('\n\n') || '';
});

function selectRecord(row: ReleasePrecheckInstanceResult | null, hostId?: number) {
  selectedRecord.value = row;
  if (hostId !== undefined) {
    selectedHostId.value = hostId;
  }
}

// T-D: Highlight failed rows per Contract 2.3；未确认单独用警告底色，不被当作失败
function precheckRowClassName({ row }: { row: ReleasePrecheckInstanceResult }): string {
  const state = classifyPrecheck(row);
  if (state === 'FAIL') return 'precheck-failed-row';
  if (state === 'UNKNOWN') return 'precheck-unconfirmed-row';
  return '';
}

async function copyOutput() {
  if (!selectedOutput.value) {
    // 没有输出时明确告知，不静默丢弃
    ElMessage.warning(t('precheckDialog.noOutputToCopy'));
    return;
  }
  try {
    await navigator.clipboard.writeText(selectedOutput.value);
    ElMessage.success(t('precheckDialog.toastLogCopied'));
  } catch {
    ElMessage.error(t('precheckDialog.toastCopyFailed'));
  }
}

// T-C: Copy all failed records per Contract 2.3
async function copyFailedRecords() {
  const failedRecords = props.records.filter((r) => classifyPrecheck(r) === 'FAIL');
  if (failedRecords.length === 0) {
    ElMessage.warning(t('precheckDialog.toastNoFailedRecord'));
    return;
  }

  const lines = failedRecords.map((r) => {
    return [
      `${t('precheckDialog.copyLineHost')}${r.hostId}`,
      `${t('precheckDialog.copyLineInstance')}${r.instanceId}`,
      `${t('precheckDialog.copyLineArtifact')}${r.artifactUri}`,
      `${t('precheckDialog.copyLineError')}${r.errorMessage || t('precheckDialog.copyLineNone')}`,
      r.stdout ? `STDOUT:\n${r.stdout}` : '',
      r.stderr ? `STDERR:\n${r.stderr}` : '',
      '---',
    ].filter(Boolean).join('\n');
  });

  const text = `${t('precheckDialog.copyHeader', { count: failedRecords.length })}\n\n${lines.join('\n')}`;

  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(t('precheckDialog.toastFailedRecordsCopied'));
  } catch {
    ElMessage.error(t('precheckDialog.toastCopyFailed'));
  }
}

// V-B: Structured summary helper functions
// 状态词表：已知状态用词表，未知状态保留原始值并显式标注，绝不默认成功
function getOverallStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    SUCCESS: t('precheckDialog.statusAllPassed'),
    FAILED: t('precheckDialog.statusHasFailed'),
    PARTIAL: t('precheckDialog.statusPartial'),
    UNKNOWN: t('precheckDialog.statusUnknown'),
  };
  return labels[status] || (status ? t('precheckDialog.statusUnknownRaw', { status }) : status);
}

function getHostStatusTagType(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    case 'PARTIAL': return 'warning';
    default: return 'info';
  }
}

function getHostStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    SUCCESS: t('precheckDialog.hostStatusSuccess'),
    FAILED: t('precheckDialog.hostStatusFailed'),
    PARTIAL: t('precheckDialog.hostStatusPartial'),
    UNKNOWN: t('precheckDialog.hostStatusUnknown'),
  };
  return labels[status] || (status ? t('precheckDialog.statusUnknownRaw', { status }) : status);
}

function getFailureCategoryTagType(category: string): string {
  switch (category) {
    case 'CONFIG': return 'warning';
    case 'CONNECTIVITY': return 'danger';
    case 'PERMISSION': return 'danger';
    case 'RESOURCE': return 'warning';
    case 'DEPENDENCY': return 'info';
    default: return 'info';
  }
}

// 失败分类不是单据状态，未知分类保留后端原始值
function getFailureCategoryLabel(category: string): string {
  const labels: Record<string, string> = {
    CONFIG: t('precheckDialog.failureCategoryConfig'),
    CONNECTIVITY: t('precheckDialog.failureCategoryConnectivity'),
    PERMISSION: t('precheckDialog.failureCategoryPermission'),
    RESOURCE: t('precheckDialog.failureCategoryResource'),
    DEPENDENCY: t('precheckDialog.failureCategoryDependency'),
    UNKNOWN: t('precheckDialog.failureCategoryUnknown'),
  };
  return labels[category] || category;
}

function getSuggestionTagType(type: string): string {
  switch (type) {
    case 'CONFIG_EDIT': return 'warning';
    case 'RETRY_PRECHECK': return 'primary';
    case 'VIEW_LOG': return 'info';
    case 'CONTACT_OWNER': return 'danger';
    case 'CHECK_INSTANCE': return 'warning';
    default: return 'info';
  }
}

// 建议类型同样保留后端原始值，不做二次改写
function getSuggestionTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    CONFIG_EDIT: t('precheckDialog.suggestionTypeConfigEdit'),
    RETRY_PRECHECK: t('precheckDialog.suggestionTypeRetryPrecheck'),
    VIEW_LOG: t('precheckDialog.suggestionTypeViewLog'),
    CONTACT_OWNER: t('precheckDialog.suggestionTypeContactOwner'),
    CHECK_INSTANCE: t('precheckDialog.suggestionTypeCheckInstance'),
  };
  return labels[type] || type;
}

function navigateToSuggestion(suggestion: PrecheckSuggestionItem) {
  if (suggestion.targetRoute) {
    router.push({
      path: suggestion.targetRoute,
      query: suggestion.targetParams as Record<string, string>,
    });
    emit('update:modelValue', false);
  }
}

watch(
  () => props.records,
  (records) => {
    selectedRecord.value = null;
    selectedHostId.value = null;
    // 默认展开所有“并非全部明确通过”的主机分组
    const attentionHostIds = hostGroups.value.filter((g) => !g.summary.allPassed).map((g) => g.hostId);
    expandedHosts.value = attentionHostIds.length > 0 ? attentionHostIds : [records[0]?.hostId].filter(Boolean);
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

.summary-error-bar {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  padding: var(--ih-spacing-3) var(--ih-spacing-sm);
  margin-bottom: var(--ih-section-gap);
  border: 1px solid var(--ih-warning);
  border-radius: var(--ih-radius-md);
  background: var(--ih-warning-bg);
  color: var(--ih-text-primary);
  font-size: 13px;
}

.summary-error-text {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.precheck-summary-bar {
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

.summary-unconfirmed {
  font-size: 13px;
  color: var(--ih-warning);
}

.precheck-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--ih-spacing-3);
  padding: var(--ih-spacing-xl) 0;
  color: var(--ih-text-secondary);
  font-size: 13px;
}

.precheck-groups {
  max-height: min(400px, 40vh);
  overflow: auto;
}

.host-collapse {
  border: none;
}

.host-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
  width: 100%;
  min-width: 0;
}

.host-name {
  font-weight: 500;
}

.host-summary {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.host-breakdown {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-left: auto;
}

.precheck-alert {
  margin-top: var(--ih-section-gap);
}

.precheck-log-panel {
  margin-top: var(--ih-section-gap);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  padding: var(--ih-spacing-sm);
  background: transparent;
}

.precheck-log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-3);
}

.precheck-log-title {
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

.precheck-log {
  min-height: 180px;
  max-height: min(320px, 28vh);
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
.precheck-log-nowrap {
  white-space: pre;
  overflow-wrap: normal;
  word-break: normal;
}

.no-output {
  min-height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--ih-border);
  border-radius: var(--ih-radius-md);
  color: var(--ih-text-placeholder);
  font-size: 13px;
}

.text-danger {
  color: var(--ih-danger);
  font-weight: 500;
}

// V-B: Structured summary styles
.structured-summary {
  margin-bottom: var(--ih-section-gap);
  padding: var(--space-sm) 0;
  border-top: var(--rule-hair) solid var(--color-rule);
  background-color: transparent;
}

.summary-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: var(--ih-spacing-sm);
  }
}

.host-summary-stats {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-sm);
}

.stat-text {
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.host-summary-table {
  width: 100%;
}

.step-count {
  font-size: 13px;
}

.step-success {
  color: var(--ih-success);
}

.step-failed {
  color: var(--ih-danger);
}

.failure-categories {
  display: flex;
  flex-direction: column;
  gap: var(--ih-spacing-3);
}

.failure-category-item {
  padding: var(--space-xs) var(--space-sm);
  border-top: var(--rule-hair) solid var(--color-rule);
  background-color: transparent;
}

.category-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-1);
}

.category-count {
  font-size: 13px;
  font-weight: 500;
  color: var(--ih-text-primary);
}

.category-message {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.suggestions-list {
  display: flex;
  flex-direction: column;
  gap: var(--ih-spacing-3);
}

.suggestion-item {
  padding: var(--space-xs) var(--space-sm);
  border-top: var(--rule-hair) solid var(--color-rule);
  background-color: transparent;
}

.suggestion-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-1);
}

.suggestion-title {
  font-weight: 500;
  font-size: 13px;
  color: var(--ih-text-primary);
}

.suggestion-desc {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-bottom: var(--ih-spacing-2);
}

.suggestion-action {
  margin-top: var(--ih-spacing-1);
}
</style>

<style lang="scss">
// 弹窗高度受控：滚动限制在表格与日志区域内
.precheck-dialog .el-dialog__body {
  max-height: calc(88vh - 150px);
  overflow: hidden;
}

// T-D: Failed row highlight (unscoped for el-table row class)
.el-table .precheck-failed-row {
  background-color: var(--ih-danger-bg);
}

// 未确认检查结果使用警告底色：仍需关注，但不谎称为失败
.el-table .precheck-unconfirmed-row {
  background-color: var(--ih-warning-bg);
}
</style>