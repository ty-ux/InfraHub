<template>
  <el-dialog v-model="visible" :title="t('common.runPipeline')" width="min(520px, 94vw)" :close-on-click-modal="!submitting">
    <el-alert v-if="loadFailed || legacyDeploy" :title="t(legacyDeploy ? 'pipelineDelivery.configureSteps' : 'pipelineDelivery.loadFailed')"
      type="warning" :closable="false" show-icon />
    <el-form v-else v-loading="loading" label-position="top">
      <el-form-item :label="t('fields.pipelineName')">
        <strong>{{ pipeline?.pipelineName ?? pipeline?.pipelineCode }}</strong>
      </el-form-item>
      <el-form-item :label="t('fields.envName')" required>
        <el-select v-model="envId" filterable>
          <el-option v-for="env in envs" :key="Number(env.id)" :label="String(env.envName)" :value="Number(env.id)" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="deploys" :label="t('fields.serviceName')" required>
        <el-select v-model="serviceId" filterable>
          <el-option v-for="service in services" :key="Number(service.id)" :label="String(service.serviceName)" :value="Number(service.id)" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!registers" :label="t('fields.version')">
        <el-select v-model="versionId" filterable clearable>
          <el-option v-for="version in versions" :key="Number(version.id)" :label="String(version.versionNo)" :value="Number(version.id)" />
        </el-select>
      </el-form-item>
      <p v-if="registers" class="pipeline-build-hint">{{ t('pipelineDelivery.buildsVersion') }}</p>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" :disabled="loading || loadFailed || legacyDeploy || !envId || (deploys && !serviceId)" @click="submit">
        {{ t('common.runPipeline') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { getPipelineExecutorTypes, listPipelineCatalog, runPipeline } from '@/api/pipeline';
import { envApi, serviceApi } from '@/api/resource';
import { versionApi } from '@/api/release';

const visible = defineModel<boolean>({ required: true });
const props = defineProps<{ pipeline: Record<string, unknown> | null }>();
const { t } = useI18n();
const router = useRouter();
const envs = ref<Record<string, unknown>[]>([]);
const services = ref<Record<string, unknown>[]>([]);
const versions = ref<Record<string, unknown>[]>([]);
const envId = ref<number>();
const serviceId = ref<number>();
const versionId = ref<number>();
const types = ref<string[]>([]);
const loading = ref(false);
const loadFailed = ref(false);
const submitting = ref(false);
const deploys = computed(() => types.value.includes('RELEASE_DEPLOY'));
const registers = computed(() => types.value.includes('REGISTER_ARTIFACT'));
const legacyDeploy = computed(() => deploys.value && !registers.value);

watch(visible, async (open, _previous, onCleanup) => {
  const pipeline = props.pipeline;
  if (!open || !pipeline) return;
  let active = true;
  onCleanup(() => { active = false; });
  envId.value = serviceId.value = versionId.value = undefined;
  envs.value = services.value = versions.value = [];
  types.value = [];
  loadFailed.value = false;
  loading.value = true;
  try {
    const [environments, executors] = await Promise.all([
      listPipelineCatalog(envApi.page), getPipelineExecutorTypes(Number(pipeline.id)),
    ]);
    if (!active) return;
    envs.value = environments;
    types.value = executors;
    const belongsToApp = (row: Record<string, unknown>) => Number(row.appId) === Number(pipeline.appId);
    if (deploys.value && registers.value) {
      const rows = (await listPipelineCatalog(serviceApi.page)).filter(belongsToApp);
      if (!active) return;
      services.value = rows;
      if (rows.length === 1) serviceId.value = Number(rows[0].id);
    } else if (!registers.value && !legacyDeploy.value) {
      const rows = (await listPipelineCatalog(versionApi.page)).filter(belongsToApp);
      if (active) versions.value = rows;
    }
  } catch {
    if (active) loadFailed.value = true;
  } finally {
    if (active) loading.value = false;
  }
});

async function submit() {
  if (!props.pipeline || submitting.value || !envId.value || (deploys.value && !serviceId.value)) return;
  submitting.value = true;
  try {
    const result = await runPipeline(Number(props.pipeline.id), {
      envId: envId.value, serviceId: deploys.value ? serviceId.value : undefined,
      versionId: registers.value ? undefined : versionId.value,
    });
    ElMessage.success(t('common.pipelineRunSubmitted'));
    visible.value = false;
    await router.push(`/pipeline/runs/${result.pipelineRunId}/steps`);
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.pipeline-build-hint { margin: 0; color: var(--el-text-color-secondary); }
</style>
