<template>
  <PipelineBrowseLayout
    :title="t('pipelineBrowse.pipelines')"
    :search-label="t('pipelineBrowse.searchPipelines')"
    :all-label="t('pipelineBrowse.allPipelines')"
    :nodes="pipelineGroups"
    :selected="selectedPipelineId"
    :loading="catalogLoading"
    :failed="catalogFailed"
    @select="selectedPipelineId = $event"
    @retry="loadCatalog"
  >
    <CrudPage :key="selectedPipelineId ?? 'all'" :config="config">
      <!-- 运行状态：明确返回的 SUCCESS/FAILED/RUNNING 正常渲染；缺失或未知保留原始值并显示"无法确认" -->
      <template #runStatus="{ row }">
        <span class="run-status-cell">
          <el-tag v-if="runStatusKind(row.runStatus) !== 'UNKNOWN'" :type="runStatusTagType(row.runStatus)" size="small" disable-transitions>
            {{ t(`pipelineRuns.status${runStatusLabel(row.runStatus)}`) }}
          </el-tag>
          <template v-else>
            <el-tooltip :content="t('pipelineRuns.statusRaw', { raw: rawRunStatus(row.runStatus) })" placement="top">
              <el-tag type="info" size="small" disable-transitions>{{ t('pipelineRuns.statusUnconfirmed') }}</el-tag>
            </el-tooltip>
            <span class="run-status-raw">{{ rawRunStatus(row.runStatus) }}</span>
          </template>
        </span>
      </template>
      <!-- 耗时：缺失或非法值显示"无法确认"，不显示 NaN -->
      <template #duration="{ value }">
        <span>{{ durationText(value) }}</span>
      </template>
      <template #row-actions="{ row }">
        <router-link v-if="stepRunId(row)" :to="`/pipeline/runs/${stepRunId(row)}/steps`">
          <el-button link type="primary">{{ t('common.stepRuns') }}</el-button>
        </router-link>
        <!-- 运行 ID 无效时不构建步骤链接（避免 undefined/NaN 路由），并说明原因 -->
        <el-tooltip v-else :content="t('pipelineRuns.invalidRunId')" placement="top">
          <span class="action-disabled">
            <el-button link disabled>{{ t('common.stepRuns') }}</el-button>
          </span>
        </el-tooltip>
      </template>
    </CrudPage>
  </PipelineBrowseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import CrudPage from '@/components/page/CrudPage.vue';
import { listPipelineCatalog, pipelineApi } from '@/api/pipeline';
import { appApi } from '@/api/resource';
import { getPageConfig } from '@/views/configs/page-configs';
import PipelineBrowseLayout, { type BrowseNode } from './PipelineBrowseLayout.vue';

const { t } = useI18n();
const selectedPipelineId = ref<number | null>(null);
const catalogLoading = ref(true);
const catalogFailed = ref(false);
const pipelines = ref<Record<string, unknown>[]>([]);
const applications = ref<Record<string, unknown>[]>([]);

const pipelineGroups = computed(() => {
  const names = new Map(applications.value.map(app => [String(app.id), String(app.appName ?? app.id)]));
  const groups = new Map<string, BrowseNode>();
  for (const pipeline of pipelines.value) {
    const id = Number(pipeline.id);
    if (!Number.isSafeInteger(id) || id <= 0) continue;
    const appId = String(pipeline.appId ?? '-');
    let group = groups.get(appId);
    if (!group) {
      group = { key: `app-${appId}`, label: names.get(appId) ?? `${t('fields.appName')} #${appId}`, children: [] };
      groups.set(appId, group);
    }
    group.children?.push({
      key: String(id), id, label: String(pipeline.pipelineName ?? pipeline.pipelineCode ?? id),
      detail: String(pipeline.pipelineCode ?? ''),
    });
  }
  return [...groups.values()].sort((left, right) => left.label.localeCompare(right.label));
});

