<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('executeConfirm.rollbackTitle')"
    width="680px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="supportBlocksSubmit"
      :type="rollbackSupportState === 'UNSUPPORTED' ? 'error' : 'warning'"
      show-icon
      :closable="false"
      class="warning-alert"
    >
      <template #title>
        {{ rollbackSupportState === 'UNSUPPORTED' ? t('rollbackSupport.alertUnsupportedTitle') : t('rollbackSupport.alertUnknownTitle') }}
      </template>
      <div class="unsupported-detail">{{ supportHint }}</div>
    </el-alert>
    <el-alert
      v-else
      type="warning"
      show-icon
      :closable="false"
      class="warning-alert"
    >
      <template #title>
        {{ t('executeConfirm.releaseWarning') }}
      </template>
    </el-alert>

    <div class="summary-title">{{ t('executeConfirm.rollbackSummaryTitle') }}</div>
    <el-descriptions :column="1" border size="small" class="info-summary">
      <el-descriptions-item :label="t('executeConfirm.rollbackNo')">
        <span class="highlight-value">{{ rollbackNo || '-' }}</span>
      </el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.app')">{{ objectAppLabel }}</el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.service')">{{ objectServiceLabel }}</el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.env')">{{ envLabel }}</el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.fromVersion')">{{ fromVersionLabel }}</el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.toVersion')">
        <span class="highlight-value">{{ toVersionLabel }}</span>
      </el-descriptions-item>
      <el-descriptions-item :label="t('executeConfirm.executorType')">
        <el-tag type="info" size="small">{{ executorTypeLabel }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <!-- 第二批：操作影响独立成块，明确对象、环境、版本区间与不可自动撤销的影响 -->
    <div class="impact-section">
      <div class="section-title">{{ t('executeConfirm.impactTitle') }}</div>
      <div class="impact-statement">{{ impactStatement }}</div>
    </div>

    <!-- V-C: Execution context section -->
    <div v-if="ctxLoading" class="ctx-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('executeConfirm.contextLoading') }}</span>
    </div>

    <el-alert
      v-else-if="ctxError"
      type="info"
      show-icon
      :closable="false"
      class="ctx-error"
      :title="t('executeConfirm.contextError')"
    />

    <template v-else-if="ctx">
      <!-- Config completeness + Precheck status + Risk level -->
      <div class="ctx-status-row">
        <div class="ctx-status-item">
          <span class="ctx-label">{{ t('executeConfirm.configComplete') }}</span>
          <el-tag :type="ctx.deployConfigComplete ? 'success' : 'danger'" size="small">
            {{ ctx.deployConfigComplete ? t('executeConfirm.complete') : t('executeConfirm.incomplete') }}
          </el-tag>
        </div>
        <div class="ctx-status-item">
          <span class="ctx-label">{{ t('executeConfirm.precheckStatus') }}</span>
          <el-tag :type="precheckTagType" size="small">{{ precheckLabel }}</el-tag>
        </div>
        <div class="ctx-status-item">
          <span class="ctx-label">{{ t('executeConfirm.riskLevel') }}</span>
          <el-tag :type="riskTagType" size="small">{{ riskLabel }}</el-tag>
        </div>
      </div>

      <!-- Risk items -->
      <div v-if="ctx.risks.length > 0" class="ctx-risk-section">
        <div class="ctx-section-title">{{ t('executeConfirm.riskTitle') }}</div>
        <div
          v-for="(risk, idx) in ctx.risks"
          :key="idx"
          class="ctx-risk-item"
          :class="`risk-${risk.level.toLowerCase()}`"
        >
          <el-icon><WarningFilled /></el-icon>
          <span class="risk-message">{{ risk.message }}</span>
          <span v-if="risk.suggestion" class="risk-suggestion">{{ risk.suggestion }}</span>
        </div>
      </div>

      <!-- Version comparison -->
      <div v-if="ctx.versionCompare.length > 0" class="ctx-version-section">
        <div class="ctx-section-title">{{ t('executeConfirm.rollbackVersionCompareTitle') }}</div>
        <el-table :data="ctx.versionCompare" size="small" :row-class-name="versionRowClassName">
          <el-table-column prop="fieldName" :label="t('executeConfirm.configItem')" min-width="200" show-overflow-tooltip />
          <el-table-column :label="t('executeConfirm.currentValue')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.currentValue || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('executeConfirm.targetValue')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.targetValue || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('executeConfirm.changed')" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.changed" type="warning" size="small">{{ t('executeConfirm.changed') }}</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <div class="manual-only-hint">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ t('executeConfirm.rollbackManualHint') }}</span>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t('executeConfirm.cancel') }}</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="isSubmitBlocked"
        @click="confirmExecute"
      >
        {{ t('executeConfirm.submit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { WarningFilled, Loading } from '@element-plus/icons-vue';
import { getRollbackExecutionContext, type ExecutionContextResponse } from '@/api/release';

const props = defineProps<{
  modelValue: boolean;
  rollbackId?: number | null;
  rollbackNo?: string;
  appName?: string;
  serviceName?: string;
  envName?: string;
  fromVersionNo?: string;
  toVersionNo?: string;
  executorType?: string | null;
  /** 统一回滚支持判定：仅 'SUPPORTED' 允许提交；null/缺省视为无法确定，一并阻断 */
  rollbackSupportState?: 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN' | null;
  /** 与列表动作入口共用的阻断提示（不支持或无法确定支持状态的原因） */
  supportHint?: string;
  submitting?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'confirm'): void;
}>();

const { t } = useI18n();
const ctx = ref<ExecutionContextResponse | null>(null);
const ctxLoading = ref(false);
// 读取失败与"没有上下文数据"分开表达：失败时给出原因，不伪装成无数据
const ctxError = ref(false);

const executorTypeLabel = computed(() => {
  const labels: Record<string, string> = {
    SSH: 'SSH',
    DOCKER: 'Docker',
    KUBERNETES: 'Kubernetes',
  };
  const type = (props.executorType ?? '').trim().toUpperCase();
  // 类型缺失时明确显示“未知”，不得把非 SSH 或类型不明的回滚单展示为 SSH
  return type ? labels[type] || type : t('executeConfirm.executorUnknown');
});

// 对象 / 环境 / 版本：缺失时明确占位，不用「-」让人误以为没有信息
const objectAppLabel = computed(() => String(ctx.value?.appName ?? props.appName ?? '').trim() || t('executeConfirm.appFallback'));
const objectServiceLabel = computed(() => String(ctx.value?.serviceName ?? props.serviceName ?? '').trim() || t('executeConfirm.serviceFallback'));
const envLabel = computed(() => String(ctx.value?.envName ?? props.envName ?? '').trim() || t('executeConfirm.envFallback'));
const fromVersionLabel = computed(() => String(props.fromVersionNo ?? '').trim() || t('executeConfirm.versionFallback'));
const toVersionLabel = computed(() => String(ctx.value?.targetVersionNo ?? props.toVersionNo ?? '').trim() || t('executeConfirm.versionFallback'));
const objectLabel = computed(() => {
  const app = String(ctx.value?.appName ?? props.appName ?? '').trim();
  const service = String(ctx.value?.serviceName ?? props.serviceName ?? '').trim();
  if (!app && !service) return t('executeConfirm.objectFallback');
  return `${objectAppLabel.value} / ${objectServiceLabel.value}`;
});

// 操作影响：对象、环境、版本区间与不可自动撤销的后果，一次说清
const impactStatement = computed(() => t('executeConfirm.rollbackImpactStatement', {
  env: envLabel.value,
  object: objectLabel.value,
  from: fromVersionLabel.value,
  to: toVersionLabel.value,
}));

// 与动作入口（RollbackOrdersView.ensureRollbackSupported）一致的判据：非 'SUPPORTED' 一律阻断
const supportBlocksSubmit = computed(() => props.rollbackSupportState !== 'SUPPORTED');

const isSubmitBlocked = computed(
  () => supportBlocksSubmit.value || (!!ctx.value && ctx.value.riskLevel === 'HIGH'),
);

const precheckTagType = computed(() => {
  if (!ctx.value) return 'info';
  const map: Record<string, string> = { SUCCESS: 'success', FAILED: 'danger', PARTIAL: 'warning', UNKNOWN: 'info' };
  return map[ctx.value.precheckStatus] || 'info';
});

const precheckLabel = computed(() => {
  if (!ctx.value) return '-';
  const map: Record<string, string> = {
    SUCCESS: t('executeConfirm.precheckSuccess'),
    FAILED: t('executeConfirm.precheckFailed'),
    PARTIAL: t('executeConfirm.precheckPartial'),
    UNKNOWN: t('executeConfirm.unknown'),
  };
  return map[ctx.value.precheckStatus] || ctx.value.precheckStatus;
});

const riskTagType = computed(() => {
  if (!ctx.value) return 'info';
  const map: Record<string, string> = { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger', UNKNOWN: 'info' };
  return map[ctx.value.riskLevel] || 'info';
});

const riskLabel = computed(() => {
  if (!ctx.value) return '-';
  const map: Record<string, string> = {
    LOW: t('executeConfirm.riskLow'),
    MEDIUM: t('executeConfirm.riskMedium'),
    HIGH: t('executeConfirm.riskHigh'),
    UNKNOWN: t('executeConfirm.unknown'),
  };
  return map[ctx.value.riskLevel] || ctx.value.riskLevel;
});

function versionRowClassName({ row }: { row: { changed: boolean } }) {
  return row.changed ? 'ctx-version-changed-row' : '';
}

async function loadContext() {
  if (!props.rollbackId) return;
  ctxLoading.value = true;
  ctx.value = null;
  ctxError.value = false;
  try {
    ctx.value = await getRollbackExecutionContext(props.rollbackId);
  } catch {
    // 读取失败需与“没有上下文”区分：给出原因提示，仍不阻断人工确认
    ctxError.value = true;
  } finally {
    ctxLoading.value = false;
  }
}

function confirmExecute() {
  // 与按钮 disabled 共用同一判据：不支持/无法确定支持状态或 HIGH 风险，一律不提交（后端仍为最终门禁）
  if (isSubmitBlocked.value) return;
  emit('confirm');
}

watch(
  () => props.modelValue,
  (val) => {
    if (val) {
      loadContext();
    } else {
      ctx.value = null;
      ctxError.value = false;
    }
  },
);
</script>

<style scoped lang="scss">
.warning-alert {
  margin-bottom: var(--ih-section-gap);
}

.unsupported-detail {
  margin-top: var(--ih-spacing-3);
  font-size: 13px;
  line-height: 1.5;
}

.info-summary {
  margin-bottom: var(--ih-section-gap);
}

// 第二批：汇总标题、关键值强调与操作影响块
.summary-title {
  font-weight: 500;
  font-size: 14px;
  color: var(--ih-text-primary);
  margin-bottom: var(--ih-spacing-sm);
}

.highlight-value {
  font-weight: 500;
  color: var(--ih-primary);
}

.impact-section {
  margin-bottom: var(--ih-section-gap);
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

.ctx-error {
  margin-bottom: var(--ih-section-gap);
}

.ctx-loading {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-sm);
  padding: var(--ih-spacing-sm);
  color: var(--ih-text-secondary);
  font-size: 13px;
}

.ctx-status-row {
  display: flex;
  gap: var(--ih-spacing-lg);
  margin-bottom: var(--ih-section-gap);
  padding: var(--ih-spacing-sm);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: transparent;
}

.ctx-status-item {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.ctx-label {
  font-size: 13px;
  color: var(--ih-text-secondary);
}

.ctx-section-title {
  font-weight: 500;
  font-size: 14px;
  margin-bottom: var(--ih-spacing-sm);
}

.ctx-risk-section {
  margin-bottom: var(--ih-section-gap);
}

.ctx-risk-item {
  display: flex;
  align-items: flex-start;
  gap: var(--ih-spacing-3);
  padding: var(--ih-spacing-3);
  border-radius: var(--ih-radius-sm);
  margin-bottom: var(--ih-spacing-3);
  font-size: 13px;
}

.ctx-risk-item.risk-high {
  background: var(--ih-danger-bg);
  color: var(--ih-danger);
}

.ctx-risk-item.risk-medium {
  background: var(--ih-warning-bg);
  color: var(--ih-warning);
}

.ctx-risk-item.risk-low {
  background: var(--ih-success-bg);
  color: var(--ih-success);
}

.risk-message {
  flex: 1;
}

.risk-suggestion {
  font-size: 12px;
  color: var(--ih-text-secondary);
  white-space: nowrap;
}

.ctx-version-section {
  margin-bottom: var(--ih-section-gap);
}

.manual-only-hint {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  padding: var(--space-2xs) var(--space-sm);
  border: var(--rule-hair) solid var(--color-accent-soft-2);
  border-radius: var(--radius-field);
  background: var(--color-accent-soft);
  font-size: var(--text-sm);
  color: var(--color-accent);
}
</style>

<style lang="scss">
// V-C: Changed version row highlight (unscoped for el-table row class)
.el-table .ctx-version-changed-row {
  background-color: var(--ih-warning-bg);
}
</style>
