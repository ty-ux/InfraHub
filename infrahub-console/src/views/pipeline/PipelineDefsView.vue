<template>
  <PipelineBrowseLayout
    :title="t('pipelineBrowse.applications')"
    :search-label="t('pipelineBrowse.searchApplications')"
    :all-label="t('pipelineBrowse.allApplications')"
    :nodes="appNodes"
    :selected="selectedAppId"
    :loading="catalogLoading"
    :failed="catalogFailed"
    @select="selectedAppId = $event"
    @retry="loadApps"
  >
    <CrudPage v-if="config" :key="selectedAppId ?? 'all'" ref="crudPageRef" :config="config">
      <template #row-actions="{ row }">
        <router-link :to="`/pipeline/defs/${row.id}`">
          <el-button link type="primary">{{ t('common.configure') }}</el-button>
        </router-link>
        <el-button v-if="canRun && row.enabled" link type="primary" @click="openRunDialog(row)">
          {{ t('common.runPipeline') }}
        </el-button>
      </template>
    </CrudPage>
  </PipelineBrowseLayout>

  <PipelineRunDialog v-model="runDialogVisible" :pipeline="selectedPipeline" />
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import CrudPage from '@/components/page/CrudPage.vue';
import { appApi } from '@/api/resource';
import { listPipelineCatalog } from '@/api/pipeline';
import { useAuthStore } from '@/store/auth';
import { getPageConfig } from '@/views/configs/page-configs';
import PipelineBrowseLayout, { type BrowseNode } from './PipelineBrowseLayout.vue';
import PipelineRunDialog from './PipelineRunDialog.vue';

const authStore = useAuthStore();
const { t } = useI18n();
const crudPageRef = ref<InstanceType<typeof CrudPage>>();
const selectedAppId = ref<number | null>(null);
const appNodes = ref<BrowseNode[]>([]);
const catalogLoading = ref(true);
const catalogFailed = ref(false);
const config = computed(() => {
  const base = getPageConfig('pipelineDefs');
  if (!base || selectedAppId.value == null) return base;
  const selected = appNodes.value.find(app => app.id === selectedAppId.value);
  return {
    ...base,
    description: selected?.label ?? base.description,
    defaultForm: { ...base.defaultForm, appId: selectedAppId.value },
    extraQuery: { appId: selectedAppId.value },
  };
});
const canRun = computed(() => authStore.canAccess(['PIPELINE_EXECUTE', 'SYSTEM_ADMIN']));
const runDialogVisible = ref(false);
const selectedPipeline = ref<Record<string, unknown> | null>(null);

async function loadApps() {
  catalogLoading.value = true;
  catalogFailed.value = false;
  try {
    const apps = await listPipelineCatalog(appApi.page);
    appNodes.value = apps
      .map(app => ({ key: String(app.id), id: Number(app.id), label: String(app.appName ?? app.id), detail: String(app.appCode ?? '') }))
      .filter(app => Number.isSafeInteger(app.id) && app.id > 0)
      .sort((left, right) => left.label.localeCompare(right.label));
  } catch {
    catalogFailed.value = true;
  } finally {
    catalogLoading.value = false;
  }
}

onMounted(loadApps);

function openRunDialog(row: Record<string, unknown>) {
  selectedPipeline.value = row;
  runDialogVisible.value = true;
}
</script>
