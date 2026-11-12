<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('executeConfirm.releaseTitle')"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      class="warning-alert"
    >
      <template #title>
        <span class="warning-title">{{ t('executeConfirm.releaseWarning') }}</span>
      </template>
    </el-alert>

    <div class="summary-section">
      <div class="section-title">{{ t('executeConfirm.releaseSummaryTitle') }}</div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item :label="t('executeConfirm.releaseOrderNo')">
          <span class="highlight-value">{{ row?.orderNo || row?.id || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('executeConfirm.app')">{{ objectAppLabel }}</el-descriptions-item>
        <el-descriptions-item :label="t('executeConfirm.service')">{{ objectServiceLabel }}</el-descriptions-item>
        <el-descriptions-item :label="t('executeConfirm.env')">{{ envLabel }}</el-descriptions-item>
        <el-descriptions-item :label="t('executeConfirm.version')">
          <span class="highlight-value">{{ versionLabel }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('executeConfirm.executorType')">
          <el-tag :type="getExecutorTypeTagType(row?.executorType)" size="small">
            {{ executorTypeLabel }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 第二批：操作影响独立成块，明确对象、环境、版本与不可自动撤销的影响 -->
    <div class="impact-section">
      <div class="section-title">{{ t('executeConfirm.impactTitle') }}</div>
      <div class="impact-statement">{{ impactStatement }}</div>
    </div>

    <!-- V-B: Execution Context (Contract 4.2.5) -->
    <div v-if="contextLoading" class="context-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert
      v-else-if="contextError"
      type="info"
      show-icon
      :closable="false"
      class="context-error"
      :title="t('executeConfirm.contextError')"
    />
    <div v-else-if="executionContext" class="execution-context-section">
      <div class="section-title">{{ t('executeConfirm.contextTitle') }}</div>

      <!-- Config completeness & precheck status -->
      <div class="context-status-row">
        <div class="context-status-item">
          <span class="context-label">{{ t('executeConfirm.configComplete') }}</span>
          <el-tag :type="executionContext.deployConfigComplete ? 'success' : 'danger'" size="small">
            {{ executionContext.deployConfigComplete ? t('executeConfirm.complete') : t('executeConfirm.incomplete') }}
          </el-tag>
        </div>
        <div class="context-status-item">
          <span class="context-label">{{ t('executeConfirm.precheckStatus') }}</span>
          <el-tag :type="getPrecheckStatusTagType(executionContext.precheckStatus)" size="small">
            {{ getPrecheckStatusLabel(executionContext.precheckStatus) }}
          </el-tag>
        </div>
        <div class="context-status-item">
          <span class="context-label">{{ t('executeConfirm.riskLevel') }}</span>
          <el-tag :type="getRiskLevelTagType(executionContext.riskLevel)" size="small">
            {{ getRiskLevelLabel(executionContext.riskLevel) }}
          </el-tag>
        </div>
      </div>

      <!-- Risk items -->
      <div v-if="executionContext.risks && executionContext.risks.length > 0" class="risk-section">
        <div class="sub-title">{{ t('executeConfirm.riskTitle') }}</div>
        <div
          v-for="(risk, index) in executionContext.risks"
          :key="index"
          class="risk-item"
        >
          <el-alert
            :type="getRiskLevelAlertType(risk.level)"
            :closable="false"
            show-icon
            size="small"
          >
            <template #title>
              <span class="risk-message">{{ risk.message }}</span>
            </template>
            <template v-if="risk.suggestion" #default>
              <span class="risk-suggestion">{{ risk.suggestion }}</span>
            </template>
          </el-alert>
        </div>
      </div>

      <!-- Version comparison -->
      <div v-if="executionContext.versionCompare && executionContext.versionCompare.length > 0" class="version-compare-section">
        <div class="sub-title">{{ t('executeConfirm.versionCompareTitle') }}</div>
        <el-table :data="changedVersionItems" size="small" class="version-compare-table">
          <el-table-column prop="fieldName" :label="t('executeConfirm.configItem')" min-width="180" show-overflow-tooltip />
          <el-table-column :label="t('executeConfirm.currentVersion')" min-width="140">
            <template #default="{ row }">
              <span class="version-value current">{{ row.currentValue || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('executeConfirm.targetVersion')" min-width="140">
            <template #default="{ row }">
              <span class="version-value target">{{ row.targetValue || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="versionUnchangedCount > 0" class="version-unchanged-hint">
          {{ t('executeConfirm.versionUnchanged', { count: versionUnchangedCount }) }}
        </div>
      </div>
    </div>

    <el-checkbox v-model="confirmed" class="confirm-checkbox">
      {{ t('executeConfirm.confirmCheckbox') }}
    </el-checkbox>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('executeConfirm.cancel') }}</el-button>
      <el-button type="primary" :disabled="!confirmed" :loading="loading" @click="handleConfirm">
        {{ t('executeConfirm.submit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { getExecutionContext, type ExecutionContextResponse } from '@/api/release';

const props = defineProps<{
  modelValue: boolean;
  row: Record<string, unknown> | null;
  loading: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'confirm'): void;
}>();

const { t } = useI18n();
const confirmed = ref(false);
const executionContext = ref<ExecutionContextResponse | null>(null);
const contextLoading = ref(false);
// 读取失败与"没有上下文数据"分开表达：失败时给出原因，不伪装成无数据
const contextError = ref(false);

const changedVersionItems = computed(() => {
  return executionContext.value?.versionCompare?.filter(item => item.changed) ?? [];
});

const versionUnchangedCount = computed(() => {
  const total = executionContext.value?.versionCompare?.length ?? 0;
  return total - changedVersionItems.value.length;
});

// 对象 / 环境 / 版本：缺失时明确占位，不用「-」让人误以为没有信息
const objectAppLabel = computed(() => String(props.row?.appName ?? executionContext.value?.appName ?? '').trim() || t('executeConfirm.appFallback'));
const objectServiceLabel = computed(() => String(props.row?.serviceName ?? executionContext.value?.serviceName ?? '').trim() || t('executeConfirm.serviceFallback'));
const envLabel = computed(() => String(props.row?.envName ?? executionContext.value?.envName ?? '').trim() || t('executeConfirm.envFallback'));
const versionLabel = computed(() =>
  String(props.row?.versionNo ?? props.row?.versionDisplayName ?? executionContext.value?.targetVersionNo ?? '').trim()
    || t('executeConfirm.versionFallback'),
);
const objectLabel = computed(() => {
  const app = String(props.row?.appName ?? executionContext.value?.appName ?? '').trim();
  const service = String(props.row?.serviceName ?? executionContext.value?.serviceName ?? '').trim();
  if (!app && !service) return t('executeConfirm.objectFallback');
  return `${objectAppLabel.value} / ${objectServiceLabel.value}`;
});

// 操作影响：对象、环境、版本与不可自动撤销的后果，一次说清
const impactStatement = computed(() => t('executeConfirm.impactStatement', {
  env: envLabel.value,
  object: objectLabel.value,
  version: versionLabel.value,
}));

async function loadExecutionContext() {
  if (!props.row?.id) return;
  contextLoading.value = true;
  contextError.value = false;
  try {
    executionContext.value = await getExecutionContext(Number(props.row.id));
  } catch {
    executionContext.value = null;
    contextError.value = true;
  } finally {
    contextLoading.value = false;
  }
}

watch(() => props.modelValue, (val) => {
  if (val) {
    confirmed.value = false;
    executionContext.value = null;
    contextError.value = false;
    loadExecutionContext();
  }
});

function handleConfirm() {
  if (!confirmed.value) return;
  emit('confirm');
}

function getExecutorTypeTagType(type: string | undefined): string {
  switch (type) {
    case 'SSH': return 'info';
    case 'DOCKER': return 'success';
    case 'KUBERNETES': return 'warning';
    default: return 'info';
  }
}

// 执行器类型缺失时明确显示「未知」，不得回落到 SSH
const executorTypeLabel = computed(() => {
  const labels: Record<string, string> = {
    SSH: 'SSH',
    DOCKER: 'Docker',
    KUBERNETES: 'Kubernetes',
  };
  const type = String(props.row?.executorType ?? '').trim().toUpperCase();
  return type ? (labels[type] || type) : t('executeConfirm.executorUnknown');
});

function getPrecheckStatusTagType(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    case 'PARTIAL': return 'warning';
    default: return 'info';
  }
}

function getPrecheckStatusLabel(status: string): string {
  switch (status) {
    case 'SUCCESS': return t('executeConfirm.precheckSuccess');
    case 'FAILED': return t('executeConfirm.precheckFailed');
    case 'PARTIAL': return t('executeConfirm.precheckPartial');
    case 'UNKNOWN': return t('executeConfirm.unknown');
    default: return status;
  }
}

function getRiskLevelTagType(level: string): string {
  switch (level) {
    case 'LOW': return 'success';
    case 'MEDIUM': return 'warning';
    case 'HIGH': return 'danger';
    default: return 'info';
  }
}

function getRiskLevelLabel(level: string): string {
  switch (level) {
    case 'LOW': return t('executeConfirm.riskLow');
    case 'MEDIUM': return t('executeConfirm.riskMedium');
    case 'HIGH': return t('executeConfirm.riskHigh');
    case 'UNKNOWN': return t('executeConfirm.unknown');
    default: return level;
  }
}

function getRiskLevelAlertType(level: string): string {
  switch (level) {
    case 'LOW': return 'success';
    case 'MEDIUM': return 'warning';
    case 'HIGH': return 'error';
    default: return 'info';
  }
}
</script>

<style scoped lang="scss">
.warning-alert {
  margin-bottom: 16px;
}

.warning-title {
  font-weight: 500;
  color: var(--ih-warning);
}

.summary-section {
  margin-bottom: 16px;

  .section-title {
    font-weight: 500;
    font-size: 14px;
    color: var(--ih-text-primary);
    margin-bottom: 12px;
  }
}

.highlight-value {
  font-weight: 500;
  color: var(--ih-primary);
}

.confirm-checkbox {
  margin-top: 16px;
}

// 第二批：操作影响块（对象 / 环境 / 版本 / 后果）
.impact-section {
  margin-bottom: var(--space-md);
  padding: var(--space-sm) var(--space-md);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  background-color: transparent;

  .section-title {
    margin-bottom: var(--space-xs);
    color: var(--color-muted);
    font-size: var(--text-xs);
    font-weight: 500;
  }
}

.impact-statement {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ih-text-primary);
  overflow-wrap: anywhere;
}

.context-error {
  margin-bottom: 16px;
}

.context-loading {
  margin-bottom: 16px;
}

.execution-context-section {
  margin-bottom: var(--space-md);
  padding: var(--space-sm) 0;
  border-top: var(--rule-hair) solid var(--color-rule);
  background-color: transparent;

  .section-title {
    margin-bottom: var(--space-xs);
    color: var(--color-muted);
    font-size: var(--text-xs);
    font-weight: 500;
  }

  .sub-title {
    margin-bottom: var(--space-2xs);
    color: var(--color-ink);
    font-size: var(--text-sm);
    font-weight: 500;
  }
}

.context-status-row {
  display: flex;
  gap: var(--ih-spacing-sm);
  flex-wrap: wrap;
  margin-bottom: var(--ih-spacing-sm);
}

.context-status-item {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-2);
}

.context-label {
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.risk-section {
  margin-bottom: var(--ih-spacing-sm);
}

.risk-item {
  margin-bottom: var(--ih-spacing-3);
}

.risk-message {
  font-size: 13px;
}

.risk-suggestion {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.version-compare-section {
  margin-top: var(--ih-spacing-3);
}

.version-compare-table {
  width: 100%;
}

.version-value {
  font-size: 13px;

  &.current {
    color: var(--ih-text-secondary);
  }

  &.target {
    color: var(--ih-primary);
    font-weight: 500;
  }
}

.version-unchanged-hint {
  margin-top: var(--ih-spacing-2);
  font-size: 12px;
  color: var(--ih-text-placeholder);
}
</style>
