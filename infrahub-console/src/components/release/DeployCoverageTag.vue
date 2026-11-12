<template>
  <div v-if="coverage" class="deploy-coverage">
    <el-tooltip :content="tooltip" placement="top" :disabled="!tooltip">
      <el-tag :type="tagType" size="small">{{ label }}</el-tag>
    </el-tooltip>
    <div v-if="counts" class="coverage-counts">{{ counts }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';

/**
 * 一次执行的**实例覆盖范围**，按后端给出的 verdict 显示，页面不自行推断。
 * FULL 只表示“本次执行覆盖了当前实例集合的全部实例”，不表示这些实例现在仍登记在该版本；
 * 当前登记版本是否一致由 VersionRegistrationTag 单独显示。PARTIAL 明确写出未覆盖的实例数；
 * UNPROVABLE / NOT_STARTED 不给任何覆盖结论。statement 与 nextStep 都取自后端，
 * 避免页面把“无法证明”重新表述成“已部署”。
 */
interface Props {
  coverage?: {
    verdict?: string;
    evidenceProvable?: boolean;
    rejectionReason?: string | null;
    sourceType?: string | null;
    deployedCount?: number;
    uncoveredCount?: number;
    unfinishedCount?: number;
    currentInstanceCount?: number;
    statement?: string;
    nextStep?: string;
    disclaimer?: string;
  } | null;
}

const props = defineProps<Props>();
const { t } = useI18n();

const label = computed(() => {
  switch (props.coverage?.verdict) {
    case 'FULL':
      return t('deployCoverage.verdictFull');
    case 'PARTIAL':
      return t('deployCoverage.verdictPartial');
    case 'UNPROVABLE':
      return t('deployCoverage.verdictUnprovable');
    case 'NOT_STARTED':
      return t('deployCoverage.verdictNotStarted');
    default:
      return t('deployCoverage.verdictUnknown');
  }
});

const tagType = computed(() => {
  switch (props.coverage?.verdict) {
    case 'FULL':
      return 'success';
    case 'PARTIAL':
      return 'warning';
    default:
      return 'info';
  }
});

/** 只有可证明的两种结论才给数量：不可证明时给数量会被读成“覆盖了这些实例”。 */
const counts = computed(() => {
  const coverage = props.coverage;
  if (!coverage?.evidenceProvable) return '';
  if ((coverage.unfinishedCount ?? 0) > 0) {
    return t('deployCoverage.countsWithUnfinished', {
      deployed: coverage.deployedCount ?? 0,
      current: coverage.currentInstanceCount ?? 0,
      unfinished: coverage.unfinishedCount ?? 0,
    });
  }
  return t('deployCoverage.counts', {
    deployed: coverage.deployedCount ?? 0,
    current: coverage.currentInstanceCount ?? 0,
  });
});

const tooltip = computed(() => {
  const coverage = props.coverage;
  if (!coverage) return '';
  const parts = [coverage.statement, coverage.nextStep, coverage.disclaimer].filter(
    (part): part is string => typeof part === 'string' && part.length > 0,
  );
  return parts.join('\n');
});
</script>

<style scoped>
.deploy-coverage {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
}

.coverage-counts {
  font-size: 12px;
  color: var(--ih-text-secondary);
}
</style>
