<template>
  <section v-loading="groupsLoading" class="env-configs">
    <header class="page-heading">
      <h2>{{ t('pages.envConfigs.title') }}</h2>
      <div>
        <el-button :disabled="editing" @click="openImportDialog">{{ t('envConfigEditor.import') }}</el-button>
        <el-button :disabled="editing" @click="openTemplateDialog">{{ t('envConfigEditor.template') }}</el-button>
      </div>
    </header>
    <el-alert v-if="groupsError" :title="groupsError" type="error" :closable="false" show-icon />
    <div class="config-layout">
      <aside class="group-panel">
        <h3>{{ t('pages.configGroups.title') }}</h3>
        <el-input v-model="groupSearch" clearable :placeholder="t('envConfigEditor.searchGroup')" />
        <nav :aria-label="t('pages.configGroups.title')">
          <button
            v-for="group in visibleGroups"
            :key="group.key"
            type="button"
            class="group-option"
            :class="{ selected: selectedGroup?.key === group.key }"
            :aria-current="selectedGroup?.key === group.key ? 'true' : undefined"
            :disabled="saving"
            @click="selectGroup(group)"
          >
            <strong>{{ group.name }}</strong>
            <span>{{ groupContext(group) }}</span>
          </button>
          <p v-if="!visibleGroups.length">{{ t('envConfigEditor.noGroups') }}</p>
        </nav>
      </aside>
      <section v-loading="documentLoading" class="document-panel">
        <template v-if="selectedGroup">
          <header class="document-heading">
            <div>
              <h3>{{ selectedGroup.name }}</h3>
              <span>{{ groupContext(selectedGroup) }}</span>
            </div>
            <div class="document-actions">
              <template v-if="editing">
                <el-button :disabled="saving" @click="cancelEdit">{{ t('common.cancel') }}</el-button>
                <el-button type="primary" :disabled="!dirty" :loading="saving" @click="saveDocument">{{ t('common.save') }}</el-button>
              </template>
              <template v-else>
                <el-button :disabled="documentLoading" @click="loadDocument(selectedGroup)">{{ t('common.refresh') }}</el-button>
                <el-button type="primary" :disabled="!document || documentLoading" @click="startEditing">{{ t('common.edit') }}</el-button>
              </template>
            </div>
          </header>
          <el-alert v-if="documentError" :title="documentError" type="error" :closable="false" show-icon />
          <template v-if="document">
            <div v-if="focusKey" class="editor-hint">{{ t('envConfigEditor.focusKey', { key: focusKey }) }}</div>
            <div v-if="editing" class="editor-hint">{{ t('envConfigEditor.editHint', { example: '{ "$secretRef": 14 }' }) }}</div>
            <textarea
              ref="editor"
              v-model="draft"
              class="json-editor"
              aria-label="JSON"
              wrap="off"
              spellcheck="false"
              :readonly="!editing || saving"
            />
          </template>
        </template>
        <el-empty v-else :description="t('envConfigEditor.selectGroup')" />
      </section>
    </div>
  </section>

  <el-dialog v-model="importVisible" title="批量导入环境配置" width="760px" @closed="resetImportForm">
    <el-form ref="importFormRef" :model="importForm" label-width="110px" :rules="importRules">
      <el-form-item label="环境" prop="envId">
        <el-select v-model="importForm.envId" filterable clearable style="width: 100%" @change="handleImportScopeChange">
          <el-option v-for="option in envOptions" :key="`import-env-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="应用" prop="appId">
        <el-select v-model="importForm.appId" filterable clearable style="width: 100%" @change="handleImportScopeChange">
          <el-option v-for="option in appOptions" :key="`import-app-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="分组" prop="groupId">
        <el-select v-model="importForm.groupId" filterable clearable style="width: 100%" :loading="groupLoading">
          <el-option v-for="option in importGroupOptions" :key="`import-group-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="导入格式" prop="format">
        <el-radio-group v-model="importForm.format">
          <el-radio-button label="properties">properties</el-radio-button>
          <el-radio-button label="yaml">YAML</el-radio-button>
          <el-radio-button label="json">JSON</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="默认值类型" prop="valueType">
        <el-select v-model="importForm.valueType" style="width: 100%">
          <el-option v-for="option in valueTypeOptions" :key="`import-type-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="密钥引用">
        <el-select v-model="importForm.secretRefId" filterable clearable style="width: 100%">
          <el-option v-for="option in secretRefOptions" :key="`import-secret-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="版本号" prop="versionNo">
        <el-input-number v-model="importForm.versionNo" :min="1" :controls="false" style="width: 100%" />
      </el-form-item>
      <el-form-item label="配置文本" prop="content">
        <el-input
          v-model="importForm.content"
          type="textarea"
          :rows="14"
          placeholder="支持粘贴 properties / YAML / JSON 配置内容"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="importVisible = false">取消</el-button>
      <el-button type="primary" :loading="importSubmitting" @click="submitImport">开始导入</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="templateVisible" title="从模板生成环境配置" width="680px" @closed="resetTemplateForm">
    <el-form ref="templateFormRef" :model="templateForm" label-width="110px" :rules="templateRules">
      <el-form-item label="环境" prop="envId">
        <el-select v-model="templateForm.envId" filterable clearable style="width: 100%" @change="handleTemplateScopeChange">
          <el-option v-for="option in envOptions" :key="`template-env-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="应用" prop="appId">
        <el-select v-model="templateForm.appId" filterable clearable style="width: 100%" @change="handleTemplateScopeChange">
          <el-option v-for="option in appOptions" :key="`template-app-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="分组" prop="groupId">
        <el-select v-model="templateForm.groupId" filterable clearable style="width: 100%" :loading="groupLoading">
          <el-option v-for="option in templateGroupOptions" :key="`template-group-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="模板" prop="templateCode">
        <el-select v-model="templateForm.templateCode" style="width: 100%">
          <el-option v-for="option in templateOptions" :key="`template-${option.value}`" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="版本号" prop="versionNo">
        <el-input-number v-model="templateForm.versionNo" :min="1" :controls="false" style="width: 100%" />
      </el-form-item>
    </el-form>
    <el-alert title="模板会按当前选择的环境、应用、分组批量生成一组默认配置项，生成后你再按需要微调。" type="info" :closable="false" />
    <template #footer>
      <el-button @click="templateVisible = false">取消</el-button>
      <el-button type="primary" :loading="templateSubmitting" @click="submitTemplate">生成配置</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { onBeforeRouteLeave, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import type { CrudFieldOption } from '@/types/page';
import {
  appApi, configGroupApi, envApi, envConfigApi, envConfigDocumentApi, secretRefApi,
  type EnvConfigDocument, type EnvConfigScope,
} from '@/api/resource';

interface GroupOption {
  key: string;
  name: string;
  code?: string;
  scope: EnvConfigScope;
}

const { t } = useI18n();
const route = useRoute();
const groupSearch = ref('');
const groups = ref<GroupOption[]>([]);
const groupsLoading = ref(false);
const groupsError = ref('');
const selectedGroup = ref<GroupOption>();
const document = ref<EnvConfigDocument>();
const documentError = ref('');
const documentLoading = ref(false);
const editor = ref<HTMLTextAreaElement>();
const editing = ref(false);
const saving = ref(false);
const draft = ref('');
const dirty = computed(() => editing.value && draft.value !== document.value?.json);
const focusKey = computed(() => {
  const group = selectedGroup.value;
  return group && matchesRouteScope(group) && typeof route.query.highlight === 'string'
    ? route.query.highlight : '';
});
const visibleGroups = computed(() => {
  const search = groupSearch.value.trim().toLowerCase();
  return groups.value.filter((group) => `${group.name} ${groupContext(group)}`.toLowerCase().includes(search));
});
let documentRequest = 0;

const importFormRef = ref<FormInstance>();
const templateFormRef = ref<FormInstance>();

const appOptions = ref<CrudFieldOption[]>([]);
const envOptions = ref<CrudFieldOption[]>([]);
const secretRefOptions = ref<CrudFieldOption[]>([]);
const importGroupOptions = ref<CrudFieldOption[]>([]);
const templateGroupOptions = ref<CrudFieldOption[]>([]);
const groupLoading = ref(false);
const importVisible = ref(false);
const templateVisible = ref(false);
const importSubmitting = ref(false);
const templateSubmitting = ref(false);

const valueTypeOptions: CrudFieldOption[] = [
  { label: 'STRING', value: 'STRING' },
  { label: 'NUMBER', value: 'NUMBER' },
  { label: 'BOOLEAN', value: 'BOOLEAN' },
  { label: 'JSON', value: 'JSON' },
  { label: 'SECRET_REF', value: 'SECRET_REF' },
];

const templateOptions: CrudFieldOption[] = [
  { label: 'Spring Boot 基础模板', value: 'spring-boot-base' },
  { label: 'PostgreSQL 模板', value: 'postgresql' },
  { label: 'Redis 模板', value: 'redis' },
];

const importForm = reactive({
  envId: undefined as number | undefined,
  appId: undefined as number | undefined,
  groupId: undefined as number | undefined,
  format: 'properties',
  valueType: 'STRING',
  secretRefId: undefined as number | undefined,
  versionNo: 1,
  content: '',
});

const templateForm = reactive({
  envId: undefined as number | undefined,
  appId: undefined as number | undefined,
  groupId: undefined as number | undefined,
  templateCode: 'spring-boot-base',
  versionNo: 1,
});

const importRules: FormRules = {
  envId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  appId: [{ required: true, message: '请选择应用', trigger: 'change' }],
  groupId: [{ required: true, message: '请选择分组', trigger: 'change' }],
  format: [{ required: true, message: '请选择导入格式', trigger: 'change' }],
  valueType: [{ required: true, message: '请选择默认值类型', trigger: 'change' }],
  versionNo: [{ required: true, message: '请输入版本号', trigger: 'blur' }],
  content: [{ required: true, message: '请粘贴配置文本', trigger: 'blur' }],
};

const templateRules: FormRules = {
  envId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  appId: [{ required: true, message: '请选择应用', trigger: 'change' }],
  groupId: [{ required: true, message: '请选择分组', trigger: 'change' }],
  templateCode: [{ required: true, message: '请选择模板', trigger: 'change' }],
  versionNo: [{ required: true, message: '请输入版本号', trigger: 'blur' }],
};

onMounted(async () => {
  groupsLoading.value = true;
  try {
    await Promise.all([preloadBaseOptions(), loadGroups()]);
    const initialGroup = groups.value.find(matchesRouteScope) ?? groups.value[0];
    if (initialGroup) await selectGroup(initialGroup);
  } catch (error) {
    groupsError.value = errorMessage(error);
  } finally {
    groupsLoading.value = false;
  }
});

onBeforeRouteLeave(() => saving.value ? false : confirmDiscard());

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function matchesRouteScope(group: GroupOption) {
  return (!route.query.groupId || String(group.scope.groupId) === route.query.groupId)
    && (!route.query.appId || String(group.scope.appId) === route.query.appId)
    && (!route.query.envId || String(group.scope.envId) === route.query.envId);
}

function groupContext(group: GroupOption) {
  const { appId, envId } = group.scope;
  const app = appOptions.value.find((item) => item.value === appId)?.label ?? appId;
  const env = envOptions.value.find((item) => item.value === envId)?.label ?? envId;
  return [app, env, group.code].filter(Boolean).join(' / ');
}

async function loadGroups() {
  const [records, ungroupedScopes] = await Promise.all([
    loadAllRecords(configGroupApi),
    envConfigDocumentApi.ungroupedScopes(),
  ]);
  groups.value = [
    ...records.map((row) => ({
      key: `group-${row.id}`,
      name: String(row.groupName ?? row.groupCode ?? row.id),
      code: String(row.groupCode ?? ''),
      scope: { groupId: Number(row.id), envId: Number(row.envId), appId: row.appId == null ? undefined : Number(row.appId) },
    })),
    ...ungroupedScopes.map(({ envId, appId }) => ({
      key: `ungrouped-${envId}-${appId ?? ''}`,
      name: t('envConfigEditor.ungrouped'),
      scope: { envId, appId: appId ?? undefined },
    })),
  ];
}

async function confirmDiscard() {
  if (!dirty.value) return true;
  return ElMessageBox.confirm(t('envConfigEditor.discard'), { type: 'warning' }).then(() => true, () => false);
}

async function selectGroup(group: GroupOption) {
  if (selectedGroup.value?.key === group.key || saving.value || !await confirmDiscard()) return;
  selectedGroup.value = group;
  editing.value = false;
  await loadDocument(group);
}

async function loadDocument(group: GroupOption) {
  const request = ++documentRequest;
  document.value = undefined;
  documentError.value = '';
  documentLoading.value = true;
  try {
    const result = await envConfigDocumentApi.read(group.scope);
    if (request === documentRequest) {
      document.value = result;
      draft.value = result.json;
    }
  } catch (error) {
    if (request === documentRequest) documentError.value = errorMessage(error);
  } finally {
    if (request === documentRequest) documentLoading.value = false;
  }
}

async function startEditing() {
  editing.value = true;
  documentError.value = '';
  await nextTick();
  editor.value?.focus();
  if (focusKey.value && editor.value) {
    const key = JSON.stringify(focusKey.value);
    const index = draft.value.indexOf(key);
    if (index !== -1) editor.value.setSelectionRange(index, index + key.length);
  }
}

async function cancelEdit() {
  if (!await confirmDiscard()) return;
  draft.value = document.value?.json ?? '';
  documentError.value = '';
  editing.value = false;
}

async function saveDocument() {
  if (!document.value || !selectedGroup.value || saving.value) return;
  documentError.value = '';
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(draft.value);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error();
  } catch {
    documentError.value = t('envConfigEditor.invalidJson');
    return;
  }
  saving.value = true;
  try {
    const removed = Object.keys(JSON.parse(document.value.json)).filter((key) => !Object.prototype.hasOwnProperty.call(parsed, key));
    if (removed.length) {
      const confirmed = await ElMessageBox.confirm(t('envConfigEditor.removeKeys', { count: removed.length }), {
        type: 'warning',
      }).then(() => true, () => false);
      if (!confirmed) return;
    }
    document.value = await envConfigDocumentApi.save(selectedGroup.value.scope, {
      json: draft.value,
      revision: document.value.revision,
    });
    draft.value = document.value.json;
    editing.value = false;
    ElMessage.success(t('envConfigEditor.saved'));
  } catch (error) {
    documentError.value = errorMessage(error);
  } finally {
    saving.value = false;
  }
}

