<template>
  <section class="menu-layout">
    <header class="page-heading">
      <div>
        <h2>{{ t('routes.menuLayout') }}</h2>
        <p>{{ t('menuLayout.description') }}</p>
      </div>
      <div class="actions">
        <el-button :disabled="loading || saving" @click="reload">{{ t('common.refresh') }}</el-button>
        <el-button :disabled="!ready || busy" @click="restoreDefaults">{{ t('menuLayout.restoreDefaults') }}</el-button>
        <el-button type="primary" :disabled="!ready || !dirty || busy || conflict || loadError" :loading="saving" @click="save">
          {{ t('common.save') }}
        </el-button>
      </div>
    </header>

    <el-alert v-if="loadError || conflict" :title="t(loadError ? 'menuLayout.loadError' : 'menuLayout.conflict')" type="error" :closable="false" show-icon />
    <div v-if="ready" class="layout-editor">
      <nav class="panel group-panel" :aria-label="t('menuLayout.groups')">
        <div class="panel-heading">
          <h3>{{ t('menuLayout.groups') }}</h3>
          <div class="actions">
            <el-button :icon="ArrowUp" :aria-label="t('menuLayout.groupUp')" :title="t('menuLayout.groupUp')" :disabled="busy || selectedIndex === 0" @click="moveGroup(-1)" />
            <el-button :icon="ArrowDown" :aria-label="t('menuLayout.groupDown')" :title="t('menuLayout.groupDown')" :disabled="busy || selectedIndex === draft.groupOrder.length - 1" @click="moveGroup(1)" />
          </div>
        </div>
        <button
          v-for="group in draft.groupOrder"
          :key="group"
          type="button"
          class="group-option"
          :aria-current="group === selectedGroup ? 'true' : undefined"
          @click="selectedGroup = group"
        >
          {{ t(menuGroupTitles[group]) }}
        </button>
      </nav>

      <section class="panel">
        <div class="panel-heading">
          <h3>{{ t(menuGroupTitles[selectedGroup]) }}</h3>
          <span v-if="dirty" class="status" role="status">{{ t('menuLayout.unsaved') }}</span>
        </div>
        <el-table :data="entries" row-key="path" :empty-text="t('menuLayout.emptyGroup')">
          <el-table-column :label="t('menuLayout.page')" min-width="185">
            <template #default="{ row }">{{ titleFor(row.path) }}</template>
          </el-table-column>
          <el-table-column :label="t('menuLayout.group')" min-width="170">
            <template #default="{ row }">
              <span v-if="row.path === menuLayoutPath">{{ t(menuGroupTitles.system) }}</span>
              <el-select v-else :model-value="row.group" :disabled="busy" :aria-label="t('menuLayout.groupFor', { name: titleFor(row.path) })" @change="moveToGroup(row, $event)">
                <el-option v-for="group in draft.groupOrder" :key="group" :value="group" :label="t(menuGroupTitles[group])" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('menuLayout.visibility')" min-width="115">
            <template #default="{ row }">
              <span v-if="row.path === menuLayoutPath">{{ t('menuLayout.alwaysVisible') }}</span>
              <el-switch v-else v-model="row.visible" :disabled="busy" :aria-label="t('menuLayout.visibilityFor', { name: titleFor(row.path) })" />
            </template>
          </el-table-column>
          <el-table-column :label="t('menuLayout.order')" width="112">
            <template #default="{ row, $index }">
              <el-button :icon="ArrowUp" :aria-label="t('menuLayout.pageUp', { name: titleFor(row.path) })" :title="t('menuLayout.up')" :disabled="busy || $index === 0" @click="moveEntry(row, -1)" />
              <el-button :icon="ArrowDown" :aria-label="t('menuLayout.pageDown', { name: titleFor(row.path) })" :title="t('menuLayout.down')" :disabled="busy || $index === entries.length - 1" @click="moveEntry(row, 1)" />
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
    <p v-else-if="loading" role="status">{{ t('common.loading') }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ArrowDown, ArrowUp } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { isAxiosError } from 'axios';
import {
  menuGroupTitles, menuLayoutPath, resolveMenuLayout, type MenuEntry, type MenuGroup, type MenuLayout,
} from '@/router/navigation';
import { defaultMenuLayout, menuRoutes } from '@/router/routes';
import { useMenuLayoutStore } from '@/store/menuLayout';

