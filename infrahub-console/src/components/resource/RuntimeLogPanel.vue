<template>
  <section class="runtime-log-panel">
    <div class="log-controls">
      <span class="log-path" :title="logPath">{{ logPath || '-' }}</span>
      <div class="log-actions">
        <label><input v-model="autoRefresh" type="checkbox" /> {{ t('runtimeLogs.autoRefresh') }}</label>
        <label><input v-model="follow" type="checkbox" @change="scrollToLatest" /> {{ t('runtimeLogs.follow') }}</label>
        <label>{{ t('runtimeLogs.lineCount') }}
          <select :value="lines" :disabled="loading" @change="changeLines">
            <option v-for="count in [200, 500, 1000]" :key="count" :value="count">{{ count }}</option>
          </select>
        </label>
        <slot name="actions" />
        <el-button size="small" :disabled="!downloadable || !content" @click="downloadLogs">{{ t('runtimeLogs.download') }}</el-button>
        <el-button size="small" :loading="loading" @click="requestRefresh">{{ t('common.refresh') }}</el-button>
        <el-button v-if="!follow" size="small" @click="resumeFollow">{{ t('runtimeLogs.latest') }}</el-button>
      </div>
    </div>
    <div class="log-hint" role="status">
      {{ t('runtimeLogs.tailNotice', { lines }) }}
      <span v-if="failed">{{ t('runtimeLogs.pausedOnError') }}</span>
      <span v-else-if="!pageVisible">{{ t('runtimeLogs.backgroundPaused') }}</span>
      <span v-else-if="loading">{{ t('runtimeLogs.loading') }}</span>
      <span v-else-if="fetchedAt">{{ t('runtimeLogs.fetchedAt') }} {{ fetchedAt }}</span>
    </div>
    <slot />
    <pre ref="viewport" class="runtime-log" tabindex="0" :aria-label="t('runtimeLogs.content')" @scroll="onScroll">{{ content || emptyText || t('runtimeLogs.empty') }}</pre>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';

const props = withDefaults(defineProps<{
  active: boolean;
  targetKey: string | number;
  content?: string;
  logPath?: string;
  fetchedAt?: string;
  loading: boolean;
  failed?: boolean;
  downloadable?: boolean;
  lines?: number;
  emptyText?: string;
}>(), { content: '', logPath: '', fetchedAt: '', failed: false, downloadable: true, lines: 200, emptyText: '' });
const emit = defineEmits<{ refresh: []; 'update:lines': [value: number] }>();
const { t } = useI18n();
const viewport = ref<HTMLElement | null>(null);
const autoRefresh = ref(true);
const follow = ref(true);
const pageVisible = ref(!document.hidden);
let timer: ReturnType<typeof setTimeout> | undefined;
const downloads = new Map<string, ReturnType<typeof setTimeout>>();

function stopTimer() { clearTimeout(timer); timer = undefined; }
function schedule() {
  stopTimer();
  if (!props.active || !autoRefresh.value || !pageVisible.value || props.loading || props.failed) return;
  timer = setTimeout(() => { timer = undefined; requestRefresh(); }, 2000);
}
function requestRefresh() {
  if (!props.active || props.loading) return;
  emit('refresh');
  // A parent which rejects/no-ops the request must not leave polling permanently stopped.
  schedule();
}
function changeLines(event: Event) {
  const lines = Number((event.target as HTMLSelectElement).value);
  if ([200, 500, 1000].includes(lines)) emit('update:lines', lines);
}
async function scrollToLatest() {
  if (!follow.value) return;
  await nextTick();
  if (viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
}
function resumeFollow() { follow.value = true; void scrollToLatest(); }
function onScroll() {
  const element = viewport.value;
  if (element) follow.value = element.scrollHeight - element.scrollTop - element.clientHeight <= 24;
}
function visibilityChanged() { pageVisible.value = !document.hidden; }
function releaseDownload(url: string) {
  clearTimeout(downloads.get(url));
  URL.revokeObjectURL(url);
  downloads.delete(url);
}
function downloadLogs() {
  if (!props.downloadable || !props.content) return;
  const url = URL.createObjectURL(new Blob([props.content], { type: 'text/plain;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  const target = String(props.targetKey).replace(/[^a-zA-Z0-9_-]/g, '_');
  anchor.download = `instance-${target}-tail-${props.lines}-${new Date().toISOString().replace(/[:.]/g, '-')}.log`;
  document.body.appendChild(anchor);
  try { anchor.click(); } finally {
    anchor.remove();
    downloads.set(url, setTimeout(() => releaseDownload(url), 1000));
  }
}
watch(() => [props.active, props.targetKey], () => {
  autoRefresh.value = true;
  follow.value = true;
  void scrollToLatest();
});
watch(() => [props.content, props.active], () => { if (props.active) void scrollToLatest(); }, { flush: 'post' });
watch(() => [props.active, props.targetKey, props.loading, props.failed, autoRefresh.value, pageVisible.value], schedule, { immediate: true });
onMounted(() => document.addEventListener('visibilitychange', visibilityChanged));
onBeforeUnmount(() => {
  stopTimer();
  document.removeEventListener('visibilitychange', visibilityChanged);
  for (const url of downloads.keys()) releaseDownload(url);
});
</script>

<style scoped>
.log-controls { display: flex; flex-direction: column; gap: 10px; margin-bottom: 8px; }
.log-path { color: var(--ih-text-secondary); overflow-wrap: anywhere; font-family: var(--ih-font-family-mono); }
.log-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.log-actions label { display: inline-flex; gap: 5px; align-items: center; white-space: nowrap; }
.log-actions select { color: var(--ih-text-primary); background: var(--ih-bg-subtle); border: 1px solid var(--ih-border); border-radius: 4px; padding: 4px; }
.log-actions :deep(.el-button + .el-button) { margin-left: 0; }
.log-hint { display: flex; flex-wrap: wrap; gap: 8px; color: var(--ih-text-secondary); font-size: 12px; margin-bottom: 8px; }
.runtime-log { background: var(--ih-bg-subtle); border: 1px solid var(--ih-border); border-radius: 4px; padding: 12px; font-family: var(--ih-font-family-mono); font-size: 13px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; height: min(50vh, 520px); overflow: auto; overflow-anchor: none; margin: 8px 0 0; }
</style>
