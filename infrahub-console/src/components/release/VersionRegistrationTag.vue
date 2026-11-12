<template>
  <div v-if="consistency" class="registration-consistency">
    <el-tooltip :content="tooltip" placement="top" :disabled="!tooltip">
      <el-tag :type="tagType" size="small">{{ label }}</el-tag>
    </el-tooltip>
    <div v-if="counts" class="registration-counts">{{ counts }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

/**
 * 当前**登记版本一致性**。与覆盖范围分开显示：这里只回答“当前这些实例在平台登记上
 * 分别是什么版本、是否都等于目标版本”，不回答“当时这次执行覆盖了谁”。
 * 只有 ALL_MATCH 才表述为当前全部一致，且它仍不是远端实际运行版本的探测结果。
 */
interface Props {
  consistency?: {
    status?: string;
    targetSourceType?: string;
    sourceOrderId?: number | null;
    sourceOrderNo?: string | null;
    sourceConsistent?: boolean;
    sourceRejectionReason?: string | null;
    targetVersionNo?: string | null;
    targetVersionId?: number | null;
    matchingCount?: number;
    currentInstanceCount?: number;
    statement?: string;
    disclaimer?: string;
  } | null;
}

const props = defineProps<Props>();
const { t } = useI18n();

const label = computed(() => {
  switch (props.consistency?.status) {
    case 'ALL_MATCH':
      return t('registrationConsistency.statusAllMatch');
    case 'SOME_DIFFER':
      return t('registrationConsistency.statusSomeDiffer');
    case 'NO_INSTANCES':
      return t('registrationConsistency.statusNoInstances');
    default:
      return t('registrationConsistency.statusUnknown');
  }
});

const tagType = computed(() => {
  switch (props.consistency?.status) {
    case 'ALL_MATCH':
      return 'success';
    case 'SOME_DIFFER':
      return 'warning';
    default:
      return 'info';
  }
});

/** 只有结论可确认时给数量；UNKNOWN 时给数量会被读成“已经核对过”。 */
const counts = computed(() => {
  const consistency = props.consistency;
  if (!consistency) return '';
  if (consistency.status !== 'ALL_MATCH' && consistency.status !== 'SOME_DIFFER') return '';
  const target = consistency.targetVersionNo
    ?? (consistency.targetVersionId ? `#${consistency.targetVersionId}` : '');
  return t('registrationConsistency.counts', {
    target,
    matching: consistency.matchingCount ?? 0,
    current: consistency.currentInstanceCount ?? 0,
  });
});

/** 目标版本与其来源必须和结论一起出现，避免只看到“一致”却不知道在跟哪个版本比。 */
const targetText = computed(() => {
  const consistency = props.consistency;
  if (!consistency) return '';
  const version = consistency.targetVersionNo
    ?? (consistency.targetVersionId ? `#${consistency.targetVersionId}` : '');
  return version ? `${t('registrationConsistency.targetVersion')}: ${version}` : '';
});

const sourceText = computed(() => {
  const consistency = props.consistency;
  if (!consistency) return '';
  const orderRef = consistency.sourceOrderNo
    ?? (consistency.sourceOrderId ? `#${consistency.sourceOrderId}` : '');
  switch (consistency.targetSourceType) {
    case 'RELEASE_ORDER':
    case 'CURRENT_VERSION_RELEASE':
      return orderRef
        ? t('registrationConsistency.sourceReleaseOrder', { orderNo: orderRef })
        : t('registrationConsistency.sourceNone');
    case 'CURRENT_VERSION_ROLLBACK':
      return orderRef
        ? t('registrationConsistency.sourceRollbackOrder', { orderNo: orderRef })
        : t('registrationConsistency.sourceNone');
    default:
      return t('registrationConsistency.sourceNone');
  }
});

const tooltip = computed(() => {
  const consistency = props.consistency;
  if (!consistency) return '';
  const sourceLabel = sourceText.value
    ? `${t('registrationConsistency.targetSource')}: ${sourceText.value}`
    : '';
  const parts = [
    targetText.value,
    sourceLabel,
    consistency.statement,
    consistency.sourceConsistent === false ? consistency.sourceRejectionReason : null,
    consistency.disclaimer,
  ].filter((part): part is string => typeof part === 'string' && part.length > 0);
  return parts.join('\n');
});
</script>

<style scoped>
.registration-consistency {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
}

.registration-counts {
  font-size: 12px;
  color: var(--ih-text-secondary);
}
</style>
