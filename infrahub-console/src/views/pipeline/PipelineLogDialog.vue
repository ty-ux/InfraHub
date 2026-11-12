<template>
  <component :is="inline ? 'section' : ElDialog" v-bind="inline ? {} : { modelValue: true, title, width: 'min(1080px, 94vw)' }" @close="emit('close')">
    <div class="log-toolbar">
      <strong v-if="!inline">{{ label }}</strong>
      <div class="log-actions">
        <el-checkbox v-model="follow" @change="scrollToLatest">{{ t('runtimeLogs.follow') }}</el-checkbox>
        <el-button v-if="failed" size="small" @click="read">{{ t('common.refresh') }}</el-button>
      </div>
    </div>
    <p v-if="failed" role="alert">{{ t('runSteps.fullLogFailed') }}</p>
    <p v-else role="status">{{ t(statusKey) }}</p>
    <div v-if="sections.some(section => wholeRun || section.content)" ref="viewport" class="log-viewport" :class="{ 'log-inline': inline }" tabindex="0" :aria-label="title" @scroll="updateFollow">
      <section v-for="section in sections" :key="section.id" class="log-section">
        <h3 v-if="wholeRun">{{ section.label }}</h3>
        <p v-if="section.unavailable" class="log-section-note">{{ t(section.content ? 'runSteps.runLogExcerpt' : 'runSteps.fullLogUnavailable') }}</p>
        <pre v-if="section.content" class="step-log">{{ section.content }}</pre>
      </section>
    </div>
  </component>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElDialog } from 'element-plus';
import { getPipelineStepLog, pipelineStepRunApi } from '@/api/pipeline';

const props = defineProps<{ runId: number; stepRunId?: number; label: string; runFinished: boolean; inline?: boolean; excerpt?: string }>();
const emit = defineEmits<{ close: [] }>();
const { t } = useI18n();

interface LogTarget {
  id: number;
  label: string;
  excerpt: string;
}

interface LogSection {
  id: number;
  label: string;
  content: string;
  unavailable: boolean;
}

const wholeRun = props.stepRunId == null;
const title = computed(() => t(wholeRun ? 'runSteps.runLogTitle' : 'runSteps.fullLogTitle'));
const viewport = ref<HTMLElement>();
const sections = ref<LogSection[]>([]);
const follow = ref(!props.runFinished || (!wholeRun && !props.inline));
const finished = ref(false);
const available = ref(false);
const failed = ref(false);
const statusKey = computed(() => {
  if (finished.value && wholeRun) return sections.value.length ? 'runSteps.runLogFinished' : 'runSteps.runLogEmpty';
  if (finished.value && !available.value) {
    return props.inline && props.excerpt ? 'runSteps.runLogExcerpt' : 'runSteps.fullLogUnavailable';
  }
  const hasOutput = sections.value.some(section => section.content);
  if (finished.value) return hasOutput ? 'runSteps.fullLogFinished' : 'runSteps.fullLogEmpty';
  return hasOutput ? 'runSteps.fullLogLive' : 'runSteps.fullLogWaiting';
});

const targets: LogTarget[] = props.stepRunId == null ? [] : [{ id: props.stepRunId, label: props.label, excerpt: props.excerpt ?? '' }];
let targetIndex = 0;
let finalStepList = false;
let decoder = new TextDecoder();
let offset = 0;
let active = true;
let timer: ReturnType<typeof setTimeout> | undefined;

async function scrollToLatest() {
  await nextTick();
  if (follow.value && viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
}

function updateFollow() {
  const element = viewport.value;
  if (element) follow.value = element.scrollHeight - element.scrollTop - element.clientHeight <= 24;
}

async function discoverSteps() {
  const ids = new Set(targets.map(target => target.id));
  let pageNum = 1;
  for (;;) {
    const page = await pipelineStepRunApi.page({ pipelineRunId: props.runId, pageNum, pageSize: 100 });
    if (!active) return;
    for (const row of page.records) {
      const id = Number(row.id);
      if (!Number.isSafeInteger(id) || id <= 0 || ids.has(id)) continue;
      const label = [row.stageName, row.stepName].filter(Boolean).join(' / ') || `#${id}`;
      const excerpt = typeof row.logExcerpt === 'string' ? row.logExcerpt : '';
      targets.push({ id, label, excerpt });
      ids.add(id);
    }
    if (page.records.length === 0 || pageNum * page.pageSize >= page.total) return;
    pageNum += 1;
  }
}

async function read() {
  failed.value = false;
  try {
    if (!targets[targetIndex] && wholeRun) {
      if (!finalStepList) {
        const alreadyFinished = props.runFinished;
        await discoverSteps();
        if (!active) return;
        finalStepList = alreadyFinished;
      }
      if (!targets[targetIndex]) {
        finished.value = finalStepList;
        if (!finished.value) timer = setTimeout(read, 1000);
        return;
      }
    }
    const target = targets[targetIndex];
    if (!sections.value[targetIndex]) {
      sections.value.push({ id: target.id, label: target.label, content: '', unavailable: false });
      void scrollToLatest();
    }
    const chunk = await getPipelineStepLog(props.runId, target.id, offset);
    if (!active) return;
    available.value = chunk.available;
    const bytes = Uint8Array.from(atob(chunk.base64), (value) => value.charCodeAt(0));
    const text = decoder.decode(bytes, { stream: !chunk.finished });
    offset = chunk.nextOffset;
    const section = sections.value[targetIndex];
    section.content += text;
    if ((wholeRun || props.inline) && chunk.finished && !chunk.available) {
      section.unavailable = wholeRun;
      section.content = props.inline ? props.excerpt ?? '' : target.excerpt;
    }
    if (text || section.unavailable) {
      void scrollToLatest();
    }
    if (chunk.finished && wholeRun) {
      targetIndex += 1;
      offset = 0;
      decoder = new TextDecoder();
      timer = setTimeout(read, 0);
    } else {
      finished.value = chunk.finished;
      if (!finished.value) timer = setTimeout(read, chunk.hasMore ? 0 : 1000);
    }
  } catch {
    if (active) failed.value = true;
  }
}

onMounted(read);
onBeforeUnmount(() => {
  active = false;
  clearTimeout(timer);
});
</script>

<style scoped>
.log-toolbar, .log-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.log-toolbar { justify-content: space-between; }
p { color: var(--ih-text-secondary); }
.log-viewport {
  height: min(58vh, 620px);
  overflow: auto;
  overflow-anchor: none;
  margin: 0;
  padding: 12px;
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-subtle);
  color: var(--ih-text-primary);
}
.log-inline {
  height: auto;
  max-height: min(55vh, 540px);
}
.log-section + .log-section {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--ih-border);
}
.log-section h3 { margin: 0 0 8px; font-size: 13px; }
.log-section-note { margin: 0 0 8px; font-size: 12px; }
.step-log {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 13px/1.6 var(--ih-font-family-mono);
}
</style>
