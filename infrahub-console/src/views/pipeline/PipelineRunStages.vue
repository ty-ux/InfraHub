<template>
  <section class="stage-workspace" :aria-label="t('runSteps.stagesTitle')">
    <div class="stage-timeline" v-if="stages.length">
      <ol class="stage-track" :aria-label="t('runSteps.stagesTitle')">
        <li class="stage-endpoint"><span class="endpoint-dot" /><span>{{ t('runSteps.stageStart') }}</span></li>
        <li v-for="stage in stages" :key="stage.key" class="track-node">
          <button type="button" class="track-button" :aria-pressed="selected?.key === stage.key"
            :aria-label="`${stage.name} · ${statusLabel(stage.status)}`" @click="selectedKey = stage.key">
            <span class="stage-dot" :data-status="stage.status" aria-hidden="true">{{ statusSymbol[stage.status] }}</span>
            <span class="track-name">{{ stage.name }}</span>
          </button>
        </li>
        <li class="stage-endpoint">
          <span class="endpoint-dot" :data-finished="runFinished" />
          <span>{{ t('runSteps.stageEnd') }}</span>
        </li>
      </ol>
    </div>

    <div v-if="selected" class="stage-columns">
      <nav class="stage-list" :aria-label="t('runSteps.stagesTitle')">
        <el-input v-if="stages.length > 5" v-model="query" clearable
          :aria-label="t('runSteps.stageSearch')" :placeholder="t('runSteps.stageSearch')" />
        <ul>
          <li v-for="stage in matchingStages" :key="stage.key">
            <button type="button" class="stage-choice" :aria-pressed="selected.key === stage.key"
              @click="selectedKey = stage.key">
              <span class="stage-dot small-dot" :data-status="stage.status" aria-hidden="true">{{ statusSymbol[stage.status] }}</span>
              <span class="choice-name">{{ stage.name }}</span>
              <span class="choice-time" v-if="stage.elapsed != null">{{ durationText(stage.elapsed) }}</span>
            </button>
          </li>
        </ul>
        <p v-if="!matchingStages.length" class="stage-empty">{{ t('common.noData') }}</p>
      </nav>

      <section class="stage-detail" :aria-label="selected.name">
        <header class="stage-heading" :data-status="selected.status">
          <span class="stage-dot" :data-status="selected.status" aria-hidden="true">{{ statusSymbol[selected.status] }}</span>
          <h3>{{ selected.name }}</h3>
          <span class="stage-heading-status">{{ statusLabel(selected.status) }}</span>
          <span class="stage-heading-time" v-if="selected.elapsed != null">{{ durationText(selected.elapsed) }}</span>
        </header>
        <div v-if="!selected.steps.length" class="stage-empty">
          {{ t(selected.status === 'PENDING' ? 'runSteps.stageWaiting' : 'runSteps.stageNoSteps') }}
        </div>
        <div v-for="step in selected.steps" :key="step.id" class="stage-step">
          <button type="button" class="step-toggle" :aria-expanded="expanded.has(step.id)"
            :aria-controls="`stage-step-log-${step.id}`" @click="toggle(step.id)">
            <span class="step-caret" aria-hidden="true">{{ expanded.has(step.id) ? '▾' : '▸' }}</span>
            <span class="stage-dot small-dot" :data-status="step.status" aria-hidden="true">{{ statusSymbol[step.status] }}</span>
            <span class="step-name">{{ step.name }}</span>
            <span class="step-status">{{ statusLabel(step.status) }}</span>
            <span class="step-time" v-if="step.elapsed != null">{{ durationText(step.elapsed) }}</span>
          </button>
          <div v-if="expanded.has(step.id)" :id="`stage-step-log-${step.id}`" class="step-content">
            <p v-if="step.status === 'SKIPPED'" class="stage-empty">{{ t('runSteps.stepSkippedLog') }}</p>
            <PipelineLogDialog v-else inline :run-id="runId" :step-run-id="step.id"
              :run-finished="runFinished" :label="step.name" :excerpt="step.excerpt" />
          </div>
        </div>
      </section>
    </div>
    <p v-else class="stage-empty" role="status">{{ t(loading ? 'common.loading' : 'runSteps.stageNoRecords') }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { pipelineStageApi } from '@/api/pipeline';
import PipelineLogDialog from './PipelineLogDialog.vue';

const props = defineProps<{
  runId: number;
  pipelineId: number | null;
  runStatus: string;
  runFinished: boolean;
  currentStage: string;
  rows: Record<string, unknown>[];
  loading: boolean;
  durationText: (value: unknown) => string;
}>();
const { t } = useI18n();

type Status = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELED' | 'UNKNOWN';
interface Step {
  id: number;
  name: string;
  status: Status;
  excerpt: string;
  elapsed: number | null;
  start: number;
  end: number;
}
interface Stage {
  key: string;
  name: string;
  steps: Step[];
  status: Status;
  elapsed: number | null;
}
interface StagePlan {
  id: number;
  name: string;
  stepIds: number[];
}

const statusSymbol: Record<Status, string> = {
  PENDING: '·', RUNNING: '…', SUCCESS: '✓', FAILED: '×', SKIPPED: '–', CANCELED: '–', UNKNOWN: '?',
};
const plan = ref<StagePlan[]>([]);
const selectedKey = ref('');
const query = ref('');
const expanded = reactive(new Set<number>());
const seenRunning = new Set<number>();

function statusLabel(status: Status) {
  return t(`pipelineRuns.status${status === 'UNKNOWN' ? 'Unconfirmed' : status[0] + status.slice(1).toLowerCase()}`);
}

function positiveId(raw: unknown) {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function stepStatus(value: unknown): Status {
  const text = typeof value === 'string' ? value.trim().toUpperCase() : '';
  const status = text === 'CANCELLED' ? 'CANCELED' : text === 'QUEUED' ? 'PENDING' : text;
  return Object.hasOwn(statusSymbol, status) ? status as Status : 'UNKNOWN';
}

function timestamp(value: unknown) {
  return typeof value === 'string' ? Date.parse(value) : Number.NaN;
}

function stageStatus(stage: Stage, planned?: StagePlan): Status {
  const states = stage.steps.map(step => step.status);
  const stillRunning = props.runStatus === 'RUNNING';
  if (states.includes('RUNNING')) return stillRunning ? 'RUNNING' : 'UNKNOWN';
  if (stillRunning && props.currentStage === stage.name && planned && stage.steps.length < planned.stepIds.length) return 'RUNNING';
  if (!states.length) return props.runFinished ? 'SKIPPED' : 'PENDING';
  if (states.includes('FAILED')) return 'FAILED';
  if (states.includes('CANCELED')) return 'CANCELED';
  if (states.includes('UNKNOWN')) return 'UNKNOWN';
  if (states.includes('PENDING')) return 'PENDING';
  return states.includes('SUCCESS') ? 'SUCCESS' : 'SKIPPED';
}

function elapsed(stage: Stage) {
  const starts = stage.steps.map(step => step.start).filter(Number.isFinite);
  const ends = stage.steps.map(step => step.end).filter(Number.isFinite);
  if (starts.length && stage.status === 'RUNNING') return Math.max(0, Date.now() - Math.min(...starts));
  if (starts.length && ends.length) return Math.max(0, Math.max(...ends) - Math.min(...starts));
  const durations = stage.steps.flatMap(step => step.elapsed == null ? [] : [step.elapsed]);
  return durations.length ? durations.reduce((sum, value) => sum + value, 0) : null;
}

watch(() => [props.pipelineId, props.runStatus] as const, async ([pipelineId, status], _, onCleanup) => {
  if (!pipelineId || status !== 'RUNNING') return;
  let active = true;
  onCleanup(() => { active = false; });
  try {
    const definitions = await pipelineStageApi.listByPipeline(pipelineId);
    if (!active) return;
    const stages = definitions.flatMap(row => {
      const id = positiveId(row.id);
      return id && typeof row.stageName === 'string' ? [{ id, name: row.stageName, stepIds: [] as number[] }] : [];
    });
    plan.value = stages;
    const steps = await Promise.allSettled(stages.map(stage => pipelineStageApi.listSteps(stage.id)));
    if (!active) return;
    plan.value = stages.map((stage, index) => {
      const result = steps[index];
      if (result.status !== 'fulfilled') return stage;
      const stepIds = result.value.flatMap(row => positiveId(row.id) ?? []);
      return { ...stage, stepIds };
    });
  } catch {
    // Step run records remain the source for observed stages.
  }
}, { immediate: true });

const stages = computed(() => {
  const all: Stage[] = plan.value.map(stage => ({
    key: `plan-${stage.id}`, name: stage.name, steps: [], status: 'PENDING', elapsed: null,
  }));
  const byStep = new Map<number, Stage>();
  plan.value.forEach((stage, index) => stage.stepIds.forEach(id => byStep.set(id, all[index])));
  let observed: Stage | undefined;
  for (const row of props.rows) {
    const id = positiveId(row.id);
    if (id == null) continue;
    const name = typeof row.stageName === 'string' && row.stageName ? row.stageName : t('runSteps.unnamedStage');
    const exact = byStep.get(positiveId(row.stepId) ?? -1);
    const named = all.slice(0, plan.value.length).filter(stage => stage.name === name);
    let stage = exact?.name === name ? exact : named.length === 1 ? named[0] : undefined;
    if (!stage) {
      if (observed?.name !== name) {
        observed = { key: `record-${id}`, name, steps: [], status: 'UNKNOWN', elapsed: null };
        all.push(observed);
      }
      stage = observed;
    } else {
      observed = undefined;
    }
    const duration = Number(row.durationMs);
    stage.steps.push({
      id,
      name: String(row.stepName || `#${id}`),
      status: stepStatus(row.runStatus),
      excerpt: typeof row.logExcerpt === 'string' ? row.logExcerpt : '',
      elapsed: row.durationMs != null && Number.isFinite(duration) && duration >= 0 ? duration : null,
      start: timestamp(row.startTime),
      end: timestamp(row.endTime),
    });
  }
  return all.map((stage, index) => {
    stage.status = stageStatus(stage, plan.value[index]);
    stage.elapsed = elapsed(stage);
    return stage;
  });
});

const selected = computed(() => stages.value.find(stage => stage.key === selectedKey.value)
  ?? stages.value.find(stage => stage.status === 'RUNNING')
  ?? (props.runStatus === 'FAILED' ? stages.value.find(stage => stage.status === 'FAILED') : undefined)
  ?? [...stages.value].reverse().find(stage => stage.steps.length > 0)
  ?? stages.value[0]);
const matchingStages = computed(() => {
  const text = query.value.trim().toLowerCase();
  return stages.value.filter(stage => stage.name.toLowerCase().includes(text));
});

watch(() => selected.value?.steps.find(step => step.status === 'RUNNING')?.id, id => {
  if (id == null || seenRunning.has(id)) return;
  seenRunning.add(id);
  expanded.add(id);
}, { immediate: true });

function toggle(id: number) {
  if (expanded.has(id)) expanded.delete(id);
  else expanded.add(id);
}
</script>

<style scoped>
.stage-workspace { margin-bottom: var(--space-lg); }
.stage-timeline {
  overflow-x: auto;
  margin-bottom: var(--space-md);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background: var(--color-paper-2);
}
.stage-track {
  position: relative;
  display: flex;
  width: max-content;
  min-width: 100%;
  margin: 0;
  padding: 20px 10px 14px;
  list-style: none;
}
.stage-track::before {
  content: '';
  position: absolute;
  inset-inline: 49px;
  top: 34px;
  height: 1px;
  background: var(--color-rule-2);
}
.stage-endpoint, .track-button {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.stage-endpoint { flex: 0 0 78px; color: var(--color-muted); font-size: var(--text-xs); }
.endpoint-dot {
  width: 12px;
  height: 12px;
  margin: 8px 0;
  border: 1px solid var(--color-rule-2);
  border-radius: 50%;
  background: var(--color-paper-2);
}
.endpoint-dot[data-finished='true'] { background: var(--color-rule-2); }
.track-node { flex: 1 0 124px; }
.track-button, .stage-choice, .step-toggle {
  width: 100%;
  padding: 0;
  color: inherit;
  border: 0;
  background: transparent;
  font: inherit;
  cursor: pointer;
}
.track-button:focus-visible, .stage-choice:focus-visible, .step-toggle:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
  border-radius: var(--radius-field);
}
.track-name { max-width: 11rem; padding: 0 8px; text-align: center; font-size: var(--text-xs); }
.track-button[aria-pressed='true'] .track-name { font-weight: 600; color: var(--el-color-primary); }
.stage-dot {
  --dot-ink: var(--color-muted);
  --dot-bg: var(--color-paper-2);
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid color-mix(in oklab, var(--dot-ink) 50%, var(--dot-bg));
  border-radius: 50%;
  color: var(--dot-ink);
  background: var(--dot-bg);
  font-weight: 650;
}
[data-status='SUCCESS'] { --dot-ink: var(--color-ok); --dot-bg: var(--color-ok-soft); }
[data-status='RUNNING'] { --dot-ink: var(--el-color-primary); --dot-bg: var(--el-color-primary-light-9); }
[data-status='FAILED'] { --dot-ink: var(--el-color-danger); --dot-bg: var(--el-color-danger-light-9); }
.track-button[aria-pressed='true'] .stage-dot { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.small-dot { width: 20px; height: 20px; font-size: 12px; }
.stage-columns { display: grid; grid-template-columns: minmax(12rem, 16rem) minmax(0, 1fr); align-items: start; gap: var(--space-md); }
.stage-list, .stage-detail { min-width: 0; }
.stage-list ul { list-style: none; margin: 0; padding: 0; max-height: 55vh; overflow: auto; }
.stage-list :deep(.el-input) { margin-bottom: var(--space-xs); }
.stage-choice, .step-toggle { display: flex; align-items: center; text-align: left; gap: var(--space-xs); }
.stage-choice { padding: 9px; border-radius: var(--radius-field); }
.stage-choice[aria-pressed='true'] { background: var(--el-color-primary-light-9); font-weight: 600; }
.choice-name, .step-name { min-width: 0; overflow-wrap: anywhere; }
.choice-time, .step-time, .step-status, .stage-heading-time { flex: none; color: var(--color-muted); font-size: var(--text-xs); }
.choice-time, .step-status, .stage-heading-time { margin-left: auto; }
.stage-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-xs);
  padding: 8px 12px;
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-field);
  background: var(--dot-bg, var(--color-paper-2));
}
.stage-heading h3 { margin: 0; font-size: var(--text-sm); }
.stage-heading-status { font-size: var(--text-xs); color: var(--dot-ink, var(--color-muted)); }
.stage-step {
  margin-top: var(--space-xs);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  background: var(--color-paper-2);
}
.step-toggle { padding: 10px 12px; }
.step-caret { color: var(--color-muted); }
.step-content { padding: 0 12px 12px; }
.stage-empty { margin: 0; padding: 12px; color: var(--color-muted); }
@media (max-width: 860px) {
  .stage-columns { grid-template-columns: minmax(0, 1fr); }
  .stage-list ul { max-height: 28vh; }
}
</style>
