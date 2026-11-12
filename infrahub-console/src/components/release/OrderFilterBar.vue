<template>
  <div class="order-filter-bar">
    <div class="quick-views">
      <el-button
        v-for="view in quickViews"
        :key="view.key"
        class="quick-view"
        :class="{ 'is-active': activeQuickView === view.key || (view.key === 'all' && !activeQuickView) }"
        size="small"
        @click="selectQuickView(view.key)"
      >
        {{ view.label }}
      </el-button>
    </div>

    <el-form :model="filters" class="filter-form">
      <el-form-item label="单号">
        <el-input
          v-model="filters.keyword"
          :placeholder="keywordPlaceholder"
          clearable
          @clear="emitChange"
          @keyup.enter="emitChange"
        />
      </el-form-item>

      <el-form-item v-if="showReleaseOrderKeyword" label="发布单号">
        <el-input
          v-model="filters.releaseOrderKeyword"
          placeholder="关联发布单号"
          clearable
          @clear="emitChange"
          @keyup.enter="emitChange"
        />
      </el-form-item>

      <el-form-item label="应用">
        <el-select
          v-model="filters.appId"
          placeholder="全部应用"
          clearable
          filterable
          fit-input-width
          @change="onAppChange"
        >
          <el-option
            v-for="option in appOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="服务">
        <el-select
          v-model="filters.serviceId"
          placeholder="全部服务"
          clearable
          filterable
          fit-input-width
          :disabled="!filters.appId"
        >
          <el-option
            v-for="option in filteredServiceOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="环境">
        <el-select
          v-model="filters.envId"
          placeholder="全部环境"
          clearable
          filterable
          fit-input-width
        >
          <el-option
            v-for="option in envOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="创建时间">
        <el-date-picker
          v-model="createdAtRange"
          type="daterange"
          range-separator="~"
          start-placeholder="开始"
          end-placeholder="结束"
          value-format="YYYY-MM-DD"
        />
      </el-form-item>

      <el-form-item label="状态">
        <el-select
          v-model="filters.status"
          placeholder="全部状态"
          clearable
          fit-input-width
          @change="onStatusChange"
        >
          <el-option label="已创建" value="CREATED" />
          <el-option label="审批中" value="APPROVING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="执行中" value="RUNNING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option v-if="type === 'release'" label="已回滚" value="ROLLED_BACK" />
        </el-select>
      </el-form-item>

      <el-form-item label="有失败提示">
        <el-select
          v-model="filters.hasFailureHint"
          placeholder="全部"
          clearable
          fit-input-width
        >
          <el-option label="全部" :value="undefined" />
          <el-option label="有" :value="true" />
          <el-option label="无" :value="false" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="type === 'release'" label="有关联流水线">
        <el-select
          v-model="filters.hasPipelineRun"
          placeholder="全部"
          clearable
          fit-input-width
        >
          <el-option label="全部" :value="undefined" />
          <el-option label="有" :value="true" />
          <el-option label="无" :value="false" />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="filter-footer">
      <div v-if="hasActiveFilters" class="active-filters">
        <span class="active-filters-label">当前筛选：</span>
        <el-tag
          v-if="activeQuickView"
          closable
          size="small"
          @close="clearQuickView"
        >
          视图：{{ quickViewLabel }}
        </el-tag>
        <el-tag v-if="filters.keyword" closable size="small" @close="clearField('keyword')">
          单号：{{ filters.keyword }}
        </el-tag>
        <el-tag v-if="filters.releaseOrderKeyword" closable size="small" @close="clearField('releaseOrderKeyword')">
          发布单号：{{ filters.releaseOrderKeyword }}
        </el-tag>
        <el-tag v-if="filters.appId" closable size="small" @close="clearField('appId')">
          应用：{{ getAppLabel(filters.appId) }}
        </el-tag>
        <el-tag v-if="filters.serviceId" closable size="small" @close="clearField('serviceId')">
          服务：{{ getServiceLabel(filters.serviceId) }}
        </el-tag>
        <el-tag v-if="filters.envId" closable size="small" @close="clearField('envId')">
          环境：{{ getEnvLabel(filters.envId) }}
        </el-tag>
        <el-tag v-if="createdAtRange && createdAtRange.length === 2" closable size="small" @close="clearDateRange">
          创建时间：{{ createdAtRange[0] }} ~ {{ createdAtRange[1] }}
        </el-tag>
        <el-tag v-if="filters.status" closable size="small" @close="clearField('status')">
          状态：{{ getStatusLabel(filters.status) }}
        </el-tag>
        <el-tag v-if="filters.hasFailureHint !== undefined" closable size="small" @close="clearField('hasFailureHint')">
          失败提示：{{ filters.hasFailureHint ? '有' : '无' }}
        </el-tag>
        <el-tag v-if="filters.hasPipelineRun !== undefined" closable size="small" @close="clearField('hasPipelineRun')">
          关联流水线：{{ filters.hasPipelineRun ? '有' : '无' }}
        </el-tag>
        <el-button link type="primary" size="small" @click="clearAllFilters">清空筛选</el-button>
      </div>
      <div class="filter-actions">
        <el-button @click="emitChange">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { appApi, envApi, serviceApi } from '@/api/resource';

