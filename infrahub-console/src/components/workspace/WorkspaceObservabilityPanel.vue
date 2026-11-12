<template>
  <div class="ledger">
    <header class="ledger-head">
      <div class="ledger-title">
        <h2>{{ t('workspace.observability.title') }}</h2>
        <p>{{ t('workspace.observability.hint') }}</p>
      </div>
      <div class="ledger-meta">
        <span class="last-updated">
          {{ lastUpdatedAt ? t('workspace.observability.lastUpdated', { time: lastUpdatedAt }) : t('workspace.observability.neverUpdated') }}
        </span>
        <el-button size="small" :loading="isRefreshing" @click="refreshObservability">
          {{ t('workspace.observability.refresh') }}
        </el-button>
      </div>
    </header>

    <div class="ledger-grid">
      <section v-if="canAccessRelease" class="ledger-col" :aria-busy="releaseLoading">
        <header class="col-head">
          <div class="col-title">
            <h3>{{ t('workspace.observability.recentReleases') }}</h3>
            <el-tag v-if="!releaseLoading" :type="releaseSummary.type" size="small">{{ releaseSummary.label }}</el-tag>
          </div>
          <router-link to="/release/orders" class="col-link">{{ t('workspace.observability.viewAll') }}</router-link>
        </header>
        <div v-if="releaseLoading" class="col-state" role="status">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ t('common.loading') }}</span>
        </div>
        <div v-else-if="releaseError" class="col-state col-state--error">
          <span>{{ t('workspace.observability.loadFailed') }}</span>
          <el-button link type="primary" size="small" @click="loadRecentReleases">{{ t('common.retry') }}</el-button>
        </div>
        <div v-else-if="recentReleases.length === 0" class="col-state">
          <span>{{ t('workspace.observability.noData') }}</span>
        </div>
        <ol v-else class="entry-list">
          <li v-for="(item, index) in recentReleases" :key="item.id" class="entry">
            <span class="entry-index measured">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="entry-body">
              <router-link :to="`/release/orders?keyword=${encodeURIComponent(String(item.orderNo))}&focus=${encodeURIComponent(String(item.orderNo))}`" class="entry-id measured">
                {{ item.orderNo }}
              </router-link>
              <span class="entry-hint">{{ computeReleaseNextStep(item) }}</span>
            </div>
            <el-tag :type="getStatusTagType(String(item.status))" size="small">{{ item.status }}</el-tag>
          </li>
        </ol>
      </section>

      <section v-if="canAccessRollback" class="ledger-col" :aria-busy="rollbackLoading">
        <header class="col-head">
          <div class="col-title">
            <h3>{{ t('workspace.observability.recentRollbacks') }}</h3>
            <el-tag v-if="!rollbackLoading" :type="rollbackSummary.type" size="small">{{ rollbackSummary.label }}</el-tag>
          </div>
          <router-link to="/release/rollbacks" class="col-link">{{ t('workspace.observability.viewAll') }}</router-link>
        </header>
        <div v-if="rollbackLoading" class="col-state" role="status">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ t('common.loading') }}</span>
        </div>
        <div v-else-if="rollbackError" class="col-state col-state--error">
          <span>{{ t('workspace.observability.loadFailed') }}</span>
          <el-button link type="primary" size="small" @click="loadRecentRollbacks">{{ t('common.retry') }}</el-button>
        </div>
        <div v-else-if="recentRollbacks.length === 0" class="col-state">
          <span>{{ t('workspace.observability.noData') }}</span>
        </div>
        <ol v-else class="entry-list">
          <li v-for="(item, index) in recentRollbacks" :key="item.id" class="entry">
            <span class="entry-index measured">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="entry-body">
              <router-link :to="`/release/rollbacks?keyword=${encodeURIComponent(String(item.rollbackNo))}&focus=${encodeURIComponent(String(item.rollbackNo))}`" class="entry-id measured">
                {{ item.rollbackNo }}
              </router-link>
              <span class="entry-hint">{{ computeRollbackNextStep(item) }}</span>
            </div>
            <el-tag :type="getStatusTagType(String(item.status))" size="small">{{ item.status }}</el-tag>
          </li>
        </ol>
      </section>

      <section v-if="canAccessPipeline" class="ledger-col" :aria-busy="pipelineLoading">
        <header class="col-head">
          <div class="col-title">
            <h3>{{ t('workspace.observability.recentPipelineRuns') }}</h3>
            <el-tag v-if="!pipelineLoading" :type="pipelineSummary.type" size="small">{{ pipelineSummary.label }}</el-tag>
          </div>
          <router-link to="/pipeline/runs" class="col-link">{{ t('workspace.observability.viewAll') }}</router-link>
        </header>
        <div v-if="pipelineLoading" class="col-state" role="status">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ t('common.loading') }}</span>
        </div>
        <div v-else-if="pipelineError" class="col-state col-state--error">
          <span>{{ t('workspace.observability.loadFailed') }}</span>
          <el-button link type="primary" size="small" @click="loadRecentPipelineRuns">{{ t('common.retry') }}</el-button>
        </div>
        <div v-else-if="recentPipelineRuns.length === 0" class="col-state">
          <span>{{ t('workspace.observability.noData') }}</span>
        </div>
        <ol v-else class="entry-list">
          <li v-for="(item, index) in recentPipelineRuns" :key="item.id" class="entry">
            <span class="entry-index measured">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="entry-body">
              <router-link :to="`/pipeline/runs/${item.id}/steps`" class="entry-id measured">#{{ item.id }}</router-link>
              <span class="entry-hint">{{ item.pipelineDisplayName || '-' }}</span>
            </div>
            <el-tag :type="getPipelineStatusTagType(String(item.runStatus))" size="small">{{ item.runStatus }}</el-tag>
          </li>
        </ol>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { Loading } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/auth';
