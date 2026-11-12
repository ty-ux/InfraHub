<template>
  <el-dialog
    :model-value="modelValue"
    title="配置完整性检查"
    width="800px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="loading" class="loading-container">
      <el-skeleton :rows="6" animated />
    </div>
    <div v-else-if="result" class="check-content">
      <!-- Overall Status -->
      <el-alert
        :title="overallStatusTitle"
        :type="result.overallComplete ? 'success' : 'error'"
        show-icon
        :closable="false"
        class="overall-status"
      >
        <template #default>
          <span v-if="result.overallComplete">配置完整，可正常发布</span>
          <span v-else>缺少 {{ missingCount }} 项必需配置</span>
        </template>
      </el-alert>

      <!-- V-B: Missing items guidance (Contract 9.2 V-B) -->
      <div v-if="!result.overallComplete && missingGuideItems.length > 0" class="missing-guide-section">
        <div class="section-title">缺失项引导</div>
        <div
          v-for="(item, index) in missingGuideItems"
          :key="index"
          class="guide-item"
        >
          <div class="guide-header">
            <el-tag type="danger" size="small">{{ item.key }}</el-tag>
            <span v-if="item.source" class="guide-source">{{ getSourceLabel(item.source) }}</span>
            <span v-if="item.serviceName" class="guide-service">{{ item.serviceName }}</span>
          </div>
          <div class="guide-body">
            <div class="guide-desc">{{ getConfigGuidance(item.key) }}</div>
            <div v-if="getConfigRecommended(item.key)" class="guide-recommended">
              推荐值：<code>{{ getConfigRecommended(item.key) }}</code>
            </div>
          </div>
          <el-button
            size="small"
            type="primary"
            text
            class="guide-action"
            @click="openConfigGuide(item)"
          >
            <el-icon><Edit /></el-icon>
            前往配置
          </el-button>
        </div>
      </div>

      <!-- Summary -->
      <div class="check-summary">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="应用">{{ result.appName }}</el-descriptions-item>
          <el-descriptions-item label="环境">{{ result.envName }}</el-descriptions-item>
          <el-descriptions-item label="检查时间">{{ formatDateTime(result.checkedAt) }}</el-descriptions-item>
          <el-descriptions-item label="服务数">{{ result.services?.length || 0 }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- Global Missing Keys -->
      <div v-if="result.missingKeys && result.missingKeys.length > 0" class="global-missing">
        <div class="section-title">全局缺失配置项</div>
        <el-tag v-for="key in result.missingKeys" :key="key" type="danger" size="small" class="missing-tag">
          {{ key }}
        </el-tag>
      </div>

      <!-- Global Warnings -->
      <div v-if="result.warnings && result.warnings.length > 0" class="global-warnings">
        <div class="section-title">警告</div>
        <el-alert v-for="warning in result.warnings" :key="warning" type="warning" :closable="false">
          {{ warning }}
        </el-alert>
      </div>

      <!-- Service Details -->
      <div v-if="result.services && result.services.length > 0" class="services-section">
        <div class="section-title">服务配置详情</div>
        <el-collapse>
          <el-collapse-item
            v-for="service in result.services"
            :key="service.serviceId"
            :name="service.serviceId"
          >
            <template #title>
              <div class="service-header">
                <span class="service-name">{{ service.serviceName }}</span>
                <el-tag :type="service.complete ? 'success' : 'danger'" size="small">
                  {{ service.complete ? '完整' : '不完整' }}
                </el-tag>
                <span v-if="service.missingKeys && service.missingKeys.length > 0" class="missing-count">
                  缺失 {{ service.missingKeys.length }} 项
                </span>
              </div>
            </template>

            <!-- Service Missing Keys -->
            <div v-if="service.missingKeys && service.missingKeys.length > 0" class="service-missing">
              <span class="label">缺失项：</span>
              <el-tag v-for="key in service.missingKeys" :key="key" type="danger" size="small" class="missing-tag">
                {{ key }}
              </el-tag>
            </div>

            <!-- Service Warnings -->
            <div v-if="service.warnings && service.warnings.length > 0" class="service-warnings">
              <el-alert v-for="warning in service.warnings" :key="warning" type="warning" :closable="false" size="small">
                {{ warning }}
              </el-alert>
            </div>

            <!-- Config Items Table -->
            <el-table v-if="service.configItems && service.configItems.length > 0" :data="service.configItems" border size="small">
              <el-table-column prop="configKey" label="配置项" min-width="180" />
              <el-table-column prop="configValue" label="值" min-width="140">
                <template #default="{ row }">
                  <span v-if="row.valueType === 'SECRET_REF'" class="secret-value">***</span>
                  <span v-else>{{ row.configValue || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="valueType" label="类型" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.valueType === 'SECRET_REF' ? 'warning' : 'info'" size="small">
                    {{ row.valueType === 'SECRET_REF' ? '密钥' : '明文' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="required" label="必需" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.required ? 'danger' : 'info'" size="small">
                    {{ row.required ? '必需' : '可选' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="hasValue" label="有值" width="80">
                <template #default="{ row }">
                  <el-tag :type="row.hasValue ? 'success' : 'danger'" size="small">
                    {{ row.hasValue ? '有' : '无' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="source" label="来源" width="100">
                <template #default="{ row }">
                  <span>{{ getSourceLabel(row.source) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="duplicateCount" label="同键条数" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.ambiguous" type="danger" size="small">
                    {{ row.duplicateCount }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
    <el-empty v-else-if="!loading" description="请先选择应用和环境" />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { Edit } from '@element-plus/icons-vue';
import type { DeployConfigCheckResponse } from '@/api/release';

interface MissingGuideItem {
  key: string;
  source?: string;
  serviceName?: string;
}

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  result: DeployConfigCheckResponse | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

const router = useRouter();

const overallStatusTitle = computed(() => {
  if (!props.result) return '';
  return props.result.overallComplete ? '配置检查通过' : '配置检查未通过';
});

const missingCount = computed(() => {
  if (!props.result) return 0;
  const globalMissing = props.result.missingKeys?.length || 0;
  const serviceMissing = props.result.services?.reduce(
    (sum, s) => sum + (s.missingKeys?.length || 0),
    0
  ) || 0;
  return globalMissing + serviceMissing;
});

// V-B: Build missing guide items from result
const missingGuideItems = computed<MissingGuideItem[]>(() => {
  if (!props.result || props.result.overallComplete) return [];
  const items: MissingGuideItem[] = [];

  // Global missing keys
  if (props.result.missingKeys) {
    for (const key of props.result.missingKeys) {
      items.push({ key });
    }
  }

  // Service-level missing keys
  if (props.result.services) {
    for (const service of props.result.services) {
      if (service.missingKeys) {
        for (const key of service.missingKeys) {
          items.push({ key, serviceName: service.serviceName });
        }
      }
    }
  }

  return items;
});

function formatDateTime(date: string | null | undefined): string {
  if (!date) return '-';
  const str = String(date);
  if (str.includes('T')) {
    return str.replace('T', ' ').substring(0, 19);
  }
  return str;
}

function getSourceLabel(source: string | undefined): string {
  const labels: Record<string, string> = {
    APP: '应用级',
    ENV: '环境级',
    SERVICE: '服务级',
  };
  return labels[source || ''] || source || '-';
}

// V-B: Config key guidance descriptions
const configGuidanceMap: Record<string, { desc: string; recommended?: string }> = {
  'deploy.package.path': {
    desc: '部署包存放路径，用于指定制品包下载和解压的位置',
    recommended: '/opt/deploy/packages',
  },
  'deploy.deploy.path': {
    desc: '实际部署路径，应用代码/配置最终部署到的目标目录',
    recommended: '/opt/deploy/app',
  },
  'deploy.script.path': {
    desc: '部署脚本路径，包含启动、停止、健康检查等脚本',
    recommended: '/opt/deploy/scripts',
  },
  'deploy.log.path': {
    desc: '应用日志路径，用于收集和查看应用运行日志',
    recommended: '/opt/deploy/logs',
  },
  'deploy.restart.command': {
    desc: '应用重启命令，在部署完成后执行的服务重启操作',
  },
  'deploy.health.check.url': {
    desc: '健康检查 URL，部署后用于验证服务是否正常启动',
  },
  'docker.image': {
    desc: 'Docker 镜像地址，指定要部署的容器镜像',
  },
  'kubernetes.namespace': {
    desc: 'Kubernetes 命名空间，指定部署到的目标命名空间',
  },
  'kubernetes.deployment': {
    desc: 'Kubernetes Deployment 名称，指定要更新的部署对象',
  },
};

function getConfigGuidance(key: string): string {
  if (configGuidanceMap[key]) return configGuidanceMap[key].desc;
  // Pattern matching for unknown keys
  if (key.startsWith('deploy.')) return `部署配置项 "${key}"，用于指定部署过程中的必要参数`;
  if (key.startsWith('docker.')) return `Docker 配置项 "${key}"，用于容器化部署场景`;
  if (key.startsWith('kubernetes.')) return `Kubernetes 配置项 "${key}"，用于 K8s 部署场景`;
  return `配置项 "${key}" 为必需项，请补充完整`;
}

function getConfigRecommended(key: string): string | undefined {
  return configGuidanceMap[key]?.recommended;
}

// V-B: openDeployConfigGuide operation (Contract 7)
function openConfigGuide(item: MissingGuideItem) {
  router.push({
    path: '/config/env-configs',
    query: {
      ...(props.result?.appId ? { appId: String(props.result.appId) } : {}),
      ...(props.result?.envId ? { envId: String(props.result.envId) } : {}),
      highlight: item.key,
    },
  });
  emit('update:modelValue', false);
}
</script>

<style scoped lang="scss">
.loading-container {
  padding: 20px;
}

.check-content {
  padding: 0 8px;
}

.overall-status {
  margin-bottom: 16px;
}

.check-summary {
  margin-bottom: 16px;
}

.section-title {
  font-weight: 500;
  font-size: 14px;
  color: var(--ih-text-primary);
  margin-bottom: 12px;
}

.global-missing {
  margin-bottom: 16px;

  .missing-tag {
    margin-right: 8px;
    margin-bottom: 4px;
  }
}

.global-warnings {
  margin-bottom: 16px;

  .el-alert {
    margin-bottom: 8px;
  }
}

.services-section {
  margin-top: 16px;
}

.service-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.service-name {
  font-weight: 500;
}

.missing-count {
  font-size: 12px;
  color: var(--ih-danger);
}

.service-missing {
  margin-bottom: 12px;

  .label {
    font-size: 13px;
    color: var(--ih-text-secondary);
    margin-right: 8px;
  }

  .missing-tag {
    margin-right: var(--space-2xs);
    margin-bottom: 4px;
  }
}

.service-warnings {
  margin-bottom: 12px;

  .el-alert {
    margin-bottom: var(--space-2xs);
  }
}

.secret-value {
  color: var(--ih-warning);
}

// V-B: Missing guide styles (U-stage visual system)
.missing-guide-section {
  margin-bottom: var(--space-md);
  padding: var(--space-sm);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-field);
  background-color: transparent;
}

.guide-item {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--space-sm);
  padding: var(--space-xs) 0;
  border-top: var(--rule-hair) solid var(--color-rule);
  background-color: transparent;

  &:first-child {
    border-top: 0;
  }
}

.guide-header {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
  min-width: 200px;
}

.guide-source {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.guide-service {
  font-size: 12px;
  color: var(--ih-primary);
}

.guide-body {
  flex: 1;
  min-width: 200px;
}

.guide-desc {
  font-size: 13px;
  color: var(--ih-text-secondary);
  line-height: 1.5;
}

.guide-recommended {
  margin-top: var(--ih-spacing-1);
  font-size: 12px;
  color: var(--ih-text-secondary);

  code {
    padding: 1px 4px;
    background-color: var(--ih-bg-row-hover);
    border-radius: var(--ih-radius-sm);
    font-family: var(--ih-font-family-mono);
    font-size: 12px;
    color: var(--ih-primary);
  }
}

.guide-action {
  flex-shrink: 0;
  align-self: center;
}
</style>