const config = computed(() => {
  const base = getPageConfig('pipelineRuns')!;
  const id = selectedPipelineId.value;
  if (id == null) return base;
  const selected = pipelines.value.find(pipeline => Number(pipeline.id) === id);
  return {
    ...base,
    description: selected ? String(selected.pipelineName ?? selected.pipelineCode ?? selected.id) : base.description,
    extraQuery: { pipelineId: id },
  };
});

async function loadCatalog() {
  catalogLoading.value = true;
  catalogFailed.value = false;
  try {
    [pipelines.value, applications.value] = await Promise.all([
      listPipelineCatalog(pipelineApi.page),
      listPipelineCatalog(appApi.page),
    ]);
  } catch {
    catalogFailed.value = true;
  } finally {
    catalogLoading.value = false;
  }
}

onMounted(loadCatalog);

type RunStatusKind = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PENDING' | 'QUEUED' | 'CANCELED' | 'SKIPPED' | 'UNKNOWN';

const RUN_STATUS_KINDS: RunStatusKind[] = ['RUNNING', 'SUCCESS', 'FAILED', 'PENDING', 'QUEUED', 'CANCELED', 'SKIPPED'];

/** 只有后端明确返回的已知状态才给出结论；缺失、空串或未知状态一律"无法确认" */
function runStatusKind(raw: unknown): RunStatusKind {
  if (typeof raw !== 'string') return 'UNKNOWN';
  const normalized = raw.trim().toUpperCase();
  const canonical = normalized === 'CANCELLED' ? 'CANCELED' : normalized;
  return (RUN_STATUS_KINDS as string[]).includes(canonical) ? (canonical as RunStatusKind) : 'UNKNOWN';
}

function runStatusLabel(raw: unknown): string {
  const kind = runStatusKind(raw);
  return kind === 'UNKNOWN' ? 'Unconfirmed' : kind.charAt(0) + kind.slice(1).toLowerCase();
}

function runStatusTagType(raw: unknown): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  const kind = runStatusKind(raw);
  if (kind === 'SUCCESS') return 'success';
  if (kind === 'FAILED') return 'danger';
  if (kind === 'RUNNING') return 'primary';
  return 'info';
}

/** 未知状态的原始值原样展示（不翻译、不改写）；缺失时明确说明状态缺失 */
function rawRunStatus(raw: unknown): string {
  if (typeof raw === 'string' && raw.trim()) return raw;
  return t('pipelineRuns.statusMissing');
}

function durationText(raw: unknown): string {
  if (raw === null || raw === undefined || raw === '') {
    return t('pipelineRuns.durationUnconfirmed');
  }
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    return t('pipelineRuns.durationUnconfirmed');
  }
  if (value < 1000) return t('pipelineRuns.durationMilliseconds', { ms: Math.round(value) });
  if (value < 60000) return t('pipelineRuns.durationSeconds', { seconds: Math.round(value / 1000) });
  if (value < 3600000) {
    return t('pipelineRuns.durationMinutesSeconds', { minutes: Math.floor(value / 60000), seconds: Math.round((value % 60000) / 1000) });
  }
  return t('pipelineRuns.durationHoursMinutes', { hours: Math.floor(value / 3600000), minutes: Math.round((value % 3600000) / 60000) });
}

/**
 * 只有正的安全整数运行 ID 才能构建步骤链接。
 * 接受正常数字与数字字符串；拒绝小数、非数值、布尔、0、负数、空串与超出安全整数范围的值。
 */
function stepRunId(row: Record<string, unknown>): number | null {
  const raw = row.id;
  if (typeof raw !== 'number' && typeof raw !== 'string') return null;
  if (typeof raw === 'string' && !raw.trim()) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}
</script>

<style scoped lang="scss">
.run-status-cell {
  display: inline-flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.run-status-raw {
  color: var(--ih-text-secondary);
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
}

.action-disabled {
  display: inline-flex;
}
</style>
