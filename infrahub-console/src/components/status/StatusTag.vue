<template>
  <span class="status-cell" :data-status-code="descriptor.code" :title="title">
    <el-tag
      v-if="descriptor.code"
      :type="descriptor.tone"
      :effect="descriptor.unconfirmed ? 'plain' : 'dark'"
      size="small"
    >
      {{ statusLabel(descriptor, t) }}
    </el-tag>
    <span v-else class="status-cell__empty">{{ emptyText }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  describeStatus,
  statusLabel,
  statusTitle,
  type StatusKind,
} from '@/utils/statusSemantics';

const props = defineProps<{
  kind: StatusKind;
  /** 原始状态码，逐字展示；空值表示没有这条记录 */
  code?: string | null;
  /** 没有记录时的文案，默认「未记录」 */
  emptyText?: string;
}>();

const { t } = useI18n();

const descriptor = computed(() => describeStatus(props.kind, props.code));
const title = computed(() => statusTitle(descriptor.value, t));
const emptyText = computed(() => props.emptyText ?? t('statusSemantics.notRecorded'));
</script>

<style scoped>
/* 状态单元格：标签 + 原始状态码入口（title 与 data-status-code 都在这一层，不依赖 el-tag 透传属性） */
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-1);
  max-width: 100%;
}

.status-cell__empty {
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-sm);
}
</style>