async function preloadBaseOptions() {
  const [apps, envs, secrets] = await Promise.all([
    loadEntityOptions(appApi, 'appName'),
    loadEntityOptions(envApi, 'envName'),
    loadEntityOptions(secretRefApi, 'secretKey'),
  ]);
  appOptions.value = apps;
  envOptions.value = envs;
  secretRefOptions.value = secrets;
}

async function loadEntityOptions(
  api: ListApi,
  labelProp: string,
  valueProp = 'id',
) {
  const records = await loadAllRecords(api);
  return records.map((record) => ({
    label: String(record[labelProp] ?? record[valueProp] ?? '-'),
    value: record[valueProp] as string | number | boolean,
  }));
}

type ListApi = { page: (params: Record<string, unknown>) => Promise<{ records: Record<string, unknown>[]; total: number }> };

async function loadAllRecords(api: ListApi) {
  const records: Record<string, unknown>[] = [];
  let pageNum = 1;
  for (;;) {
    const page = await api.page({ pageNum, pageSize: 200 });
    records.push(...page.records);
    if (records.length >= page.total || page.records.length === 0) return records;
    pageNum += 1;
  }
}

async function loadGroupOptions(envId?: number, appId?: number) {
  if (!envId || !appId) {
    return [];
  }
  groupLoading.value = true;
  try {
    const records = await loadAllRecords(configGroupApi);
    return records
      .filter((record) => String(record.envId ?? '') === String(envId) && String(record.appId ?? '') === String(appId))
      .map((record) => ({
        label: String(record.groupName ?? record.groupCode ?? record.id ?? '-'),
        value: record.id as string | number | boolean,
      }));
  } finally {
    groupLoading.value = false;
  }
}

