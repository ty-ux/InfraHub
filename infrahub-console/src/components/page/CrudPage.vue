<template>
  <section class="crud-page">
    <div class="page-toolbar">
      <div class="page-title">
        <h2>{{ config.title }}</h2>
        <p>{{ config.description }}</p>
      </div>
      <div class="toolbar-actions">
        <slot name="filters" />
        <slot name="toolbar-extra" />
        <el-button @click="fetchData()">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canCreate && config.fields?.length" type="primary" @click="openCreate">{{ t('common.create') }}</el-button>
      </div>
    </div>

    <div v-if="$slots['filter-panel']" class="filter-panel">
      <slot name="filter-panel" />
    </div>

    <slot name="before-table" />
    <component :is="$slots['table-summary'] ? 'details' : 'div'" class="crud-table-region">
      <summary v-if="$slots['table-summary']" class="crud-table-summary"><slot name="table-summary" /></summary>
    <div class="page-card content-card">
      <el-table
        :data="tableData"
        v-loading="loading"
        :row-key="rowKey"
        :empty-text="config.emptyText || t('common.noData')"
        :row-class-name="config.rowClassName"
        table-layout="fixed"
      >
        <el-table-column
          v-for="column in config.columns"
          :key="column.prop"
          :prop="column.prop"
          :label="column.label"
          :min-width="column.minWidth || 140"
          :width="column.width"
          :align="getColumnAlign(column)"
          :header-align="getColumnHeaderAlign(column)"
          :resizable="column.resizable !== false"
          :show-overflow-tooltip="column.overflowTooltip !== false"
        >
          <template #header>
            <el-tooltip v-if="column.headerTooltip" :content="column.headerTooltip" placement="top">
              <span>{{ column.label }}</span>
            </el-tooltip>
            <span v-else>{{ column.label }}</span>
          </template>
          <template #default="{ row }">
            <slot v-if="column.slot" :name="column.slot" :row="row" :value="row[column.prop]">
              {{ renderCell(column, row) }}
            </slot>
            <template v-else>
              {{ renderCell(column, row) }}
            </template>
          </template>
        </el-table-column>
        <el-table-column
          v-if="hasActions || $slots['row-actions']"
          :label="t('common.actions')"
          fixed="right"
          :width="config.actionColumnWidth || 200"
          align="center"
          header-align="center"
          resizable
        >
          <template #default="{ row }">
            <el-button v-if="canEdit" link type="primary" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button v-if="canDelete" link type="danger" @click="removeRow(row)">{{ t('common.delete') }}</el-button>
            <slot name="row-actions" :row="row" />
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          @current-change="fetchData()"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
    </component>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? `${t('common.edit')} ${config.title}` : `${t('common.create')} ${config.title}`"
      width="680px"
      @closed="resetDialog"
    >
      <el-form ref="formRef" :model="formState" label-width="110px" :rules="rules">
        <el-form-item v-for="field in config.fields || []" :key="field.prop" :label="field.label" :prop="field.prop">
          <el-input
            v-if="!field.type || field.type === 'text'"
            v-model="formState[field.prop]"
            :placeholder="field.placeholder || t('common.pleaseEnter', { label: field.label })"
          />
          <el-input
            v-else-if="field.type === 'textarea'"
            v-model="formState[field.prop]"
            :rows="4"
            type="textarea"
            :placeholder="field.placeholder || t('common.pleaseEnter', { label: field.label })"
          />
          <el-input-number
            v-else-if="field.type === 'number'"
            v-model="formState[field.prop]"
            :min="0"
            :controls="false"
            style="width: 100%"
          />
          <el-select
            v-else-if="field.type === 'select'"
            v-model="formState[field.prop]"
            :placeholder="field.placeholder || t('common.pleaseEnter', { label: field.label })"
            :loading="fieldLoading[field.prop]"
            clearable
            filterable
            fit-input-width
            style="width: 100%"
          >
            <el-option
              v-for="option in getFieldOptions(field)"
              :key="`${field.prop}-${option.value}`"
              :label="option.label"
              :value="option.value"
              :disabled="option.disabled"
            />
          </el-select>
          <el-switch v-else-if="field.type === 'switch'" v-model="formState[field.prop]" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { useI18n } from 'vue-i18n';
