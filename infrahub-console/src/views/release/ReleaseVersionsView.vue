<template>
  <section>
    <div class="page-toolbar">
      <div class="page-title">
        <h2>版本管理</h2>
        <p>查看应用的构建版本、制品和来源流水线。</p>
      </div>
      <div class="toolbar-actions">
        <el-button @click="fetchData">刷新</el-button>
        <el-button type="primary" @click="openCreateDialog">新建版本</el-button>
      </div>
    </div>

    <div class="page-card content-card">
      <el-alert
        v-if="lastCreatedVersion"
        type="success"
        show-icon
        :closable="false"
        class="version-success"
      >
        <template #title>
          版本已创建：{{ lastCreatedVersion.versionNo }}
        </template>
        <div class="version-result">
          <div>版本ID：{{ lastCreatedVersion.id }}</div>
          <div>应用ID：{{ lastCreatedVersion.appId }}</div>
          <div>制品ID：{{ lastCreatedVersion.artifactId }}</div>
        </div>
      </el-alert>
      <el-table :data="records" v-loading="loading" row-key="id" empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="appDisplayName" label="应用名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="versionNo" label="版本" min-width="160" show-overflow-tooltip />
        <el-table-column prop="gitCommit" label="Git Commit" min-width="150" show-overflow-tooltip />
        <el-table-column prop="branchName" label="分支" min-width="120" show-overflow-tooltip />
        <el-table-column prop="buildNo" label="构建号" min-width="120" show-overflow-tooltip />
        <el-table-column prop="artifactDisplayName" label="制品名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="buildRunDisplayName" label="构建记录" min-width="120" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="190" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="row.buildPipelineRunId" link type="primary" size="small" @click="router.push(`/pipeline/runs/${row.buildPipelineRunId}/steps`)">构建记录</el-button>
            <el-button link type="primary" size="small" @click="navigateToCurrentVersion(row)">当前版本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          @current-change="fetchData"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="新建版本" width="680px" @closed="resetDialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="应用" prop="appId">
          <el-select v-model="form.appId" filterable clearable style="width: 100%">
            <el-option v-for="option in appOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号" prop="versionNo">
          <el-input v-model="form.versionNo" placeholder="例如 infrahub-upload-deploy-20260415-1" />
        </el-form-item>
        <el-form-item label="制品" prop="artifactId">
          <el-select v-model="form.artifactId" filterable clearable style="width: 100%">
            <el-option v-for="option in artifactOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="Git Commit">
          <el-input v-model="form.gitCommit" />
        </el-form-item>
        <el-form-item label="分支">
          <el-input v-model="form.branchName" />
        </el-form-item>
        <el-form-item label="构建号">
          <el-input v-model="form.buildNo" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { appApi } from '@/api/resource';
import { artifactApi, createReleaseVersion, versionApi } from '@/api/release';
import { getPageConfig } from '@/views/configs/page-configs';

interface Option {
  label: string;
  value: number;
}

const route = useRoute();
const router = useRouter();
const versionConfig = getPageConfig('releaseVersions');
const records = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const pageNum = ref(1);
const pageSize = ref(10);
const total = ref(0);
const dialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();
const appOptions = ref<Option[]>([]);
const artifactOptions = ref<Option[]>([]);
const lastCreatedVersion = ref<Record<string, unknown> | null>(null);

const form = reactive({
  appId: undefined as number | undefined,
  versionNo: '',
  artifactId: undefined as number | undefined,
  gitCommit: '',
  branchName: '',
  buildNo: '',
});

const rules = computed<FormRules>(() => ({
  appId: [{ required: true, message: '请选择应用', trigger: 'change' }],
  versionNo: [{ required: true, message: '请输入版本号', trigger: 'blur' }],
  artifactId: [{ required: true, message: '请选择制品', trigger: 'change' }],
}));

async function fetchData() {
  loading.value = true;
  try {
    const page = versionConfig
      ? await versionConfig.api.page({ pageNum: pageNum.value, pageSize: pageSize.value })
      : await versionApi.page({ pageNum: pageNum.value, pageSize: pageSize.value });
    records.value = page.records;
    total.value = page.total;
  } finally {
    loading.value = false;
  }
}

async function openCreateDialog(prefillArtifactId?: number) {
  resetDialog();
  dialogVisible.value = true;
  await Promise.all([loadApps(), loadArtifacts()]);
  if (prefillArtifactId) {
    form.artifactId = prefillArtifactId;
  }
}

function resetDialog() {
  form.appId = undefined;
  form.versionNo = '';
  form.artifactId = undefined;
  form.gitCommit = '';
  form.branchName = '';
  form.buildNo = '';
  formRef.value?.clearValidate();
}

async function loadApps() {
  const page = await appApi.page({ pageNum: 1, pageSize: 200 });
  appOptions.value = page.records.map((record) => ({
    label: `${record.appName ?? record.appCode ?? record.id}`,
    value: Number(record.id),
  }));
}

async function loadArtifacts() {
  const page = await artifactApi.page({ pageNum: 1, pageSize: 200 });
  artifactOptions.value = page.records.map((record) => ({
    label: `${record.artifactName ?? record.id} - ${record.artifactUri ?? ''}`,
    value: Number(record.id),
  }));
}

async function submit() {
  await formRef.value?.validate();
  if (!form.appId || !form.artifactId) {
    return;
  }
  submitting.value = true;
  try {
    lastCreatedVersion.value = await createReleaseVersion({
      appId: form.appId,
      versionNo: form.versionNo,
      artifactId: form.artifactId,
      gitCommit: form.gitCommit,
      branchName: form.branchName,
      buildNo: form.buildNo,
    });
    ElMessage.success('版本创建成功');
    dialogVisible.value = false;
    await router.replace({ path: '/release/versions' });
    await fetchData();
  } finally {
    submitting.value = false;
  }
}

function handleSizeChange() {
  pageNum.value = 1;
  fetchData();
}

// V-D: navigateToCurrentVersion — jump to current versions filtered by appId
function navigateToCurrentVersion(row: Record<string, unknown>) {
  const query: Record<string, string> = {};
  if (row.appId) query.appId = String(row.appId);
  router.push({ path: '/release/current-versions', query });
}

onMounted(async () => {
  await fetchData();
  const artifactId = Number(route.query.artifactId);
  if (artifactId) {
    await openCreateDialog(artifactId);
  }
});
</script>

<style scoped lang="scss">
.content-card {
  padding: var(--space-lg);
}

.toolbar-actions {
  display: flex;
  gap: var(--ih-spacing-sm);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--ih-section-gap);
  padding-top: var(--ih-spacing-md);
  border-top: 1px solid var(--ih-border-light);
}

.version-success {
  margin-bottom: var(--ih-section-gap);
}

.version-result {
  display: grid;
  gap: var(--ih-spacing-1);
  margin-top: var(--ih-spacing-3);
  line-height: 1.5;
}
</style>