interface Props {
  type: 'release' | 'rollback';
  initialFilters?: FilterState;
}

interface FilterState {
  keyword?: string;
  releaseOrderKeyword?: string;
  status?: string;
  appId?: number;
  serviceId?: number;
  envId?: number;
  createdAtStart?: string;
  createdAtEnd?: string;
  hasFailureHint?: boolean;
  hasPipelineRun?: boolean;
  activeQuickView?: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'change', filters: FilterState): void;
}>();

const filters = ref<FilterState>({ ...(props.initialFilters || {}) });
const createdAtRange = ref<[string, string] | null>(null);
const activeQuickView = ref<string | null>(null);

const appOptions = ref<{ label: string; value: number }[]>([]);
const serviceOptions = ref<{ label: string; value: number; appId?: number }[]>([]);
const envOptions = ref<{ label: string; value: number }[]>([]);

const showReleaseOrderKeyword = computed(() => props.type === 'rollback');
const keywordPlaceholder = computed(() => props.type === 'release' ? '发布单号' : '回滚单号');

const quickViews = [
  { key: 'all', label: '全部', status: undefined },
  { key: 'pending', label: '待执行', status: 'CREATED,APPROVED' },
  { key: 'running', label: '执行中', status: 'RUNNING' },
  { key: 'failed', label: '失败', status: 'FAILED' },
  { key: 'success', label: '成功', status: 'SUCCESS' },
];

const quickViewLabel = computed(() => {
  const view = quickViews.find((v) => v.key === activeQuickView.value);
  return view?.label || '';
});

const filteredServiceOptions = computed(() => {
  if (!filters.value.appId) return [];
  return serviceOptions.value.filter((s) => s.appId === filters.value.appId);
});

const hasActiveFilters = computed(() => {
  return (
    activeQuickView.value ||
    filters.value.keyword ||
    filters.value.releaseOrderKeyword ||
    filters.value.appId ||
    filters.value.serviceId ||
    filters.value.envId ||
    filters.value.status ||
    filters.value.hasFailureHint !== undefined ||
    filters.value.hasPipelineRun !== undefined ||
    (createdAtRange.value && createdAtRange.value.length === 2)
  );
});

function selectQuickView(key: string) {
  if (activeQuickView.value === key) return;
  activeQuickView.value = key === 'all' ? null : key;
  // V1.19: Clear status filter when selecting quick view (mutually exclusive)
  if (activeQuickView.value && filters.value.status) {
    delete filters.value.status;
  }
  emitChange();
}

function clearQuickView() {
  activeQuickView.value = null;
  emitChange();
}

function clearField(field: keyof FilterState) {
  delete filters.value[field];
  if (field === 'appId') {
    delete filters.value.serviceId;
  }
  emitChange();
}

function clearDateRange() {
  createdAtRange.value = null;
  emitChange();
}

function clearAllFilters() {
  filters.value = {};
  createdAtRange.value = null;
  activeQuickView.value = null;
  emitChange();
}

function onAppChange() {
  delete filters.value.serviceId;
}

// V1.19: Clear quick view when manually selecting status (mutually exclusive)
function onStatusChange() {
  if (activeQuickView.value && filters.value.status) {
    activeQuickView.value = null;
  }
}

function getAppLabel(id: number): string {
  return appOptions.value.find((o) => o.value === id)?.label || String(id);
}