import { releaseApi, rollbackApi } from '@/api/release';
import { pipelineRunApi } from '@/api/pipeline';
import { computeReleaseNextStep, computeRollbackNextStep } from '@/views/configs/page-configs';

const VISIBLE_RECORD_LIMIT = 5;
const OBSERVABILITY_FETCH_SIZE = 20;

const authStore = useAuthStore();
const { t } = useI18n();

// Permission checks
const canAccessRelease = computed(() => authStore.canAccess(['RELEASE_VIEW', 'SYSTEM_ADMIN']));
const canAccessRollback = computed(() => authStore.canAccess(['ROLLBACK_VIEW', 'SYSTEM_ADMIN']));
const canAccessPipeline = computed(() => authStore.canAccess(['PIPELINE_EXECUTE', 'SYSTEM_ADMIN']));

// Recent data state (already prioritized, capped at VISIBLE_RECORD_LIMIT)
const recentReleases = ref<Record<string, unknown>[]>([]);
const releaseLoading = ref(false);
const releaseError = ref(false);

const recentRollbacks = ref<Record<string, unknown>[]>([]);
const rollbackLoading = ref(false);
const rollbackError = ref(false);

const recentPipelineRuns = ref<Record<string, unknown>[]>([]);
const pipelineLoading = ref(false);
const pipelineError = ref(false);

// V1.23: Refresh state
const lastUpdatedAt = ref('');
const isRefreshing = computed(() => releaseLoading.value || rollbackLoading.value || pipelineLoading.value);

function formatRefreshTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', { hour12: false });
}

// V1.25: Priority ordering — FAILED > RUNNING > others
function statusPriority(status: string): number {
  if (status === 'FAILED') return 0;
  if (status === 'RUNNING') return 1;
  return 2;
}

function prioritizeRecords(records: Record<string, unknown>[], statusKey: string): Record<string, unknown>[] {
  return [...records]
    .sort((a, b) => statusPriority(String(a[statusKey] ?? '')) - statusPriority(String(b[statusKey] ?? '')))
    .slice(0, VISIBLE_RECORD_LIMIT);
}

// V1.25: Status summary for card header
interface StatusSummary { label: string; type: 'success' | 'warning' | 'danger' }

function getStatusSummary(records: Record<string, unknown>[], statusKey: string): StatusSummary {
  const failedCount = records.filter((r) => String(r[statusKey]) === 'FAILED').length;
  const runningCount = records.filter((r) => String(r[statusKey]) === 'RUNNING').length;
  if (failedCount > 0) return { label: t('workspace.observability.failedCount', { count: failedCount }), type: 'danger' };
  if (runningCount > 0) return { label: t('workspace.observability.runningCount', { count: runningCount }), type: 'warning' };
  return { label: t('workspace.observability.normal'), type: 'success' };
}

function getHeaderStatusSummary(hasError: boolean, records: Record<string, unknown>[], statusKey: string): StatusSummary {
  if (hasError) {
    return { label: t('workspace.observability.loadFailed'), type: 'danger' };
  }
  return getStatusSummary(records, statusKey);
}

const releaseSummary = computed(() => getHeaderStatusSummary(releaseError.value, recentReleases.value, 'status'));
const rollbackSummary = computed(() => getHeaderStatusSummary(rollbackError.value, recentRollbacks.value, 'status'));
const pipelineSummary = computed(() => getHeaderStatusSummary(pipelineError.value, recentPipelineRuns.value, 'runStatus'));

// Status tag type mapping
function getStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'RUNNING':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'info';
  }
}

function getPipelineStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'RUNNING':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'info';
  }
}

