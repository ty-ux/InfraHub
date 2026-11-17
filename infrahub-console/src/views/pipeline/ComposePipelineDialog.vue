<template>
  <el-dialog v-model="visible" :title="t('composePipeline.title')" width="min(1060px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <div class="template-layout" v-loading="loading">
      <el-form ref="formRef" :model="form" :disabled="saving || catalogFailed" label-position="top">
        <el-alert v-if="catalogFailed" :title="t('pipelineDelivery.loadFailed')" type="error" :closable="false" />
        <section class="template-section">
          <h3>{{ t('composePipeline.target') }}</h3>
          <div class="field-grid">
            <el-form-item :label="t('fields.pipelineName')" prop="pipelineName" :rules="requiredText">
              <el-input v-model.trim="form.pipelineName" maxlength="128" />
            </el-form-item>
            <el-form-item :label="t('fields.pipelineCode')" prop="pipelineCode" :rules="codeRules">
              <el-input v-model.trim="form.pipelineCode" maxlength="64" placeholder="my-app-compose" />
            </el-form-item>
            <el-form-item :label="t('fields.appName')" prop="appId" :rules="requiredChoice">
              <el-select v-model="form.appId" filterable @change="form.serviceId = undefined">
                <el-option v-for="row in apps" :key="row.id" :label="row.name" :value="row.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('fields.envName')" prop="envId" :rules="requiredChoice">
              <el-select v-model="form.envId" filterable>
                <el-option v-for="row in environments" :key="row.id" :label="row.name" :value="row.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('fields.serviceName')" prop="serviceId" :rules="requiredChoice">
              <el-select v-model="form.serviceId" filterable :disabled="!form.appId">
                <el-option v-for="row in appServices" :key="row.id" :label="row.name" :value="row.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('composePipeline.deployRoot')">
              <el-input v-model.trim="form.deployRoot" :placeholder="defaultRoot" />
            </el-form-item>
          </div>
          <p v-if="targetLoading" class="field-help" role="status">{{ t('composePipeline.loadingTargets') }}</p>
          <el-alert v-else-if="targetError" :title="targetError" type="warning" :closable="false" />
          <p v-else-if="validTargets" class="field-help">{{ t('composePipeline.targetSummary', { count: targets.length }) }}
            {{ targets.map((target) => target.instanceName).join('、') }}</p>
        </section>

        <section class="template-section">
          <h3>{{ t('composePipeline.source') }}</h3>
          <el-form-item :label="t('composePipeline.repositoryUrl')" prop="repositoryUrl" :rules="requiredText">
            <el-input v-model.trim="form.repositoryUrl" placeholder="https://git.example.com/team/project.git" />
          </el-form-item>
          <div class="field-grid">
            <el-form-item :label="t('composePipeline.branch')" prop="branch" :rules="requiredText">
              <el-input v-model.trim="form.branch" />
            </el-form-item>
            <el-form-item v-if="form.repositoryUrl.startsWith('ssh://')" :label="t('composePipeline.gitKey')">
              <el-select v-model="form.gitSshSecretRefId" clearable filterable>
                <el-option v-for="row in secrets" :key="row.id" :label="row.name" :value="row.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('composePipeline.composeFile')" prop="composeFile" :rules="requiredText">
              <el-input v-model.trim="form.composeFile" />
            </el-form-item>
          </div>
        </section>

        <section class="template-section">
          <h3>{{ t('composePipeline.images') }}</h3>
          <p class="field-help">{{ t('composePipeline.dockerfileHelp') }}</p>
          <div class="field-grid">
            <el-form-item :label="t('composePipeline.buildContext')">
              <el-select v-model="form.dockerContext" clearable :placeholder="t('composePipeline.defaultContext')">
                <el-option v-for="context in buildContexts" :key="context" :label="context" :value="context" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('composePipeline.registry')" prop="registry" :rules="requiredText">
              <el-input v-model.trim="form.registry" placeholder="registry.example.com:5000" />
            </el-form-item>
          </div>
          <div class="field-grid">
            <section v-for="key in imageKeys" :key="key" class="image-fields">
              <h4>{{ t(`composePipeline.${key}`) }}</h4>
              <el-form-item :label="t('composePipeline.composeService')" :prop="`${key}.composeService`" :rules="requiredText">
                <el-input v-model.trim="form[key].composeService" />
              </el-form-item>
              <el-form-item :label="t('composePipeline.imageRepository')" :prop="`${key}.repository`" :rules="requiredText">
                <el-input v-model.trim="form[key].repository" :placeholder="`team/project-${key}`" />
              </el-form-item>
              <el-form-item :label="t('composePipeline.context')" :prop="`${key}.context`" :rules="requiredText">
                <el-input v-model.trim="form[key].context" />
              </el-form-item>
              <el-form-item :label="t('composePipeline.dockerfile')" :prop="`${key}.dockerfile`" :rules="requiredText">
                <el-input v-model.trim="form[key].dockerfile" :placeholder="`${key}/Dockerfile`" />
              </el-form-item>
            </section>
          </div>
        </section>

        <section class="template-section">
          <h3>{{ t('composePipeline.checks') }}</h3>
          <div class="field-grid">
            <el-checkbox v-model="form.preflight">{{ t('composePipeline.preflight') }}</el-checkbox>
            <el-checkbox v-model="healthEnabled">{{ t('composePipeline.health') }}</el-checkbox>
          </div>
          <div v-if="healthEnabled" class="field-grid">
            <el-form-item :label="t('composePipeline.healthUrl')" prop="healthUrl" :rules="requiredText">
              <el-input v-model.trim="form.healthUrl" placeholder="http://127.0.0.1:8080/actuator/health" />
            </el-form-item>
            <el-form-item :label="t('fields.timeoutSeconds')">
              <el-input-number v-model="form.healthTimeoutSeconds" :min="5" :max="30" />
            </el-form-item>
          </div>
        </section>
      </el-form>

      <aside class="template-preview" :aria-label="t('composePipeline.preview')">
        <h3>{{ t('composePipeline.preview') }}</h3>
        <ol>
          <li v-for="stage in stagePreview" :key="stage">{{ t(`composePipeline.${stage}`) }}</li>
        </ol>
        <p>{{ t('composePipeline.versionHelp') }}</p>
      </aside>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || catalogFailed || targetLoading || !validTargets" @click="submit">
        {{ t('composePipeline.save') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, type FormInstance, type FormItemRule } from 'element-plus';
import { createComposePipeline, getComposeBuildContexts, listPipelineCatalog, type ComposePipelineTemplate } from '@/api/pipeline';
import { getApplicationRollbackInstances, getApplicationRollbackOptions, type RollbackChoice } from '@/api/release';
import { secretRefApi } from '@/api/resource';
import type { RuntimeServiceInstance } from '@/api/runtime';

const visible = defineModel<boolean>({ required: true });
const props = defineProps<{ initialAppId?: number }>();
const { t } = useI18n();
const router = useRouter();
const formRef = ref<FormInstance>();
const form = reactive<Omit<ComposePipelineTemplate, 'appId' | 'envId' | 'serviceId'> & {
  appId?: number; envId?: number; serviceId?: number;
}>({
  pipelineName: '', pipelineCode: '', repositoryUrl: '', branch: 'main', registry: '', composeFile: 'compose.yml', deployRoot: '',
  backend: { composeService: 'backend', repository: '', context: '.', dockerfile: '' },
  frontend: { composeService: 'frontend', repository: '', context: '.', dockerfile: '' },
  preflight: true, healthUrl: '', healthTimeoutSeconds: 20,
});
const requiredText = computed<FormItemRule[]>(() => [{ required: true, message: t('composePipeline.required'), trigger: 'blur' }]);
const requiredChoice = computed<FormItemRule[]>(() => [{ required: true, type: 'number', message: t('composePipeline.required'), trigger: 'change' }]);
const codeRules = computed<FormItemRule[]>(() => [
  ...requiredText.value, { pattern: /^[a-z0-9][a-z0-9_-]{0,63}$/, message: t('composePipeline.invalidCode'), trigger: 'blur' },
]);
const imageKeys = ['backend', 'frontend'] as const;
const healthEnabled = ref(false);
const loading = ref(true);
const catalogFailed = ref(false);
const targetLoading = ref(false);
const targetReadFailed = ref(false);
const saving = ref(false);
const apps = ref<RollbackChoice[]>([]);
const environments = ref<RollbackChoice[]>([]);
const services = ref<RollbackChoice[]>([]);
const secrets = ref<RollbackChoice[]>([]);
const buildContexts = ref<string[]>([]);
const targets = ref<RuntimeServiceInstance[]>([]);
const appServices = computed(() => services.value.filter((row) => row.appId === form.appId));
const defaultRoot = computed(() => `/opt/infrahub/${form.pipelineCode || 'my-app-compose'}`);
const validTargets = computed(() => targets.value.length > 0
  && targets.value.every((row) => /^[a-z0-9][a-z0-9_-]*$/.test(row.instanceName ?? '') && row.instanceType === 'DOCKER_COMPOSE' && row.hostId)
  && new Set(targets.value.map((row) => row.hostId)).size === targets.value.length);
const targetError = computed(() => {
  if (!form.envId || !form.serviceId || validTargets.value) return '';
  return t(targetReadFailed.value ? 'pipelineDelivery.loadFailed' : 'composePipeline.missingTargets');
});
const stagePreview = computed(() => [
  ...(form.preflight ? ['preflight'] : []), 'checkoutStage', 'buildStage', 'pushStage', 'artifactStage',
  healthEnabled.value ? 'deployWithHealth' : 'deployStage',
]);

onMounted(async () => {
  try {
    const [catalog, secretRows, contexts] = await Promise.all([
      getApplicationRollbackOptions(), listPipelineCatalog(secretRefApi.page), getComposeBuildContexts(),
    ]);
    apps.value = catalog.apps;
    environments.value = catalog.envs;
    services.value = catalog.services;
    buildContexts.value = contexts;
    secrets.value = secretRows.filter((row) => row.secretProvider === 'LOCAL_FILE')
      .map((row) => ({ id: Number(row.id), name: String(row.secretKey) }));
    form.appId = props.initialAppId ?? (apps.value.length === 1 ? apps.value[0].id : undefined);
  } catch {
    catalogFailed.value = true;
  } finally {
    loading.value = false;
  }
});

watch(() => [form.serviceId, form.envId] as const, async ([serviceId, envId], _old, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  targets.value = [];
  targetReadFailed.value = false;
  targetLoading.value = !!(serviceId && envId);
  if (!serviceId || !envId) return;
  try {
    const rows = await getApplicationRollbackInstances(serviceId, envId);
    if (active) targets.value = rows;
  } catch {
    if (active) targetReadFailed.value = true;
  } finally {
    if (active) targetLoading.value = false;
  }
});

async function submit() {
  const { appId, envId, serviceId } = form;
  if (saving.value || !appId || !envId || !serviceId || !validTargets.value) return;
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || saving.value) return;
  saving.value = true;
  try {
    const result = await createComposePipeline({
      ...form, appId, envId, serviceId,
      deployRoot: form.deployRoot || defaultRoot.value, dockerContext: form.dockerContext || undefined,
      gitSshSecretRefId: form.repositoryUrl.startsWith('ssh://') ? form.gitSshSecretRefId : undefined,
      healthUrl: healthEnabled.value ? form.healthUrl : undefined,
      healthTimeoutSeconds: healthEnabled.value ? form.healthTimeoutSeconds : undefined,
    });
    ElMessage.success(t('common.successCreate'));
    visible.value = false;
    await router.push(`/pipeline/defs/${result.pipelineId}`);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.template-layout { display: grid; grid-template-columns: minmax(0, 1fr) 13rem; gap: 1.5rem; max-height: 64vh; overflow-y: auto; padding-inline-end: .5rem; }
.template-section { margin-bottom: 1.15rem; border-bottom: 1px solid var(--el-border-color-light); padding-bottom: .55rem; }
.template-section:last-child { border-bottom: 0; margin-bottom: 0; }
.template-section h3, .template-preview h3 { margin: 0 0 .65rem; color: var(--el-text-color-primary); font-size: 1rem; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 1rem; }
.field-grid .el-select { width: 100%; }
.image-fields { border-inline-start: 2px solid var(--el-border-color); padding-inline-start: .8rem; }
.image-fields h4 { margin: 0 0 .55rem; font-size: .875rem; }
.field-help { margin: 0 0 .65rem; color: var(--el-text-color-secondary); }
.template-preview { align-self: start; position: sticky; top: 0; padding: .85rem 1rem; border-inline-start: 1px solid var(--el-border-color-light); }
.template-preview ol { padding-inline-start: 1.3rem; margin: 0 0 .85rem; }
.template-preview li { padding-block: .3rem; }
.template-preview p { color: var(--el-text-color-secondary); margin: 0; }
@media (max-width: 720px) {
  .template-layout, .field-grid { grid-template-columns: minmax(0, 1fr); }
  .template-preview { position: static; }
}
</style>
