<template>
  <section>
    <div class="page-toolbar">
      <div class="page-title">
        <h2>制品管理</h2>
        <p>上传 JAR 或 Compose 清单到目标机 incoming 目录，并登记为可发布制品。</p>
      </div>
      <div class="toolbar-actions">
        <el-button @click="fetchData">刷新</el-button>
        <el-button type="primary" @click="openUploadDialog">上传制品</el-button>
      </div>
    </div>

    <div class="page-card content-card">
      <el-alert
        v-if="lastUploadedArtifact"
        type="success"
        show-icon
        :closable="false"
        class="upload-success"
      >
        <template #title>
          已上传 {{ lastUploadedArtifact.artifactName }}
          <el-button link type="primary" @click="goCreateVersion">创建版本</el-button>
        </template>
        <div class="upload-result">
          <div>制品ID：{{ lastUploadedArtifact.artifactId }}</div>
          <div>目标路径：{{ lastUploadedArtifact.artifactUri }}</div>
          <div>校验和：{{ lastUploadedArtifact.checksum }}</div>
          <div>大小：{{ lastUploadedArtifact.sizeBytes }} bytes</div>
        </div>
      </el-alert>
      <el-table :data="records" v-loading="loading" row-key="id" empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="artifactType" label="制品类型" width="120" />
        <el-table-column prop="artifactName" label="制品名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="artifactUri" label="制品地址" min-width="280" show-overflow-tooltip />
        <el-table-column prop="checksum" label="校验和" min-width="220" show-overflow-tooltip />
        <el-table-column prop="sizeBytes" label="大小" width="120" />
        <el-table-column prop="repositoryType" label="仓库类型" width="140" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="navigateToVersions(row)">查看版本</el-button>
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

    <el-dialog v-model="uploadDialogVisible" title="上传制品" width="720px" @closed="resetUploadDialog">
      <el-form ref="uploadFormRef" :model="uploadForm" :rules="uploadRules" label-width="120px">
        <el-form-item label="服务" prop="serviceId">
          <el-select v-model="uploadForm.serviceId" filterable clearable style="width: 100%">
            <el-option v-for="option in serviceOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境" prop="envId">
          <el-select v-model="uploadForm.envId" filterable clearable style="width: 100%">
            <el-option v-for="option in envOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="运行实例" prop="runtimeServiceInstanceId">
          <el-select
            v-model="uploadForm.runtimeServiceInstanceId"
            :loading="instanceLoading"
            filterable
            clearable
            style="width: 100%"
          >
            <el-option v-for="option in instanceOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="制品名称">
          <el-input v-model="uploadForm.artifactName" placeholder="默认使用文件名" />
        </el-form-item>
        <el-form-item label="制品文件" prop="file">
          <el-upload v-model:file-list="fileList" :auto-upload="false" :limit="1" accept=".jar,.compose.yml" @change="handleFileChange" @remove="handleFileRemove">
            <el-button>选择文件</el-button>
            <template #tip>
              <div class="upload-tip">支持 .jar 和 .compose.yml，最大 200 MB。目标机已有的同名文件会保留。</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-alert v-if="deployConfig" type="info" :closable="false" show-icon>
          <template #title>
            上传目录：{{ deployConfig.deployRoot }}/incoming
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules, UploadUserFile } from 'element-plus';
import { artifactApi, uploadArtifact, type UploadArtifactResponse } from '@/api/release';
import { envApi, serviceApi } from '@/api/resource';
import { getRuntimeServiceInstanceDeployConfig, listRuntimeServiceInstances, type RuntimeServiceInstanceDeployConfig } from '@/api/runtime';

interface Option {
  label: string;
  value: number;
}

const router = useRouter();
const records = ref<Record<string, unknown>[]>([]);
const loading = ref(false);
const pageNum = ref(1);
const pageSize = ref(10);
const total = ref(0);
const uploadDialogVisible = ref(false);
const uploading = ref(false);
const instanceLoading = ref(false);
const uploadFormRef = ref<FormInstance>();
const fileList = ref<UploadUserFile[]>([]);
const envOptions = ref<Option[]>([]);
const serviceOptions = ref<Option[]>([]);
const instanceOptions = ref<Option[]>([]);
const deployConfig = ref<RuntimeServiceInstanceDeployConfig | null>(null);
const lastUploadedArtifact = ref<UploadArtifactResponse | null>(null);

const uploadForm = reactive<{
  serviceId?: number;
  envId?: number;
  runtimeServiceInstanceId?: number;
  artifactName: string;
  file?: File;
}>({
  artifactName: '',
});