async function handleImportScopeChange() {
  importForm.groupId = undefined;
  importGroupOptions.value = await loadGroupOptions(importForm.envId, importForm.appId);
}

async function handleTemplateScopeChange() {
  templateForm.groupId = undefined;
  templateGroupOptions.value = await loadGroupOptions(templateForm.envId, templateForm.appId);
}

async function openImportDialog() {
  resetImportForm();
  const scope = selectedGroup.value?.scope;
  if (scope?.groupId) {
    Object.assign(importForm, scope);
    importGroupOptions.value = await loadGroupOptions(scope.envId, scope.appId);
  }
  importVisible.value = true;
}

async function openTemplateDialog() {
  resetTemplateForm();
  const scope = selectedGroup.value?.scope;
  if (scope?.groupId) {
    Object.assign(templateForm, scope);
    templateGroupOptions.value = await loadGroupOptions(scope.envId, scope.appId);
  }
  templateVisible.value = true;
}

function resetImportForm() {
  importForm.envId = undefined;
  importForm.appId = undefined;
  importForm.groupId = undefined;
  importForm.format = 'properties';
  importForm.valueType = 'STRING';
  importForm.secretRefId = undefined;
  importForm.versionNo = 1;
  importForm.content = '';
  importGroupOptions.value = [];
  importFormRef.value?.clearValidate();
}