import type { CrudColumn, CrudField, CrudFieldOption, CrudPageConfig } from '@/types/page';
import { useAuthStore } from '@/store/auth';

const props = defineProps<{
  config: CrudPageConfig<Record<string, unknown>>;
}>();

const authStore = useAuthStore();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const tableData = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const submitLoading = ref(false);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const editingId = ref<number | null>(null);
const initializingDialog = ref(false);
const formState = reactive<Record<string, unknown>>({});
const fieldOptions = reactive<Record<string, CrudFieldOption[]>>({});
const fieldLoading = reactive<Record<string, boolean>>({});
let fetchSequence = 0;

const rowKey = computed(() => props.config.rowKey || 'id');
const canCreate = computed(() => Boolean(props.config.api.create) && authStore.canAccess(props.config.createAuthority));
const canEdit = computed(() => Boolean(props.config.api.update) && authStore.canAccess(props.config.editAuthority));
const canDelete = computed(() => Boolean(props.config.api.remove) && authStore.canAccess(props.config.deleteAuthority));
const hasActions = computed(() => canEdit.value || canDelete.value);

const rules = computed<FormRules>(() =>
  (props.config.fields || []).reduce((result, field) => {
    if (field.required) {
      result[field.prop] = [{ required: true, message: t('common.required', { label: field.label }), trigger: 'blur' }];
    }
    return result;
  }, {} as FormRules),
);

function resetDialog() {
  editingId.value = null;
  Object.keys(formState).forEach((key) => delete formState[key]);
  Object.assign(formState, props.config.defaultForm || {});
  Object.keys(fieldOptions).forEach((key) => delete fieldOptions[key]);
  Object.keys(fieldLoading).forEach((key) => delete fieldLoading[key]);
  formRef.value?.clearValidate();
}

async function openCreate() {
  resetDialog();
  dialogVisible.value = true;
  initializingDialog.value = true;
  try {
    await loadAllSelectOptions();
  } catch {
    dialogVisible.value = false;
  } finally {
    initializingDialog.value = false;
  }
}

async function openEdit(row: Record<string, unknown>) {
  resetDialog();
  editingId.value = Number(row[rowKey.value]);
  initializingDialog.value = true;
  Object.assign(formState, row);
  dialogVisible.value = true;
  try {
    await loadAllSelectOptions();
  } catch {
    dialogVisible.value = false;
  } finally {
    initializingDialog.value = false;
  }
}

function renderCell(column: CrudColumn, row: Record<string, unknown>) {
  const value = row[column.prop];
  return column.formatter ? column.formatter(value, row) : (value ?? '-');
}

function getColumnAlign(column: CrudColumn): 'left' | 'center' | 'right' {
  if (column.align) {
    return column.align;
  }
  if (/^(id|.*Id|.*Status|status|enabled|.*Count|.*Rate)$/i.test(column.prop)) {
    return 'center';
  }
  return 'left';
}

function getColumnHeaderAlign(column: CrudColumn): 'left' | 'center' | 'right' {
  return column.headerAlign || getColumnAlign(column);
}

async function fetchData({ silent = false } = {}) {
  if (silent && loading.value) return;
  const sequence = ++fetchSequence;
  if (!silent) loading.value = true;
  try {
    const page = await props.config.api.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      ...(props.config.extraQuery || {}),
    });
    if (sequence !== fetchSequence) {
      return;
    }
    tableData.value = page.records;
    total.value = page.total;
  } finally {
    if (sequence === fetchSequence) {
      loading.value = false;
    }
  }
}

function getFieldOptions(field: CrudField) {
  return fieldOptions[field.prop] || field.options || [];
}