const { t } = useI18n();
const menu = useMenuLayoutStore();
const selectedGroup = ref<MenuGroup>('workspace');
const draft = ref<MenuLayout>(resolveMenuLayout(defaultMenuLayout));
const baseline = ref('');
const revision = ref(0);
const ready = ref(false);
const loading = ref(false);
const saving = ref(false);
const loadError = ref(false);
const conflict = ref(false);
const busy = computed(() => loading.value || saving.value);
const entries = computed(() => draft.value.entries.filter(({ group }) => group === selectedGroup.value));
const selectedIndex = computed(() => draft.value.groupOrder.indexOf(selectedGroup.value));
const dirty = computed(() => ready.value && JSON.stringify(draft.value) !== baseline.value);

function titleFor(path: string) {
  const key = menuRoutes.get(path)?.meta.title;
  return key ? t(key) : path;
}

function moveGroup(offset: number) {
  const from = selectedIndex.value;
  const to = from + offset;
  const order = draft.value.groupOrder;
  [order[from], order[to]] = [order[to], order[from]];
}

function moveEntry(entry: MenuEntry, offset: number) {
  const neighbor = entries.value[entries.value.indexOf(entry) + offset];
  const all = draft.value.entries;
  const from = all.indexOf(entry);
  const to = all.indexOf(neighbor);
  [all[from], all[to]] = [all[to], all[from]];
}

function moveToGroup(entry: MenuEntry, group: MenuGroup) {
  const all = draft.value.entries;
  all.splice(all.indexOf(entry), 1);
  all.push({ ...entry, group });
}

function applySaved(nextRevision: number, layout: MenuLayout) {
  revision.value = nextRevision;
  draft.value = resolveMenuLayout(defaultMenuLayout, layout);
  baseline.value = JSON.stringify(draft.value);
  conflict.value = false;
  ready.value = true;
}

async function confirmDiscard() {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(t('menuLayout.discard'), { type: 'warning' });
    return true;
  } catch {
    return false;
  }
}

async function reload() {
  if (!await confirmDiscard()) return;
  loading.value = true;
  loadError.value = false;
  try {
    const saved = await menu.load();
    applySaved(saved.revision, saved.layout);
  } catch {
    loadError.value = true;
  } finally {
    loading.value = false;
  }
}

function restoreDefaults() {
  draft.value = resolveMenuLayout(defaultMenuLayout);
}

async function save() {
  saving.value = true;
  try {
    const isDefault = JSON.stringify(draft.value) === JSON.stringify(defaultMenuLayout);
    const saved = await menu.save(revision.value, isDefault ? { groupOrder: [], entries: [] } : draft.value);
    applySaved(saved.revision, saved.layout);
    ElMessage.success(t('menuLayout.saved'));
  } catch (error) {
    conflict.value = isAxiosError(error) && error.response?.status === 409;
  } finally {
    saving.value = false;
  }
}

onMounted(reload);
onBeforeRouteLeave(() => !saving.value && confirmDiscard());
</script>

<style scoped lang="scss">
.menu-layout {
  display: grid;
  gap: var(--space-md);
}

.page-heading,
.panel-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.page-heading h2,
.panel-heading h3 {
  margin: 0;
}

.page-heading p {
  margin: var(--space-2xs) 0 0;
  color: var(--color-ink-2);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.actions :deep(.el-button),
:deep(.el-table .el-button) {
  margin-left: 0;
}

.panel-heading :deep(.el-button),
:deep(.el-table .el-button) {
  padding-inline: 7px;
}

.layout-editor {
  display: grid;
  grid-template-columns: minmax(200px, 230px) minmax(0, 1fr);
  align-items: start;
  gap: var(--space-md);
}

.panel {
  min-width: 0;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-card);
  background: var(--color-paper);
  overflow: hidden;
}

.panel-heading {
  padding: var(--space-sm) var(--space-md);
  border-bottom: var(--rule-hair) solid var(--color-rule);
}

.group-panel {
  padding-bottom: var(--space-xs);
}

.group-option {
  display: block;
  width: calc(100% - 2 * var(--space-xs));
  margin: var(--space-xs) auto 0;
  padding: var(--space-xs) var(--space-sm);
  border: 0;
  border-radius: var(--radius-field);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.group-option:hover,
.group-option[aria-current] {
  background: var(--color-rail-2);
}

.group-option[aria-current] {
  box-shadow: inset 3px 0 var(--color-rail-accent);
  font-weight: 600;
}

.status {
  color: var(--color-ink-2);
}

@media (max-width: 760px) {
  .layout-editor { grid-template-columns: minmax(0, 1fr); }
}
</style>