function getServiceLabel(id: number): string {
  return serviceOptions.value.find((o) => o.value === id)?.label || String(id);
}

function getEnvLabel(id: number): string {
  return envOptions.value.find((o) => o.value === id)?.label || String(id);
}

function getStatusLabel(status: string): string {
  const statusMap: Record<string, string> = {
    CREATED: '已创建',
    APPROVING: '审批中',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    RUNNING: '执行中',
    SUCCESS: '成功',
    FAILED: '失败',
    ROLLED_BACK: '已回滚',
  };
  return statusMap[status] || status;
}

function emitChange() {
  const result: FilterState = { ...filters.value };

  // V1.19: Pass activeQuickView marker so view layer knows there's explicit filter
  if (activeQuickView.value) {
    result.activeQuickView = activeQuickView.value;
    const view = quickViews.find((v) => v.key === activeQuickView.value);
    if (view?.status) {
      result.status = view.status;
    }
  }

  if (createdAtRange.value && createdAtRange.value.length === 2) {
    // 发送本地日期时间（不带 Z 后缀），由后端按服务器时区解析
    result.createdAtStart = createdAtRange.value[0] + 'T00:00:00';
    result.createdAtEnd = createdAtRange.value[1] + 'T23:59:59';
  }

  emit('change', result);
}

function resetFilters() {
  clearAllFilters();
}

async function loadOptions() {
  const [appPage, servicePage, envPage] = await Promise.all([
    appApi.page({ pageNum: 1, pageSize: 200 }),
    serviceApi.page({ pageNum: 1, pageSize: 200 }),
    envApi.page({ pageNum: 1, pageSize: 200 }),
  ]);

  appOptions.value = appPage.records.map((r) => ({
    label: String(r.appName ?? r.id),
    value: r.id as number,
  }));

  serviceOptions.value = servicePage.records.map((r) => ({
    label: String(r.serviceName ?? r.id),
    value: r.id as number,
    appId: r.appId as number,
  }));

  envOptions.value = envPage.records.map((r) => ({
    label: String(r.envName ?? r.id),
    value: r.id as number,
  }));
}

onMounted(async () => {
  await loadOptions();
  if (Object.keys(props.initialFilters || {}).length > 0) {
    emitChange();
  }
});

defineExpose({
  resetFilters,
});
</script>

<style scoped lang="scss">
.order-filter-bar {
  overflow: hidden;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
}

.quick-views {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2xs);
  padding: var(--space-xs);
  border-bottom: var(--rule-hair) solid var(--color-rule);
  background: var(--color-paper-3);
}

.quick-view {
  height: 26px;
  padding: 0 var(--space-xs);
  border-color: transparent;
  background: transparent;
  color: var(--color-muted);
  font-size: var(--text-xs);

  &:hover {
    background: var(--color-paper-2);
    color: var(--color-ink);
  }

  &.is-active {
    border-color: transparent;
    background: var(--color-accent-soft);
    color: var(--color-accent);
    font-weight: 500;
  }
}

.filter-form {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-sm) var(--space-md);
  padding: var(--space-sm) var(--space-sm) var(--space-md);

  :deep(.el-form-item) {
    display: flex;
    min-width: 0;
    align-items: center;
    margin: 0;
  }

  :deep(.el-form-item__label) {
    flex: 0 0 88px;
    width: 88px;
    padding-right: var(--space-xs);
    color: var(--color-muted);
    font-size: var(--text-xs);
    line-height: var(--control-h);
    text-align: right;
    white-space: nowrap;
  }

  :deep(.el-form-item__content) {
    flex: 1;
    min-width: 0;
    margin-left: 0;
  }

  :deep(.el-input),
  :deep(.el-select),
  :deep(.el-date-editor) {
    width: 100%;
  }
}

.filter-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border-top: var(--rule-hair) solid var(--color-rule);
}

.filter-actions {
  display: flex;
  flex-shrink: 0;
  gap: var(--space-xs);
  margin-left: auto;
}

.active-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
  min-width: 0;
  margin-right: auto;
}

.active-filters-label {
  color: var(--color-muted);
  font-size: var(--text-xs);
}

@media (max-width: 1400px) {
  .filter-form {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1100px) {
  .filter-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .filter-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