function resetTemplateForm() {
  templateForm.envId = undefined;
  templateForm.appId = undefined;
  templateForm.groupId = undefined;
  templateForm.templateCode = 'spring-boot-base';
  templateForm.versionNo = 1;
  templateGroupOptions.value = [];
  templateFormRef.value?.clearValidate();
}

async function submitImport() {
  await importFormRef.value?.validate();
  importSubmitting.value = true;
  try {
    const entries = await parseImportContent(importForm.format, importForm.content);
    if (!entries.length) {
      ElMessage.warning('没有解析到可导入的配置项');
      return;
    }
    await createEnvConfigs(
      entries.map((entry) => ({
        ...entry,
        valueType: entry.valueType || importForm.valueType,
      })),
      {
        envId: importForm.envId!,
        appId: importForm.appId!,
        groupId: importForm.groupId!,
        secretRefId: importForm.secretRefId,
        versionNo: importForm.versionNo,
      },
    );
    importVisible.value = false;
  } finally {
    importSubmitting.value = false;
  }
}

async function submitTemplate() {
  await templateFormRef.value?.validate();
  templateSubmitting.value = true;
  try {
    const entries = buildTemplateEntries(templateForm.templateCode);
    await createEnvConfigs(entries, {
      envId: templateForm.envId!,
      appId: templateForm.appId!,
      groupId: templateForm.groupId!,
      versionNo: templateForm.versionNo,
    });
    templateVisible.value = false;
  } finally {
    templateSubmitting.value = false;
  }
}