async function loadFieldOptions(field: CrudField) {
  if (field.type !== 'select') {
    return;
  }

  if (!field.loadOptions) {
    fieldOptions[field.prop] = field.options || [];
    return;
  }

  fieldLoading[field.prop] = true;
  try {
    const options = await field.loadOptions({ ...formState });
    fieldOptions[field.prop] = options;
    if (formState[field.prop] != null && !options.some((option) => option.value === formState[field.prop])) {
      formState[field.prop] = undefined;
    }
  } finally {
    fieldLoading[field.prop] = false;
  }
}

async function loadAllSelectOptions() {
  const fields = props.config.fields || [];
  await Promise.all(fields.filter((field) => field.type === 'select').map((field) => loadFieldOptions(field)));
}

async function submit() {
  if (!props.config.fields?.length) {
    return;
  }
  await formRef.value?.validate();
  submitLoading.value = true;
  try {
    if (editingId.value && props.config.api.update) {
      await props.config.api.update(editingId.value, { ...formState });
      ElMessage.success(t('common.successUpdate'));
    } else if (props.config.api.create) {
      await props.config.api.create({ ...formState });
      ElMessage.success(t('common.successCreate'));
    }
    dialogVisible.value = false;
    await fetchData();
  } finally {
    submitLoading.value = false;
  }
}

async function removeRow(row: Record<string, unknown>) {
  if (!props.config.api.remove) {
    return;
  }
  await ElMessageBox.confirm(t('common.deleteConfirmText'), t('common.deleteConfirmTitle'), {
    type: 'warning',
    confirmButtonText: t('common.deleteConfirmButton'),
    cancelButtonText: t('common.cancel'),
  });
  await props.config.api.remove(Number(row[rowKey.value]));
  ElMessage.success(t('common.successDelete'));
  await fetchData();
}

function handleSizeChange() {
  pageNum.value = 1;
  fetchData();
}

watch(
  () => props.config.extraQuery,
  () => {
    pageNum.value = 1;
    fetchData();
  },
  { deep: true },
);

(props.config.fields || [])
  .filter((field) => field.type === 'select' && field.loadOptions && field.dependsOn?.length)
  .forEach((field) => {
    watch(
      () => field.dependsOn!.map((dep) => formState[dep]),
      async () => {
        if (!dialogVisible.value) {
          return;
        }
        if (!initializingDialog.value && field.clearOnDependencyChange !== false) {
          formState[field.prop] = undefined;
        }
        await loadFieldOptions(field);
      },
      { deep: false },
    );
  });

onMounted(() => {
  resetDialog();
  fetchData();
});

defineExpose({
  fetchData,
  openCreate,
});
</script>

<style scoped lang="scss">
.content-card {
  overflow: hidden;
  padding: 0;
}

.filter-panel {
  margin-bottom: var(--space-md);
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-xs);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: var(--space-sm) var(--space-md);
  border-top: var(--rule-hair) solid var(--color-rule);
}

.crud-table-summary {
  cursor: pointer;
  color: var(--color-muted);
  margin-bottom: var(--space-xs);
}

:deep(.el-table) {
  .el-table__cell:last-child .cell {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: center;
    gap: var(--space-2xs);
  }
}

:deep(.el-dialog) {
  .el-dialog__header {
    padding: var(--space-md) var(--space-lg);
    border-bottom: var(--rule-hair) solid var(--color-rule);
  }

  .el-dialog__body {
    padding: var(--space-lg);
  }

  .el-dialog__footer {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-xs);
    padding: var(--space-sm) var(--space-lg);
    border-top: var(--rule-hair) solid var(--color-rule);
  }
}

:deep(.el-form) {
  .el-form-item {
    margin-bottom: var(--space-md);
  }

  .el-form-item__label {
    width: 110px;
    color: var(--color-ink-2);
    font-size: var(--text-xs);
    font-weight: 500;
  }
}

@media (max-width: 720px) {
  .page-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-sm);
  }

  .toolbar-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .pagination-bar {
    justify-content: center;
    overflow-x: auto;
  }
}
</style>