const uploadRules = computed<FormRules>(() => ({
  serviceId: [{ required: true, message: '请选择服务', trigger: 'change' }],
  envId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  runtimeServiceInstanceId: [{ required: true, message: '请选择运行实例', trigger: 'change' }],
  file: [{ required: true, message: '请选择制品文件', trigger: 'change' }],
}));

async function fetchData() {
  loading.value = true;
  try {
    const page = await artifactApi.page({ pageNum: pageNum.value, pageSize: pageSize.value });
    records.value = page.records;
    total.value = page.total;
  } finally {
    loading.value = false;
  }
}

async function openUploadDialog() {
  uploadDialogVisible.value = true;
  await Promise.all([loadServices(), loadEnvs()]);
}

function resetUploadDialog() {
  uploadForm.serviceId = undefined;
  uploadForm.envId = undefined;
  uploadForm.runtimeServiceInstanceId = undefined;
  uploadForm.artifactName = '';
  uploadForm.file = undefined;
  fileList.value = [];
  instanceOptions.value = [];
  deployConfig.value = null;
  uploadFormRef.value?.clearValidate();
}

async function loadServices() {
  const page = await serviceApi.page({ pageNum: 1, pageSize: 200 });
  serviceOptions.value = page.records.map((record) => ({
    label: `${record.serviceName ?? record.serviceCode ?? record.id}`,
    value: Number(record.id),
  }));
}

async function loadEnvs() {
  const page = await envApi.page({ pageNum: 1, pageSize: 200 });
  envOptions.value = page.records.map((record) => ({
    label: `${record.envName ?? record.envCode ?? record.id}`,
    value: Number(record.id),
  }));
}

async function loadInstances() {
  uploadForm.runtimeServiceInstanceId = undefined;
  instanceOptions.value = [];
  deployConfig.value = null;
  if (!uploadForm.serviceId || !uploadForm.envId) {
    return;
  }
  instanceLoading.value = true;
  try {
    const instances = await listRuntimeServiceInstances({ serviceId: uploadForm.serviceId, envId: uploadForm.envId });
    instanceOptions.value = instances.map((instance) => ({
      label: `${instance.instanceName ?? `实例 ${instance.id}`} (#${instance.id})`,
      value: Number(instance.id),
    }));
  } finally {
    instanceLoading.value = false;
  }
}

async function loadDeployConfig() {
  deployConfig.value = null;
  if (!uploadForm.runtimeServiceInstanceId) {
    return;
  }
  deployConfig.value = await getRuntimeServiceInstanceDeployConfig(uploadForm.runtimeServiceInstanceId);
}

function handleFileChange(file: UploadUserFile) {
  uploadForm.file = file.raw;
  uploadFormRef.value?.validateField('file');
}

function handleFileRemove() {
  uploadForm.file = undefined;
}

async function submitUpload() {
  await uploadFormRef.value?.validate();
  if (!uploadForm.runtimeServiceInstanceId || !uploadForm.file) {
    return;
  }
  uploading.value = true;
  try {
    lastUploadedArtifact.value = await uploadArtifact({
      runtimeServiceInstanceId: uploadForm.runtimeServiceInstanceId,
      file: uploadForm.file,
      artifactName: uploadForm.artifactName,
    });
    ElMessage.success('制品上传成功');
    uploadDialogVisible.value = false;
    await fetchData();
  } finally {
    uploading.value = false;
  }
}

function goCreateVersion() {
  if (!lastUploadedArtifact.value) {
    return;
  }
  router.push({ path: '/release/versions', query: { artifactId: lastUploadedArtifact.value.artifactId } });
}

// V-D: navigateToVersions — jump to release versions filtered by artifactId
function navigateToVersions(row: Record<string, unknown>) {
  const artifactId = row.id;
  if (!artifactId) return;
  router.push({ path: '/release/versions', query: { artifactId: String(artifactId) } });
}

function handleSizeChange() {
  pageNum.value = 1;
  fetchData();
}

watch(() => [uploadForm.serviceId, uploadForm.envId], loadInstances);
watch(() => uploadForm.runtimeServiceInstanceId, loadDeployConfig);

onMounted(fetchData);
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

.upload-success {
  margin-bottom: var(--ih-section-gap);
}

.upload-tip {
  color: var(--ih-text-secondary);
  margin-top: var(--ih-spacing-2);
}

.upload-result {
  display: grid;
  gap: var(--ih-spacing-1);
  margin-top: var(--ih-spacing-3);
  line-height: 1.5;
  word-break: break-all;
}
</style>