// Load recent data (V1.25: fetch 20, prioritize, show top 5)
async function loadRecentReleases() {
  if (!canAccessRelease.value) return;
  releaseLoading.value = true;
  releaseError.value = false;
  try {
    const result = await releaseApi.page({ pageNum: 1, pageSize: OBSERVABILITY_FETCH_SIZE });
    recentReleases.value = prioritizeRecords(result.records || [], 'status');
  } catch {
    releaseError.value = true;
  } finally {
    releaseLoading.value = false;
  }
}

async function loadRecentRollbacks() {
  if (!canAccessRollback.value) return;
  rollbackLoading.value = true;
  rollbackError.value = false;
  try {
    const result = await rollbackApi.page({ pageNum: 1, pageSize: OBSERVABILITY_FETCH_SIZE });
    recentRollbacks.value = prioritizeRecords(result.records || [], 'status');
  } catch {
    rollbackError.value = true;
  } finally {
    rollbackLoading.value = false;
  }
}

async function loadRecentPipelineRuns() {
  if (!canAccessPipeline.value) return;
  pipelineLoading.value = true;
  pipelineError.value = false;
  try {
    const result = await pipelineRunApi.page({ pageNum: 1, pageSize: OBSERVABILITY_FETCH_SIZE });
    recentPipelineRuns.value = prioritizeRecords(result.records || [], 'runStatus');
  } catch {
    pipelineError.value = true;
  } finally {
    pipelineLoading.value = false;
  }
}

onMounted(() => {
  refreshObservability();
});

// V1.23: Unified refresh
async function refreshObservability() {
  const tasks: Promise<void>[] = [];
  if (canAccessRelease.value) tasks.push(loadRecentReleases());
  if (canAccessRollback.value) tasks.push(loadRecentRollbacks());
  if (canAccessPipeline.value) tasks.push(loadRecentPipelineRuns());
  await Promise.allSettled(tasks);
  lastUpdatedAt.value = formatRefreshTime(new Date());
}
</script>

<style scoped lang="scss">
.ledger {
  overflow: hidden;
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
}

.ledger-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-lg);
  padding: var(--space-md) var(--space-lg);
  border-bottom: var(--rule-hair) solid var(--color-rule);
  background: var(--color-paper-3);
}

.ledger-title {
  min-width: 0;

  h2 {
    min-width: 0;
    overflow-wrap: break-word;
    color: var(--color-ink);
    font-size: var(--text-lg);
    font-weight: 500;
    letter-spacing: -0.015em;
    text-wrap: balance;
  }

  p {
    max-width: 68ch;
    margin-top: var(--space-2xs);
    color: var(--color-muted);
    font-size: var(--text-xs);
    line-height: 1.6;
  }
}

.ledger-meta {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--space-sm);
}

.last-updated {
  color: var(--color-muted);
  font-size: var(--text-2xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ledger-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: var(--rule-hair);
  background: var(--color-rule);
}

.ledger-col {
  display: flex;
  min-width: 0;
  flex-direction: column;
  background: var(--color-paper-2);
}

.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-md);
  border-bottom: var(--rule-hair) solid var(--color-rule);
}

.col-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--space-xs);

  h3 {
    overflow: hidden;
    color: var(--color-ink);
    font-size: var(--text-sm);
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.col-link {
  flex-shrink: 0;
  color: var(--color-muted);
  font-size: var(--text-xs);
  text-decoration: none;
  white-space: nowrap;

  &:hover {
    color: var(--color-accent);
    text-decoration: underline;
    text-underline-offset: 0.18em;
  }
}

.col-state {
  display: flex;
  min-height: 180px;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  padding: var(--space-lg);
  color: var(--color-muted);
  font-size: var(--text-xs);
}

.col-state--error {
  color: var(--color-bad);
}

.entry-list {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
}

.entry {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-md);
  border-top: var(--rule-hair) solid var(--color-rule);
  transition: background-color var(--dur-short) var(--ease-out);

  &:first-child {
    border-top: 0;
  }

  &:hover {
    background: var(--color-paper-3);
  }
}

.entry-index {
  padding-top: 1px;
  color: var(--color-placeholder);
  font-size: var(--text-2xs);
  line-height: 1.6;
}

.entry-body {
  display: grid;
  min-width: 0;
  gap: var(--space-3xs);
}

.entry-id {
  overflow: hidden;
  color: var(--color-ink);
  font-size: var(--text-xs);
  line-height: 1.5;
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;

  &:hover {
    color: var(--color-accent);
    text-decoration: underline;
    text-underline-offset: 0.18em;
  }
}

.entry-hint {
  overflow: hidden;
  color: var(--color-muted);
  font-size: var(--text-2xs);
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 760px) {
  .ledger-head {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-sm);
    padding: var(--space-sm) var(--space-md);
  }

  .ledger-meta {
    width: 100%;
    justify-content: space-between;
  }

  .ledger-grid {
    grid-template-columns: 1fr;
  }
}
</style>
