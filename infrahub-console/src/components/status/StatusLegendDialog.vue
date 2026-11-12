<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('statusSemantics.legendTitle')"
    width="760px"
    class="status-legend-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="legend-intro">{{ t('statusSemantics.legendIntro') }}</p>

    <section v-for="kind in kinds" :key="kind" class="legend-kind">
      <h4 class="legend-kind__name">{{ t(STATUS_KIND_META[kind].nameKey) }}</h4>
      <p class="legend-kind__source">
        <span class="legend-kind__label">{{ t('statusSemantics.dataSourceLabel') }}</span>
        {{ t(STATUS_KIND_META[kind].sourceKey) }}
      </p>
      <el-table :data="listStatusValues(kind)" size="small" class="legend-table">
        <el-table-column :label="t('statusSemantics.columnValue')" width="190">
          <template #default="{ row }">
            <el-tag :type="row.tone" :effect="row.unconfirmed ? 'plain' : 'dark'" size="small">
              {{ statusLabel(row, t) }}
            </el-tag>
            <span class="legend-code">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('statusSemantics.columnMeaning')">
          <template #default="{ row }">
            {{ row.noteKey ? t(row.noteKey) : '' }}
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-alert
      class="legend-note"
      type="info"
      :closable="false"
      show-icon
      :title="t('statusSemantics.legendNote')"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import {
  listStatusValues,
  statusLabel,
  STATUS_KIND_META,
  type StatusKind,
} from '@/utils/statusSemantics';

defineProps<{
  modelValue: boolean;
  kinds: StatusKind[];
}>();

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>();

const { t } = useI18n();
</script>

<style scoped>
.legend-intro {
  margin: 0 0 var(--ih-spacing-4);
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-sm);
  line-height: 1.6;
}

.legend-kind {
  margin-bottom: var(--ih-spacing-5);
}

.legend-kind__name {
  margin: 0 0 var(--ih-spacing-2);
  font-size: var(--ih-font-size-md);
  font-weight: 500;
  color: var(--ih-text-primary);
}

.legend-kind__source {
  margin: 0 0 var(--ih-spacing-2);
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-sm);
  line-height: 1.6;
}

.legend-kind__label {
  color: var(--ih-text-primary);
  font-weight: 500;
}

/* 原始状态码：中性色，跟在取值标签后面，随时可核对 */
.legend-code {
  margin-left: var(--ih-spacing-2);
  font-family: var(--ih-font-family-mono);
  font-size: var(--ih-font-size-xs);
  color: var(--ih-text-secondary);
}

.legend-note {
  margin-top: var(--ih-spacing-2);
}
</style>