async function createEnvConfigs(
  entries: Array<{ configKey: string; configValue: string; valueType?: string; secretRefId?: number }>,
  scope: { envId: number; appId: number; groupId: number; versionNo: number; secretRefId?: number },
) {
  let successCount = 0;
  const failures: string[] = [];

  for (const entry of entries) {
    try {
      await envConfigApi.create({
        envId: scope.envId,
        appId: scope.appId,
        groupId: scope.groupId,
        configKey: entry.configKey,
        configValue: entry.configValue,
        valueType: entry.valueType || 'STRING',
        secretRefId: entry.secretRefId ?? scope.secretRefId,
        versionNo: scope.versionNo,
      });
      successCount += 1;
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : String(error);
      failures.push(`${entry.configKey}: ${message}`);
    }
  }

  const target = groups.value.find((group) => group.scope.groupId === scope.groupId);
  if (target) {
    if (target.key === selectedGroup.value?.key) await loadDocument(target);
    else await selectGroup(target);
  }

  if (!failures.length) {
    ElMessage.success(`已成功生成 ${successCount} 条配置`);
    return;
  }

  await ElMessageBox.alert(
    `成功 ${successCount} 条，失败 ${failures.length} 条。\n\n${failures.slice(0, 8).join('\n')}`,
    '批量导入结果',
    { type: 'warning' },
  );
}

