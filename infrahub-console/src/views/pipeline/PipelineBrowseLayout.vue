<template>
  <div class="pipeline-browse">
    <aside class="catalog" :aria-label="title">
      <h2>{{ title }}</h2>
      <el-input v-model="search" :aria-label="searchLabel" :placeholder="searchLabel" clearable />
      <button class="catalog-all" type="button" :aria-pressed="selected == null" @click="emit('select', null)">
        {{ allLabel }}
      </button>
      <div v-if="loading" class="catalog-message" role="status">{{ t('common.loading') }}</div>
      <div v-else-if="failed" class="catalog-message" role="alert">
        <span>{{ t('pipelineBrowse.loadFailed') }}</span>
        <el-button link type="primary" @click="emit('retry')">{{ t('common.refresh') }}</el-button>
      </div>
      <el-tree
        v-else
        ref="tree"
        class="catalog-tree"
        :data="visibleNodes"
        :empty-text="t('common.noData')"
        node-key="key"
        :current-node-key="selected == null ? undefined : String(selected)"
        :highlight-current="selected != null"
        :expand-on-click-node="true"
        default-expand-all
        @node-click="selectNode"
      >
        <template #default="{ data }">
          <span class="catalog-node" :title="data.detail || data.label">{{ data.label }}</span>
        </template>
      </el-tree>
    </aside>
    <div class="browse-table"><slot /></div>
  </div>
</template>

<script lang="ts">
export interface BrowseNode {
  key: string;
  id?: number;
  label: string;
  detail?: string;
  children?: BrowseNode[];
}
</script>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { ElTree } from 'element-plus';

const props = defineProps<{
  title: string;
  allLabel: string;
  searchLabel: string;
  nodes: BrowseNode[];
  selected: number | null;
  loading: boolean;
  failed: boolean;
}>();
const emit = defineEmits<{ select: [id: number | null]; retry: [] }>();
const { t } = useI18n();
const search = ref('');
const tree = ref<InstanceType<typeof ElTree>>();

const visibleNodes = computed(() => {
  const query = search.value.trim().toLowerCase();
  if (!query) return props.nodes;
  const matches = (node: BrowseNode) => `${node.label} ${node.detail ?? ''}`.toLowerCase().includes(query);
  return props.nodes.flatMap(node => {
    if (matches(node)) return [node];
    const children = node.children?.filter(matches);
    return children?.length ? [{ ...node, children }] : [];
  });
});

function selectNode(node: BrowseNode) {
  if (node.id != null) {
    emit('select', node.id);
    return;
  }
  tree.value?.setCurrentKey(props.selected == null ? null : String(props.selected), false);
}
</script>

<style scoped>
.pipeline-browse {
  display: grid;
  grid-template-columns: minmax(14rem, 16rem) minmax(0, 1fr);
  gap: var(--space-md);
  align-items: start;
}
.browse-table { min-width: 0; }
.catalog {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-md);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
  min-width: 0;
}
.catalog h2 { margin: 0 0 var(--space-2xs); font: var(--ih-font-page-title); }
.catalog-all {
  text-align: left;
  padding: 8px 10px;
  color: var(--color-ink);
  background: transparent;
  border: 0;
  border-radius: var(--radius-field);
  font: inherit;
  cursor: pointer;
}
.catalog-all[aria-pressed='true'] {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.catalog-all:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.catalog-tree { background: transparent; max-height: 65vh; overflow: auto; }
.catalog-node { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.catalog-message { color: var(--color-muted); padding: var(--space-2xs) 0; }
@media (max-width: 960px) {
  .pipeline-browse { grid-template-columns: minmax(0, 1fr); }
  .catalog-tree { max-height: 34vh; }
}
</style>