async function parseImportContent(format: string, content: string) {
  switch (format) {
    case 'json':
      return parseJsonContent(content);
    case 'yaml':
      return parseYamlContent(content);
    default:
      return parsePropertiesContent(content);
  }
}

function parsePropertiesContent(content: string) {
  return content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#') && !line.startsWith('!'))
    .map((line) => {
      const splitIndex = line.includes('=') ? line.indexOf('=') : line.indexOf(':');
      if (splitIndex < 0) {
        return null;
      }
      return {
        configKey: line.slice(0, splitIndex).trim(),
        configValue: line.slice(splitIndex + 1).trim(),
      };
    })
    .filter((item): item is { configKey: string; configValue: string } => Boolean(item?.configKey));
}

function parseJsonContent(content: string) {
  const parsed = JSON.parse(content) as Record<string, unknown>;
  return flattenObject(parsed);
}

async function parseYamlContent(content: string) {
  const yaml = await import('yaml');
  const parsed = yaml.parse(content) as Record<string, unknown>;
  return flattenObject(parsed);
}

function flattenObject(input: Record<string, unknown>, prefix = ''): Array<{ configKey: string; configValue: string }> {
  return Object.entries(input).flatMap(([key, value]) => {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    if (value != null && typeof value === 'object' && !Array.isArray(value)) {
      return flattenObject(value as Record<string, unknown>, fullKey);
    }
    return [{ configKey: fullKey, configValue: Array.isArray(value) ? JSON.stringify(value) : String(value ?? '') }];
  });
}

function buildTemplateEntries(templateCode: string) {
  switch (templateCode) {
    case 'postgresql':
      return [
        { configKey: 'spring.datasource.url', configValue: 'jdbc:postgresql://127.0.0.1:5432/your_db', valueType: 'STRING' },
        { configKey: 'spring.datasource.username', configValue: 'postgres', valueType: 'STRING' },
        { configKey: 'spring.datasource.password', configValue: '', valueType: 'STRING' },
      ];
    case 'redis':
      return [
        { configKey: 'spring.redis.host', configValue: '127.0.0.1', valueType: 'STRING' },
        { configKey: 'spring.redis.port', configValue: '6379', valueType: 'NUMBER' },
        { configKey: 'spring.redis.password', configValue: '', valueType: 'STRING' },
      ];
    default:
      return [
        { configKey: 'server.port', configValue: '8080', valueType: 'NUMBER' },
        { configKey: 'spring.application.name', configValue: 'your-service', valueType: 'STRING' },
        { configKey: 'logging.level.root', configValue: 'INFO', valueType: 'STRING' },
        { configKey: 'management.endpoints.web.exposure.include', configValue: 'health,info', valueType: 'STRING' },
      ];
  }
}
</script>

<style scoped lang="scss">
.env-configs {
  display: grid;
  gap: 16px;
}

.page-heading,
.document-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

h2, h3 {
  margin: 0;
}

.config-layout {
  display: grid;
  grid-template-columns: minmax(210px, 258px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.group-panel,
.document-panel {
  display: grid;
  gap: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  padding: 16px;
  min-width: 0;
}

.group-panel nav {
  display: grid;
  gap: 4px;
  max-height: 68vh;
  overflow: auto;
}

.group-option {
  display: grid;
  gap: 4px;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--el-text-color-primary);
  background: transparent;
  font: inherit;
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;

  &:hover { background: var(--el-fill-color-light); }
  &.selected {
    background: var(--el-color-primary-light-9);
    border-color: var(--el-color-primary-light-5);
  }
}

.group-option span,
.group-panel p,
.document-heading span,
.editor-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.json-editor {
  box-sizing: border-box;
  width: 100%;
  min-height: clamp(280px, 62vh, 900px);
  resize: vertical;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font: 13px/1.65 ui-monospace, Consolas, monospace;
  tab-size: 2;

  &:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
}

@media (max-width: 760px) {
  .config-layout { grid-template-columns: minmax(0, 1fr); }
  .group-panel nav { max-height: 200px; }
}
</style>
