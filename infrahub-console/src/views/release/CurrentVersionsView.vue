<template>
  <CrudPage v-if="config" ref="crudPageRef" :config="config">
    <!-- 运行实例列：只表达"这个服务环境有几个实例"，不按数组顺序挑一个实例代表整个服务 -->
    <template #runtimeInstance="{ row }">
      <span :class="instanceCellClass(row)">{{ instanceCellText(row) }}</span>
    </template>
    <!-- 部署覆盖列：指针只说“最近一次成功发布的版本号”，覆盖范围由后端证据算出，页面不推断 -->
    <template #coverage="{ row }">
      <DeployCoverageTag :coverage="row.coverage as DeployCoverageRow | undefined" />
    </template>
    <!-- 当前登记版本一致性：与覆盖范围是两件事，单独一列，避免把历史覆盖读成当前一致 -->
    <template #registrationConsistency="{ row }">
      <VersionRegistrationTag :consistency="row.registrationConsistency as RegistrationConsistencyRow | undefined" />
    </template>
    <template #row-actions="{ row }">
      <div class="row-actions">
        <div class="row-action-buttons">
          <el-tooltip :content="t('currentVersions.tipStatus')" placement="top">
            <el-button link type="primary" class="is-row-primary-action" @click="openStatus(row)">
              {{ t('currentVersions.actionStatus') }}
            </el-button>
          </el-tooltip>
          <el-tooltip :content="t('currentVersions.tipLogs')" placement="top">
            <el-button link type="info" @click="openLogs(row)">{{ t('currentVersions.actionLogs') }}</el-button>
          </el-tooltip>
          <el-button v-if="canRollback" link type="primary" @click="navigateToApplicationRollback(row)">{{ t('pipelineDelivery.rollbackTitle') }}</el-button>
          <!-- 次要配置与导航操作收进「更多」：六个入口的能力一个不少，只是分层。
               触发器必须是可直接聚焦的真实按钮：ElDropdown 的默认插槽只放按钮本身，
               提示性 Tooltip 移到外层包裹。原先 Tooltip 嵌在插槽里会让插槽成为多根节点，
               触发器引用无法绑定，鼠标点击与键盘展开都不会打开菜单。 -->
          <el-tooltip :content="t('currentVersions.tipMore')" placement="top">
            <span class="row-more-wrap">
              <el-dropdown trigger="click" placement="bottom-end" class="row-more">
                <el-button link type="info" class="row-more-trigger">{{ t('currentVersions.actionMore') }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="openDeployConfig(row)">{{ t('currentVersions.actionDeployConfig') }}</el-dropdown-item>
                    <el-dropdown-item @click="openLatestDeployRecords(row)">{{ t('currentVersions.actionLatestDeployRecords') }}</el-dropdown-item>
                    <el-dropdown-item @click="navigateToReleaseOrder(row)">{{ t('currentVersions.actionReleaseOrder') }}</el-dropdown-item>
                    <el-dropdown-item @click="navigateToRuntimeInstance(row)">{{ t('currentVersions.actionRuntimeInstance') }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </el-tooltip>
        </div>
      </div>
    </template>
  </CrudPage>

  <!-- 运行状态：目标实例、本通道自己的部署配置读取（端口来源）与状态证据，互不共享上下文 -->
  <el-dialog v-model="statusVisible" :title="t('currentVersions.statusTitle')" width="min(760px, 94vw)">
    <el-alert
      v-if="statusInstanceState === 'UNKNOWN'"
      :title="t('currentVersions.instanceUnknownTitle')"
      :description="t('currentVersions.instanceUnknownHint')"
      type="warning"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <template v-else-if="statusInstanceState === 'ERROR'">
      <!-- 作用域错误与查询失败都要说清，但都不能被讲成"没有实例" -->
      <el-alert :title="statusInstancesAlertTitle" type="error" show-icon :closable="false" class="diagnostic-alert">
        <div class="read-failure-hint">{{ statusInstancesAlertHint }}</div>
      </el-alert>
      <div v-if="statusInstancesRawError" class="raw-error-block">
        <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
        <pre class="raw-error-text">{{ statusInstancesRawError }}</pre>
      </div>
      <el-button size="small" :loading="statusInstancesLoading" @click="reloadStatusInstances">
        {{ t('currentVersions.reloadInstances') }}
      </el-button>
    </template>
    <el-alert
      v-else-if="statusInstanceState === 'NONE'"
      :title="t('currentVersions.instanceNoneTitle')"
      :description="t('currentVersions.instanceNoneHint')"
      type="info"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <el-skeleton v-else-if="statusInstanceState === 'LOADING'" :rows="4" animated />
    <template v-else>
      <div v-if="statusInstanceState === 'MULTIPLE'" class="target-select">
        <div class="target-select-title">{{ t('currentVersions.selectInstanceTitle', { count: statusInstances.length }) }}</div>
        <div class="target-select-row">
          <el-select
            v-model="statusSelectedInstanceId"
            class="target-select-input"
            :placeholder="t('currentVersions.selectInstancePlaceholder')"
            @change="onStatusInstanceChange"
          >
            <el-option
              v-for="instance in statusInstances"
              :key="instance.id"
              :label="instanceOptionLabel(instance)"
              :value="instance.id"
            />
          </el-select>
          <el-button type="primary" :loading="statusLoading && !statusEvidence" @click="confirmStatusRead">
            {{ t('currentVersions.confirmRead') }}
          </el-button>
        </div>
      </div>
      <template v-if="statusInstance">
        <div class="target-header">
          <span class="target-header-item"><b>{{ t('currentVersions.dialogCurrentVersionId') }}:</b> {{ statusTarget?.currentVersionId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogService') }}:</b> {{ targetServiceText(statusTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogEnv') }}:</b> {{ targetEnvText(statusTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceName') }}:</b> {{ statusInstance.instanceName || '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceId') }}:</b> {{ statusInstance.id }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogHostId') }}:</b> {{ statusInstance.hostId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceVersion') }}:</b> {{ statusEvidence?.versionId ?? statusInstance.versionId ?? '-' }}</span>
          <span class="target-header-item">
            <b>{{ t('currentVersions.dialogEvidenceAt') }}:</b>
            {{ statusRetrievedAt ? formatDateTime(statusRetrievedAt) : t('currentVersions.dialogNotRetrieved') }}
          </span>
        </div>
        <el-skeleton v-if="statusLoading && !statusEvidence" :rows="6" animated />
        <template v-else>
          <el-alert v-if="statusFailure" :title="t('currentVersions.readFailed')" type="error" show-icon :closable="false" class="diagnostic-alert">
            <div class="read-failure-hint">{{ statusFailureHint }}</div>
            <div v-if="statusFailureAdvice" class="read-failure-hint">{{ statusFailureAdvice }}</div>
            <div v-if="statusFailureRawError" class="raw-error-block">
              <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
              <pre class="raw-error-text">{{ statusFailureRawError }}</pre>
            </div>
            <div v-if="statusEvidence" class="read-failure-hint">{{ t('currentVersions.staleEvidenceNotice') }}</div>
          </el-alert>
          <!-- 只读重读：目标实例已确定后才出现；本次读取失败时为"只读重试"，已有证据时仍可手动重读一次 -->
          <div v-if="statusInstance && (statusFailure || statusEvidence)" class="retry-row">
            <el-button size="small" :loading="statusLoading" @click="retryStatusRead">
              {{ statusFailure ? t('currentVersions.readOnlyRetry') : t('currentVersions.readAgain') }}
            </el-button>
            <span class="read-again-hint">{{ t('currentVersions.readAgainHint') }}</span>
          </div>
          <!-- 附属配置（端口/来源）无法确认时明确说明；不影响上面状态证据本身的展示 -->
          <el-alert
            v-if="statusConfigIssue"
            :title="t('currentVersions.statusConfigUnavailableTitle')"
            :description="statusConfigIssueHint"
            :type="statusConfigIssue === 'SCOPE' ? 'warning' : 'info'"
            show-icon
            :closable="false"
            class="diagnostic-alert"
          />
          <template v-if="statusEvidence">
            <div class="quick-summary">
              <div class="summary-item" :class="triStateSummaryClass(statusRunningState)">
                <span class="summary-label">{{ statusEvidence.composeProject ? 'Docker Compose' : t('currentVersions.statusProcess') }}</span>
                <el-tag :type="triStateTagType(statusRunningState)" size="small">
                  {{ boolText(statusRunningState, 'currentVersions.statusRunning', 'currentVersions.statusNotRunning') }}
                </el-tag>
              </div>
              <div class="summary-item" :class="triStateSummaryClass(statusPortState)">
                <!-- 端口只来自通过目标校验的附属配置；不符时一律显示"无法确认"，不展示外来响应的端口 -->
                <span class="summary-label">
                  <span>{{ t('currentVersions.statusPort') }}</span>
                  <span class="summary-port" :class="{ 'summary-port-unconfirmed': statusConfig?.deployPort == null }">
                    {{ statusConfig?.deployPort ?? t('currentVersions.statusUnconfirmed') }}
                  </span>
                </span>
                <el-tag :type="triStateTagType(statusPortState)" size="small">
                  {{ boolText(statusPortState, 'currentVersions.statusListening', 'currentVersions.statusNotListening') }}
                </el-tag>
              </div>
              <div class="summary-item" :class="statusHealthClass">
                <span class="summary-label">{{ t('currentVersions.statusHealth') }}</span>
                <el-tag :type="statusHealthTagType" size="small">{{ statusHealthRaw ?? t('currentVersions.statusUnconfirmed') }}</el-tag>
              </div>
            </div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item :label="t('currentVersions.statusConfigSource')">{{ statusConfig?.source ?? t('currentVersions.statusUnconfirmed') }}</el-descriptions-item>
              <el-descriptions-item v-if="statusEvidence.composeProject" label="Docker Compose">{{ statusEvidence.composeProject }}</el-descriptions-item>
              <el-descriptions-item v-else :label="t('currentVersions.statusPid')">{{ statusEvidence.pid || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="t('currentVersions.statusCheckedAt')">{{ statusEvidence.checkedAt || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="statusEvidence.errorMessage" :label="t('currentVersions.statusErrorLayer')">
                <el-tag type="warning" size="small">{{ diagnoseErrorLayer(statusEvidence) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item v-if="statusEvidence.errorMessage" :label="t('currentVersions.statusErrorMessage')" :span="2">
                {{ statusEvidence.errorMessage }}
              </el-descriptions-item>
            </el-descriptions>
            <section v-if="statusEvidence.healthBody" class="diagnostic-section">
              <div class="diagnostic-title">{{ t('currentVersions.statusHealthBody') }}</div>
              <pre class="diagnostic-log">{{ statusEvidence.healthBody }}</pre>
            </section>
          </template>
          <el-empty v-else-if="!statusFailure" :description="t('currentVersions.statusEmpty')" />
        </template>
      </template>
    </template>
  </el-dialog>

  <!-- 运行日志：刷新与复制只使用日志弹窗自己的目标实例 -->
  <el-dialog v-model="logsVisible" :title="t('currentVersions.logsTitle')" width="min(1000px, 96vw)">
    <el-alert
      v-if="logsInstanceState === 'UNKNOWN'"
      :title="t('currentVersions.instanceUnknownTitle')"
      :description="t('currentVersions.instanceUnknownHint')"
      type="warning"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <template v-else-if="logsInstanceState === 'ERROR'">
      <el-alert :title="logsInstancesAlertTitle" type="error" show-icon :closable="false" class="diagnostic-alert">
        <div class="read-failure-hint">{{ logsInstancesAlertHint }}</div>
      </el-alert>
      <div v-if="logsInstancesRawError" class="raw-error-block">
        <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
        <pre class="raw-error-text">{{ logsInstancesRawError }}</pre>
      </div>
      <el-button size="small" :loading="logsInstancesLoading" @click="reloadLogsInstances">
        {{ t('currentVersions.reloadInstances') }}
      </el-button>
    </template>
    <el-alert
      v-else-if="logsInstanceState === 'NONE'"
      :title="t('currentVersions.instanceNoneTitle')"
      :description="t('currentVersions.instanceNoneHint')"
      type="info"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <el-skeleton v-else-if="logsInstanceState === 'LOADING'" :rows="4" animated />
    <template v-else>
      <div v-if="logsInstanceState === 'MULTIPLE'" class="target-select">
        <div class="target-select-title">{{ t('currentVersions.selectInstanceTitle', { count: logsInstances.length }) }}</div>
        <div class="target-select-row">
          <el-select
            v-model="logsSelectedInstanceId"
            class="target-select-input"
            :placeholder="t('currentVersions.selectInstancePlaceholder')"
            @change="onLogsInstanceChange"
          >
            <el-option
              v-for="instance in logsInstances"
              :key="instance.id"
              :label="instanceOptionLabel(instance)"
              :value="instance.id"
            />
          </el-select>
          <el-button type="primary" :loading="logsLoading && !logsEvidence" @click="confirmLogsRead">
            {{ t('currentVersions.confirmRead') }}
          </el-button>
        </div>
      </div>
      <template v-if="logsInstance">
        <div class="target-header">
          <span class="target-header-item"><b>{{ t('currentVersions.dialogCurrentVersionId') }}:</b> {{ logsTarget?.currentVersionId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogService') }}:</b> {{ targetServiceText(logsTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogEnv') }}:</b> {{ targetEnvText(logsTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceName') }}:</b> {{ logsInstance.instanceName || '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceId') }}:</b> {{ logsInstance.id }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogHostId') }}:</b> {{ logsInstance.hostId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceVersion') }}:</b> {{ logsEvidence?.versionId ?? logsInstance.versionId ?? '-' }}</span>
          <span class="target-header-item">
            <b>{{ t('currentVersions.dialogEvidenceAt') }}:</b>
            {{ logsRetrievedAt ? formatDateTime(logsRetrievedAt) : t('currentVersions.dialogNotRetrieved') }}
          </span>
        </div>
        <div class="logs-notice">{{ t('currentVersions.logsTailNotice', { lines: logsLines }) }}</div>
        <RuntimeLogPanel
          :active="logsVisible" :target-key="logsInstance.id"
          :content="logsEvidence?.content" :log-path="logsEvidence?.logPath" :fetched-at="logsRetrievedAt || undefined"
          :loading="logsLoading" :failed="Boolean(logsFailure) || (Boolean(logsEvidence) && logsEvidence?.success !== true)"
          :downloadable="logsEvidence?.success === true" :lines="logsLines" :empty-text="t('currentVersions.logsEmpty')"
          @refresh="retryLogsRead" @update:lines="changeLogsLines"
        >
          <template #actions>
            <el-button size="small" :disabled="!logsEvidence?.content" @click="copyLogs">{{ t('currentVersions.logsCopy') }}</el-button>
          </template>
          <el-alert v-if="logsFailure" :title="t('currentVersions.readFailed')" type="error" show-icon :closable="false" class="diagnostic-alert">
            <div class="read-failure-hint">{{ logsFailureHint }}</div>
            <div v-if="logsFailureAdvice" class="read-failure-hint">{{ logsFailureAdvice }}</div>
            <div v-if="logsFailureRawError" class="raw-error-block">
              <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
              <pre class="raw-error-text">{{ logsFailureRawError }}</pre>
            </div>
            <div v-if="logsEvidence" class="read-failure-hint">{{ t('currentVersions.staleEvidenceNotice') }}</div>
          </el-alert>
          <div v-if="logsFailure" class="retry-row">
            <el-button size="small" :loading="logsLoading" @click="retryLogsRead">{{ t('currentVersions.readOnlyRetry') }}</el-button>
            <span class="read-again-hint">{{ t('currentVersions.readAgainHint') }}</span>
          </div>
          <!-- 后端 success=false：读到了响应但没有日志内容，与"读取请求失败"分开表达 -->
          <el-alert
            v-if="logsEvidence && logsEvidence.success === false"
            :title="logsEvidence.errorMessage || t('currentVersions.logsUnavailable')"
            type="error"
            show-icon
            :closable="false"
            class="diagnostic-alert"
          />
          <el-alert
            v-else-if="logsEvidence && logsEvidence.success !== true"
            :title="t('currentVersions.logsSuccessUnknown')"
            type="info"
            show-icon
            :closable="false"
            class="diagnostic-alert"
          />
        </RuntimeLogPanel>
      </template>
    </template>
  </el-dialog>

  <!-- 部署配置：与运行状态弹窗各自持有目标与证据，不共用任何可变状态 -->
  <el-dialog v-model="configVisible" :title="t('currentVersions.deployConfigTitle')" width="min(760px, 94vw)">
    <el-alert
      v-if="configInstanceState === 'UNKNOWN'"
      :title="t('currentVersions.instanceUnknownTitle')"
      :description="t('currentVersions.instanceUnknownHint')"
      type="warning"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <template v-else-if="configInstanceState === 'ERROR'">
      <el-alert :title="configInstancesAlertTitle" type="error" show-icon :closable="false" class="diagnostic-alert">
        <div class="read-failure-hint">{{ configInstancesAlertHint }}</div>
      </el-alert>
      <div v-if="configInstancesRawError" class="raw-error-block">
        <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
        <pre class="raw-error-text">{{ configInstancesRawError }}</pre>
      </div>
      <el-button size="small" :loading="configInstancesLoading" @click="reloadConfigInstances">
        {{ t('currentVersions.reloadInstances') }}
      </el-button>
    </template>
    <el-alert
      v-else-if="configInstanceState === 'NONE'"
      :title="t('currentVersions.instanceNoneTitle')"
      :description="t('currentVersions.instanceNoneHint')"
      type="info"
      show-icon
      :closable="false"
      class="diagnostic-alert"
    />
    <el-skeleton v-else-if="configInstanceState === 'LOADING'" :rows="4" animated />
    <template v-else>
      <div v-if="configInstanceState === 'MULTIPLE'" class="target-select">
        <div class="target-select-title">{{ t('currentVersions.selectInstanceTitle', { count: configInstances.length }) }}</div>
        <div class="target-select-row">
          <el-select
            v-model="configSelectedInstanceId"
            class="target-select-input"
            :placeholder="t('currentVersions.selectInstancePlaceholder')"
            @change="onConfigInstanceChange"
          >
            <el-option
              v-for="instance in configInstances"
              :key="instance.id"
              :label="instanceOptionLabel(instance)"
              :value="instance.id"
            />
          </el-select>
          <el-button type="primary" :loading="configLoading && !configEvidence" @click="confirmConfigRead">
            {{ t('currentVersions.confirmRead') }}
          </el-button>
        </div>
      </div>
      <template v-if="configInstance">
        <div class="target-header">
          <span class="target-header-item"><b>{{ t('currentVersions.dialogCurrentVersionId') }}:</b> {{ configTarget?.currentVersionId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogService') }}:</b> {{ targetServiceText(configTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogEnv') }}:</b> {{ targetEnvText(configTarget) }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceName') }}:</b> {{ configInstance.instanceName || '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceId') }}:</b> {{ configInstance.id }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogHostId') }}:</b> {{ configInstance.hostId ?? '-' }}</span>
          <span class="target-header-item"><b>{{ t('currentVersions.dialogInstanceVersion') }}:</b> {{ configEvidence?.versionId ?? configInstance.versionId ?? '-' }}</span>
          <span class="target-header-item">
            <b>{{ t('currentVersions.dialogEvidenceAt') }}:</b>
            {{ configRetrievedAt ? formatDateTime(configRetrievedAt) : t('currentVersions.dialogNotRetrieved') }}
          </span>
        </div>
        <el-skeleton v-if="configLoading && !configEvidence" :rows="6" animated />
        <template v-else>
          <el-alert v-if="configFailure" :title="t('currentVersions.readFailed')" type="error" show-icon :closable="false" class="diagnostic-alert">
            <div class="read-failure-hint">{{ configFailureHint }}</div>
            <div v-if="configFailureAdvice" class="read-failure-hint">{{ configFailureAdvice }}</div>
            <div v-if="configFailureRawError" class="raw-error-block">
              <div class="raw-error-title">{{ t('currentVersions.rawErrorTitle') }}</div>
              <pre class="raw-error-text">{{ configFailureRawError }}</pre>
            </div>
            <div v-if="configEvidence" class="read-failure-hint">{{ t('currentVersions.staleEvidenceNotice') }}</div>
          </el-alert>
          <div v-if="configFailure" class="retry-row">
            <el-button size="small" :loading="configLoading" @click="retryConfigRead">{{ t('currentVersions.readOnlyRetry') }}</el-button>
            <span class="read-again-hint">{{ t('currentVersions.readAgainHint') }}</span>
          </div>
          <el-descriptions v-if="configEvidence" :column="1" border>
            <el-descriptions-item :label="t('currentVersions.statusConfigSource')">{{ configEvidence.source }}</el-descriptions-item>
            <el-descriptions-item :label="t('currentVersions.deployRoot')">{{ configEvidence.deployRoot }}</el-descriptions-item>
            <el-descriptions-item :label="t('currentVersions.deployPort')">{{ configEvidence.deployPort }}</el-descriptions-item>
            <el-descriptions-item :label="t('currentVersions.pidFile')">{{ configEvidence.pidFile }}</el-descriptions-item>
            <el-descriptions-item :label="t('currentVersions.logFile')">{{ configEvidence.logFile }}</el-descriptions-item>
            <el-descriptions-item :label="t('currentVersions.healthUrl')">{{ configEvidence.healthUrl }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else-if="!configFailure" :description="t('currentVersions.deployConfigEmpty')" />
        </template>
      </template>
    </template>
  </el-dialog>

  <!-- 最近部署记录：接入 DeployRecordsDialog 的错误/只读重试契约，读取失败不等于没有记录 -->
  <DeployRecordsDialog
    v-model="recordsVisible"
    :records="records"
    :loading="recordsLoading"
    :error="recordsError"
    @retry="retryLatestDeployRecords"
  />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import CrudPage from '@/components/page/CrudPage.vue';
import DeployCoverageTag from '@/components/release/DeployCoverageTag.vue';
import VersionRegistrationTag from '@/components/release/VersionRegistrationTag.vue';
import DeployRecordsDialog from '@/components/release/DeployRecordsDialog.vue';
import RuntimeLogPanel from '@/components/resource/RuntimeLogPanel.vue';
import { getPageConfig } from '@/views/configs/page-configs';
import { formatDateTime } from '@/utils/format';
import {
  getRuntimeServiceInstanceDeployConfig,
  getRuntimeServiceInstanceLogs,
  getRuntimeServiceInstanceStatus,
  listRuntimeServiceInstances,
  type RuntimeServiceInstanceDeployConfig,
  type RuntimeServiceInstance,
  type RuntimeServiceInstanceLogs,
  type RuntimeServiceInstanceStatus,
} from '@/api/runtime';
import { getLatestDeployRecords } from '@/api/release';
import { useAuthStore } from '@/store/auth';

const crudPageRef = ref<InstanceType<typeof CrudPage>>();
const router = useRouter();
const { t } = useI18n();
const auth = useAuthStore();
const canRollback = computed(() => auth.canAccess(['ROLLBACK_EXECUTE', 'SYSTEM_ADMIN']));
const config = computed(() => getPageConfig('currentVersions'));

/**
 * 版本指针的实例覆盖范围。判定与文案全部来自后端，页面只按 verdict 显示：
 * FULL 只表示本次执行覆盖了当前实例集合，不代表当前登记版本一致。
 */
interface DeployCoverageRow {
  verdict?: string;
  evidenceProvable?: boolean;
  deployedCount?: number;
  uncoveredCount?: number;
  unfinishedCount?: number;
  currentInstanceCount?: number;
  statement?: string;
  nextStep?: string;
  disclaimer?: string;
}

/** 当前实例登记版本与目标版本的一致性；与覆盖范围分开，页面不把两者合并成一个结论。 */
interface RegistrationConsistencyRow {
  status?: string;
  targetSourceType?: string;
  sourceOrderId?: number | null;
  sourceOrderNo?: string | null;
  sourceConsistent?: boolean;
  sourceRejectionReason?: string | null;
  targetVersionNo?: string | null;
  targetVersionId?: number | null;
  matchingCount?: number;
  currentInstanceCount?: number;
  statement?: string;
  disclaimer?: string;
}

/** 读取失败：优先保留后端原始原因（不改写、不翻译），取不到原因时按当前语言回退。 */
interface ReadFailure {
  reason: string | null;
  /** SCOPE = 返回数据与当前目标作用域不符，被拒绝展示 */
  code?: 'SCOPE';
}

/** 诊断目标：一次操作内固定下来的归属（当前版本、服务、环境），列表业务名称优先、ID 用于定位 */
interface DiagnosticTarget {
  currentVersionId: number | null;
  serviceId: number;
  envId: number;
  serviceName: string | null;
  envName: string | null;
}

/** 目标实例的确定过程：无实例 / 列表读取失败 / 多实例必须分别表达 */
type InstancePlanState = 'IDLE' | 'LOADING' | 'SINGLE' | 'MULTIPLE' | 'NONE' | 'ERROR' | 'UNKNOWN';

function readDiagnosticTarget(row: Record<string, unknown>): DiagnosticTarget | null {
  const serviceId = Number(row.serviceId);
  const envId = Number(row.envId);
  if (!Number.isFinite(serviceId) || serviceId <= 0 || !Number.isFinite(envId) || envId <= 0) {
    return null;
  }
  const currentVersionId = Number(row.id);
  return {
    currentVersionId: Number.isFinite(currentVersionId) && currentVersionId > 0 ? currentVersionId : null,
    serviceId,
    envId,
    serviceName: typeof row.serviceDisplayName === 'string' && row.serviceDisplayName ? row.serviceDisplayName : null,
    envName: typeof row.envDisplayName === 'string' && row.envDisplayName ? row.envDisplayName : null,
  };
}

function sameTarget(current: DiagnosticTarget | null, target: DiagnosticTarget) {
  return !!current
    && current.currentVersionId === target.currentVersionId
    && current.serviceId === target.serviceId
    && current.envId === target.envId;
}

function readFailureReason(error: unknown): string | null {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  const responseMessage = (error as { response?: { data?: { message?: unknown } } } | null)?.response?.data?.message;
  if (typeof responseMessage === 'string' && responseMessage.trim()) {
    return responseMessage;
  }
  return null;
}

function toReadFailure(error: unknown): ReadFailure {
  return { reason: readFailureReason(error) };
}

function readFailureText(failure: ReadFailure | null, fallbackKey: string): string | null {
  if (!failure) return null;
  return failure.reason ?? t(fallbackKey);
}

/** 三态布尔：只有后端明确返回 true/false 才给出结论，缺失或 null 一律"无法确认"。 */
type TriState = 'TRUE' | 'FALSE' | 'UNKNOWN';

function triState(value: unknown): TriState {
  if (value === true) return 'TRUE';
  if (value === false) return 'FALSE';
  return 'UNKNOWN';
}

function triStateTagType(state: TriState) {
  if (state === 'TRUE') return 'success';
  if (state === 'FALSE') return 'danger';
  return 'info';
}

function triStateSummaryClass(state: TriState) {
  if (state === 'TRUE') return 'summary-success';
  if (state === 'FALSE') return 'summary-danger';
  return 'summary-unknown';
}

function boolText(state: TriState, trueKey: string, falseKey: string) {
  if (state === 'TRUE') return t(trueKey);
  if (state === 'FALSE') return t(falseKey);
  return t('currentVersions.statusUnconfirmed');
}

/** 原始异常里的已知情形转成可操作的用户提示；原始文本仍原样展示，不被替换。 */
function friendlyDiagnosticError(reason: string): string {
  if (reason.includes('服务未配置部署诊断参数')) {
    return t('currentVersions.errorMissingDiagnosticParams');
  }
  if (reason.includes('服务实例不存在')) {
    return t('currentVersions.errorInstanceNotFound');
  }
  if (reason.includes('目标主机不存在')) {
    return t('currentVersions.errorHostNotFound');
  }
  if (reason.includes('目标主机缺少启用中的 SSH 配置')) {
    return t('currentVersions.errorHostSshMissing');
  }
  return '';
}

function diagnoseErrorLayer(status: RuntimeServiceInstanceStatus): string {
  if (status.instanceStatus === 'UNKNOWN') {
    return t(status.stderr ? 'currentVersions.layerSsh' : 'currentVersions.layerUnknown');
  }
  const running = triState(status.running);
  const listening = triState(status.portListening);
  // 严格判据：只依据明确返回的 true/false 推断层级，缺失字段不作为"未运行/未监听"的证据
  if (!status.pid && status.stderr) {
    return t('currentVersions.layerSsh');
  }
  if (status.pid && running === 'FALSE') {
    return t('currentVersions.layerProcess');
  }
  if (running === 'TRUE' && listening === 'FALSE') {
    return t('currentVersions.layerPort');
  }
  if (listening === 'TRUE' && typeof status.healthStatus === 'string' && status.healthStatus && status.healthStatus !== 'UP') {
    return t('currentVersions.layerHealth');
  }
  return t('currentVersions.layerUnknown');
}

function targetServiceText(target: DiagnosticTarget | null) {
  if (!target) return '-';
  return target.serviceName ?? String(target.serviceId);
}

function targetEnvText(target: DiagnosticTarget | null) {
  if (!target) return '-';
  return target.envName ?? String(target.envId);
}

/** 列表单元格：单实例给身份信息，多实例只给数量与"诊断时选择"，无实例与读取失败分别表达 */
function instanceCellText(row: Record<string, unknown>): string {
  const state = String(row.runtimeInstanceState ?? 'UNKNOWN');
  if (state === 'SINGLE') {
    const id = row.runtimeInstanceId;
    const name = typeof row.runtimeInstanceName === 'string' && row.runtimeInstanceName ? row.runtimeInstanceName : null;
    return name ? t('currentVersions.instanceSingle', { name, id }) : t('currentVersions.instanceSingleIdOnly', { id });
  }
  if (state === 'MULTIPLE') {
    return t('currentVersions.instanceMultiple', { count: Number(row.runtimeInstanceCount ?? 0) });
  }
  if (state === 'NONE') {
    return t('currentVersions.instanceNone');
  }
  if (state === 'ERROR') {
    return t('currentVersions.instanceError');
  }
  return t('currentVersions.instanceUnknown');
}

function instanceCellClass(row: Record<string, unknown>): string {
  const state = String(row.runtimeInstanceState ?? 'UNKNOWN');
  if (state === 'ERROR') return 'instance-cell instance-cell-error';
  if (state === 'NONE') return 'instance-cell instance-cell-muted';
  return 'instance-cell';
}

function instanceOptionLabel(instance: RuntimeServiceInstance): string {
  const name = instance.instanceName || t('currentVersions.instanceSingleIdOnly', { id: instance.id });
  const host = instance.hostId ?? '-';
  const version = instance.versionId ?? '-';
  return `${name} · ${t('currentVersions.dialogInstanceId')} ${instance.id} · ${t('currentVersions.dialogHostId')} ${host} · ${t('currentVersions.dialogInstanceVersion')} ${version}`;
}

/**
 * 按 service/env 查询返回的实例列表：只要存在明显不属于本次目标的条目，整轮读取判为作用域错误。
 * 不再静默过滤：过滤会把"作用域错误"降级成"没有实例"，两件事必须分开表达。
 */
type InstanceScopeResult =
  | { ok: true; instances: RuntimeServiceInstance[] }
  | { ok: false };

function verifyInstanceScope(instances: RuntimeServiceInstance[], target: DiagnosticTarget): InstanceScopeResult {
  const foreign = instances.some((item) => item.serviceId !== target.serviceId || item.envId !== target.envId);
  if (foreign) return { ok: false };
  return { ok: true, instances };
}

/** 实例列表通道的 ERROR 标题/说明：作用域不符与查询失败分别说明，都不说成"没有实例" */
function instancesAlertTitle(failure: ReadFailure | null) {
  return failure?.code === 'SCOPE' ? t('currentVersions.instanceListScopeFailed') : t('currentVersions.instanceListFailed');
}

function instancesAlertHint(failure: ReadFailure | null) {
  return failure?.code === 'SCOPE' ? t('currentVersions.instanceListScopeFailedHint') : t('currentVersions.instanceListFailedHint');
}

async function copyText(raw: string | null | undefined, emptyKey: string, successKey: string, failedKey: string) {
  if (!raw) {
    ElMessage.warning(t(emptyKey));
    return;
  }
  try {
    await navigator.clipboard.writeText(raw);
    ElMessage.success(t(successKey));
  } catch {
    ElMessage.error(t(failedKey));
  }
}

// —— 运行状态通道：实例列表 + 本通道自己的部署配置（端口来源）+ 状态证据 ——
const statusVisible = ref(false);
const statusLoading = ref(false);
const statusInstancesLoading = ref(false);
const statusInstances = ref<RuntimeServiceInstance[]>([]);
const statusInstanceState = ref<InstancePlanState>('IDLE');
const statusInstancesFailure = ref<ReadFailure | null>(null);
const statusSelectedInstanceId = ref<number | null>(null);
const statusTarget = ref<DiagnosticTarget | null>(null);
const statusInstance = ref<RuntimeServiceInstance | null>(null);
const statusEvidence = ref<RuntimeServiceInstanceStatus | null>(null);
const statusConfig = ref<RuntimeServiceInstanceDeployConfig | null>(null);
/** 附属配置（端口/来源）不可用的原因：SCOPE = 返回作用域与当前目标不符，FAILED = 本次读取失败 */
const statusConfigIssue = ref<'SCOPE' | 'FAILED' | null>(null);
const statusFailure = ref<ReadFailure | null>(null);
const statusRetrievedAt = ref<string | null>(null);
let statusSeq = 0;

const statusInstancesRawError = computed(() => statusInstancesFailure.value?.reason ?? null);
const statusInstancesAlertTitle = computed(() => instancesAlertTitle(statusInstancesFailure.value));
const statusInstancesAlertHint = computed(() => instancesAlertHint(statusInstancesFailure.value));
const statusConfigIssueHint = computed(() => (statusConfigIssue.value === 'SCOPE'
  ? t('currentVersions.statusConfigScopeHint')
  : t('currentVersions.statusConfigFailedHint')));
const statusFailureRawError = computed(() => statusFailure.value?.reason ?? null);
const statusFailureHint = computed(() => (statusFailure.value?.code === 'SCOPE'
  ? t('currentVersions.scopeMismatch')
  : t('currentVersions.statusReadFailedHint')));
const statusFailureAdvice = computed(() => (statusFailure.value?.reason ? friendlyDiagnosticError(statusFailure.value.reason) : ''));
const statusRunningState = computed<TriState>(() => statusEvidence.value?.instanceStatus === 'UNKNOWN'
  ? 'UNKNOWN' : triState(statusEvidence.value?.running));
const statusPortState = computed<TriState>(() => statusEvidence.value?.instanceStatus === 'UNKNOWN'
  ? 'UNKNOWN' : triState(statusEvidence.value?.portListening));
const statusHealthRaw = computed(() => {
  const value = statusEvidence.value?.healthStatus;
  return typeof value === 'string' && value ? value : null;
});
const statusHealthTagType = computed(() => (statusHealthRaw.value === 'UP' ? 'success' : statusHealthRaw.value ? 'warning' : 'info'));
const statusHealthClass = computed(() => (statusHealthRaw.value === 'UP' ? 'summary-success' : 'summary-unknown'));

/**
 * 读取运行状态证据。发起前固定"请求序号 + 目标身份"，每个 await 返回后重新确认归属；
 * 归属失效则立即停止后续读取与状态更新，旧请求的成功、失败、finally 都无权改写当前展示。
 */
async function readStatusEvidence(requestId: number) {
  const target = statusTarget.value;
  const instance = statusInstance.value;
  if (!target || !instance) return;
  const isCurrent = () => requestId === statusSeq
    && statusVisible.value
    && sameTarget(statusTarget.value, target)
    && statusInstance.value?.id === instance.id
    && statusSelectedInstanceId.value === instance.id;
  statusLoading.value = true;
  try {
    // 端口只来自本通道自己的部署配置读取（状态接口不返回端口）；
    // 该读取失败只影响端口展示，不会被当成运行状态读取失败。
    try {
      const deployConfig = await getRuntimeServiceInstanceDeployConfig(instance.id);
      if (!isCurrent()) return;
      // 附属配置也必须验证目标：instanceId/serviceId/envId 任一不符，就不能展示它的端口、来源或其他任何字段
      if (deployConfig.instanceId !== instance.id
        || deployConfig.serviceId !== target.serviceId
        || deployConfig.envId !== target.envId) {
        statusConfig.value = null;
        statusConfigIssue.value = 'SCOPE';
      } else {
        statusConfig.value = deployConfig;
        statusConfigIssue.value = null;
      }
    } catch {
      if (!isCurrent()) return;
      statusConfig.value = null;
      statusConfigIssue.value = 'FAILED';
    }
    // 附属配置不可用不阻断状态证据：status 的响应仍然按自己的事实展示，也不因此宣称服务停机
    const evidence = await getRuntimeServiceInstanceStatus(instance.id);
    if (!isCurrent()) return;
    if (evidence.instanceId !== instance.id || evidence.serviceId !== target.serviceId || evidence.envId !== target.envId) {
      statusFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    statusEvidence.value = evidence;
    statusFailure.value = null;
    statusRetrievedAt.value = new Date().toISOString();
  } catch (error) {
    if (!isCurrent()) return;
    // 同一目标读取失败：保留已读到的证据，同时明确标注本次读取失败
    statusFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      statusLoading.value = false;
    }
  }
}

/** 实例列表读取（只读 GET，不执行远程诊断）：单实例沿用"点击后直接读取"，多实例等用户选定后确认 */
async function resolveStatusInstance() {
  const target = statusTarget.value;
  if (!target) return;
  const requestId = statusSeq;
  statusInstanceState.value = 'LOADING';
  statusInstancesLoading.value = true;
  statusInstancesFailure.value = null;
  try {
    const instances = await listRuntimeServiceInstances({ serviceId: target.serviceId, envId: target.envId });
    if (requestId !== statusSeq || !statusVisible.value) return;
    const scoped = verifyInstanceScope(instances, target);
    if (!scoped.ok) {
      // 作用域错误不是空列表：不写入任何实例，也不进入选择器
      statusInstances.value = [];
      statusInstanceState.value = 'ERROR';
      statusInstancesFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    statusInstances.value = scoped.instances;
    if (scoped.instances.length === 0) {
      statusInstanceState.value = 'NONE';
      return;
    }
    if (scoped.instances.length === 1) {
      statusInstanceState.value = 'SINGLE';
      statusSelectedInstanceId.value = scoped.instances[0].id;
      statusInstance.value = scoped.instances[0];
      await readStatusEvidence(requestId);
      return;
    }
    statusInstanceState.value = 'MULTIPLE';
  } catch (error) {
    if (requestId !== statusSeq || !statusVisible.value) return;
    statusInstanceState.value = 'ERROR';
    statusInstancesFailure.value = toReadFailure(error);
  } finally {
    if (requestId === statusSeq) {
      statusInstancesLoading.value = false;
    }
  }
}

async function openStatus(row: Record<string, unknown>) {
  const target = readDiagnosticTarget(row);
  // 切换当前版本/重新打开：先让旧请求失效，再清空旧证据
  statusSeq += 1;
  statusVisible.value = true;
  statusLoading.value = false;
  statusInstancesLoading.value = false;
  statusInstances.value = [];
  statusInstanceState.value = 'IDLE';
  statusInstancesFailure.value = null;
  statusSelectedInstanceId.value = null;
  statusTarget.value = target;
  statusInstance.value = null;
  statusEvidence.value = null;
  statusConfig.value = null;
  statusConfigIssue.value = null;
  statusFailure.value = null;
  statusRetrievedAt.value = null;
  if (!target) {
    statusInstanceState.value = 'UNKNOWN';
    return;
  }
  await resolveStatusInstance();
}

async function reloadStatusInstances() {
  statusSeq += 1;
  statusTarget.value = statusTarget.value ? { ...statusTarget.value } : null;
  await resolveStatusInstance();
}

async function confirmStatusRead() {
  const instance = statusInstances.value.find((item) => item.id === statusSelectedInstanceId.value) ?? null;
  if (!instance) {
    // 明确的用户动作提示：确认前不发起任何远程诊断
    ElMessage.warning(t('currentVersions.selectInstanceRequired'));
    return;
  }
  statusSeq += 1;
  const requestId = statusSeq;
  statusInstance.value = instance;
  statusEvidence.value = null;
  statusConfig.value = null;
  statusConfigIssue.value = null;
  statusFailure.value = null;
  statusRetrievedAt.value = null;
  await readStatusEvidence(requestId);
}

function onStatusInstanceChange() {
  // 切换目标立即清除旧证据：证据只属于它被读取时的那个实例
  statusSeq += 1;
  statusInstance.value = null;
  statusEvidence.value = null;
  statusConfig.value = null;
  statusConfigIssue.value = null;
  statusFailure.value = null;
  statusRetrievedAt.value = null;
  statusLoading.value = false;
}

async function retryStatusRead() {
  if (!statusInstance.value) return;
  statusSeq += 1;
  await readStatusEvidence(statusSeq);
}

// —— 运行日志通道 ——
const logsVisible = ref(false);
const logsLines = ref(200);
const logsLoading = ref(false);
const logsInstancesLoading = ref(false);
const logsInstances = ref<RuntimeServiceInstance[]>([]);
const logsInstanceState = ref<InstancePlanState>('IDLE');
const logsInstancesFailure = ref<ReadFailure | null>(null);
const logsSelectedInstanceId = ref<number | null>(null);
const logsTarget = ref<DiagnosticTarget | null>(null);
const logsInstance = ref<RuntimeServiceInstance | null>(null);
const logsEvidence = ref<RuntimeServiceInstanceLogs | null>(null);
const logsFailure = ref<ReadFailure | null>(null);
const logsRetrievedAt = ref<string | null>(null);
let logsSeq = 0;

const logsInstancesRawError = computed(() => logsInstancesFailure.value?.reason ?? null);
const logsInstancesAlertTitle = computed(() => instancesAlertTitle(logsInstancesFailure.value));
const logsInstancesAlertHint = computed(() => instancesAlertHint(logsInstancesFailure.value));
const logsFailureRawError = computed(() => logsFailure.value?.reason ?? null);
const logsFailureHint = computed(() => (logsFailure.value?.code === 'SCOPE'
  ? t('currentVersions.scopeMismatch')
  : t('currentVersions.logsReadFailedHint')));
const logsFailureAdvice = computed(() => (logsFailure.value?.reason ? friendlyDiagnosticError(logsFailure.value.reason) : ''));

async function readLogsEvidence(requestId: number) {
  const target = logsTarget.value;
  const instance = logsInstance.value;
  if (!target || !instance) return;
  const isCurrent = () => requestId === logsSeq
    && logsVisible.value
    && sameTarget(logsTarget.value, target)
    && logsInstance.value?.id === instance.id
    && logsSelectedInstanceId.value === instance.id;
  logsLoading.value = true;
  try {
    const evidence = await getRuntimeServiceInstanceLogs(instance.id, logsLines.value);
    if (!isCurrent()) return;
    if (evidence.instanceId !== instance.id || evidence.serviceId !== target.serviceId || evidence.envId !== target.envId) {
      logsFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    logsEvidence.value = evidence;
    logsFailure.value = null;
    logsRetrievedAt.value = new Date().toISOString();
  } catch (error) {
    if (!isCurrent()) return;
    logsFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      logsLoading.value = false;
    }
  }
}

async function resolveLogsInstance() {
  const target = logsTarget.value;
  if (!target) return;
  const requestId = logsSeq;
  logsInstanceState.value = 'LOADING';
  logsInstancesLoading.value = true;
  logsInstancesFailure.value = null;
  try {
    const instances = await listRuntimeServiceInstances({ serviceId: target.serviceId, envId: target.envId });
    if (requestId !== logsSeq || !logsVisible.value) return;
    const scoped = verifyInstanceScope(instances, target);
    if (!scoped.ok) {
      logsInstances.value = [];
      logsInstanceState.value = 'ERROR';
      logsInstancesFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    logsInstances.value = scoped.instances;
    if (scoped.instances.length === 0) {
      logsInstanceState.value = 'NONE';
      return;
    }
    if (scoped.instances.length === 1) {
      logsInstanceState.value = 'SINGLE';
      logsSelectedInstanceId.value = scoped.instances[0].id;
      logsInstance.value = scoped.instances[0];
      await readLogsEvidence(requestId);
      return;
    }
    logsInstanceState.value = 'MULTIPLE';
  } catch (error) {
    if (requestId !== logsSeq || !logsVisible.value) return;
    logsInstanceState.value = 'ERROR';
    logsInstancesFailure.value = toReadFailure(error);
  } finally {
    if (requestId === logsSeq) {
      logsInstancesLoading.value = false;
    }
  }
}

async function openLogs(row: Record<string, unknown>) {
  const target = readDiagnosticTarget(row);
  logsSeq += 1;
  logsVisible.value = true;
  logsLines.value = 200;
  logsLoading.value = false;
  logsInstancesLoading.value = false;
  logsInstances.value = [];
  logsInstanceState.value = 'IDLE';
  logsInstancesFailure.value = null;
  logsSelectedInstanceId.value = null;
  logsTarget.value = target;
  logsInstance.value = null;
  logsEvidence.value = null;
  logsFailure.value = null;
  logsRetrievedAt.value = null;
  if (!target) {
    logsInstanceState.value = 'UNKNOWN';
    return;
  }
  await resolveLogsInstance();
}

async function reloadLogsInstances() {
  logsSeq += 1;
  logsTarget.value = logsTarget.value ? { ...logsTarget.value } : null;
  await resolveLogsInstance();
}

async function confirmLogsRead() {
  const instance = logsInstances.value.find((item) => item.id === logsSelectedInstanceId.value) ?? null;
  if (!instance) {
    ElMessage.warning(t('currentVersions.selectInstanceRequired'));
    return;
  }
  logsSeq += 1;
  const requestId = logsSeq;
  logsInstance.value = instance;
  logsEvidence.value = null;
  logsFailure.value = null;
  logsRetrievedAt.value = null;
  await readLogsEvidence(requestId);
}

function onLogsInstanceChange() {
  logsSeq += 1;
  logsInstance.value = null;
  logsEvidence.value = null;
  logsFailure.value = null;
  logsRetrievedAt.value = null;
  logsLoading.value = false;
}

/** 日志刷新只使用日志弹窗自己的目标实例；不会读取其他弹窗选中的实例 */
async function retryLogsRead() {
  if (!logsInstance.value || logsLoading.value) return;
  logsSeq += 1;
  await readLogsEvidence(logsSeq);
}

function changeLogsLines(lines: number) { logsLines.value = lines; void retryLogsRead(); }

async function copyLogs() {
  await copyText(logsEvidence.value?.content, 'currentVersions.logsCopyEmpty', 'currentVersions.logsCopied', 'currentVersions.logsCopyFailed');
}

// —— 部署配置通道 ——
const configVisible = ref(false);
const configLoading = ref(false);
const configInstancesLoading = ref(false);
const configInstances = ref<RuntimeServiceInstance[]>([]);
const configInstanceState = ref<InstancePlanState>('IDLE');
const configInstancesFailure = ref<ReadFailure | null>(null);
const configSelectedInstanceId = ref<number | null>(null);
const configTarget = ref<DiagnosticTarget | null>(null);
const configInstance = ref<RuntimeServiceInstance | null>(null);
const configEvidence = ref<RuntimeServiceInstanceDeployConfig | null>(null);
const configFailure = ref<ReadFailure | null>(null);
const configRetrievedAt = ref<string | null>(null);
let configSeq = 0;

const configInstancesRawError = computed(() => configInstancesFailure.value?.reason ?? null);
const configInstancesAlertTitle = computed(() => instancesAlertTitle(configInstancesFailure.value));
const configInstancesAlertHint = computed(() => instancesAlertHint(configInstancesFailure.value));
const configFailureRawError = computed(() => configFailure.value?.reason ?? null);
const configFailureHint = computed(() => (configFailure.value?.code === 'SCOPE'
  ? t('currentVersions.scopeMismatch')
  : t('currentVersions.deployConfigReadFailedHint')));
const configFailureAdvice = computed(() => (configFailure.value?.reason ? friendlyDiagnosticError(configFailure.value.reason) : ''));

async function readConfigEvidence(requestId: number) {
  const target = configTarget.value;
  const instance = configInstance.value;
  if (!target || !instance) return;
  const isCurrent = () => requestId === configSeq
    && configVisible.value
    && sameTarget(configTarget.value, target)
    && configInstance.value?.id === instance.id
    && configSelectedInstanceId.value === instance.id;
  configLoading.value = true;
  try {
    const evidence = await getRuntimeServiceInstanceDeployConfig(instance.id);
    if (!isCurrent()) return;
    if (evidence.instanceId !== instance.id || evidence.serviceId !== target.serviceId || evidence.envId !== target.envId) {
      configFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    configEvidence.value = evidence;
    configFailure.value = null;
    configRetrievedAt.value = new Date().toISOString();
  } catch (error) {
    if (!isCurrent()) return;
    configFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      configLoading.value = false;
    }
  }
}

async function resolveConfigInstance() {
  const target = configTarget.value;
  if (!target) return;
  const requestId = configSeq;
  configInstanceState.value = 'LOADING';
  configInstancesLoading.value = true;
  configInstancesFailure.value = null;
  try {
    const instances = await listRuntimeServiceInstances({ serviceId: target.serviceId, envId: target.envId });
    if (requestId !== configSeq || !configVisible.value) return;
    const scoped = verifyInstanceScope(instances, target);
    if (!scoped.ok) {
      configInstances.value = [];
      configInstanceState.value = 'ERROR';
      configInstancesFailure.value = { reason: null, code: 'SCOPE' };
      return;
    }
    configInstances.value = scoped.instances;
    if (scoped.instances.length === 0) {
      configInstanceState.value = 'NONE';
      return;
    }
    if (scoped.instances.length === 1) {
      configInstanceState.value = 'SINGLE';
      configSelectedInstanceId.value = scoped.instances[0].id;
      configInstance.value = scoped.instances[0];
      await readConfigEvidence(requestId);
      return;
    }
    configInstanceState.value = 'MULTIPLE';
  } catch (error) {
    if (requestId !== configSeq || !configVisible.value) return;
    configInstanceState.value = 'ERROR';
    configInstancesFailure.value = toReadFailure(error);
  } finally {
    if (requestId === configSeq) {
      configInstancesLoading.value = false;
    }
  }
}

async function openDeployConfig(row: Record<string, unknown>) {
  const target = readDiagnosticTarget(row);
  configSeq += 1;
  configVisible.value = true;
  configLoading.value = false;
  configInstancesLoading.value = false;
  configInstances.value = [];
  configInstanceState.value = 'IDLE';
  configInstancesFailure.value = null;
  configSelectedInstanceId.value = null;
  configTarget.value = target;
  configInstance.value = null;
  configEvidence.value = null;
  configFailure.value = null;
  configRetrievedAt.value = null;
  if (!target) {
    configInstanceState.value = 'UNKNOWN';
    return;
  }
  await resolveConfigInstance();
}

async function reloadConfigInstances() {
  configSeq += 1;
  configTarget.value = configTarget.value ? { ...configTarget.value } : null;
  await resolveConfigInstance();
}

async function confirmConfigRead() {
  const instance = configInstances.value.find((item) => item.id === configSelectedInstanceId.value) ?? null;
  if (!instance) {
    ElMessage.warning(t('currentVersions.selectInstanceRequired'));
    return;
  }
  configSeq += 1;
  const requestId = configSeq;
  configInstance.value = instance;
  configEvidence.value = null;
  configFailure.value = null;
  configRetrievedAt.value = null;
  await readConfigEvidence(requestId);
}

function onConfigInstanceChange() {
  configSeq += 1;
  configInstance.value = null;
  configEvidence.value = null;
  configFailure.value = null;
  configRetrievedAt.value = null;
  configLoading.value = false;
}

async function retryConfigRead() {
  if (!configInstance.value) return;
  configSeq += 1;
  await readConfigEvidence(configSeq);
}

// —— 最近部署记录通道：与三个诊断弹窗完全独立 ——
const recordsVisible = ref(false);
const recordsLoading = ref(false);
const records = ref<Record<string, unknown>[]>([]);
const recordsTargetId = ref<number | null>(null);
const recordsFailure = ref<ReadFailure | null>(null);
let recordsSeq = 0;
const recordsError = computed(() => readFailureText(recordsFailure.value, 'currentVersions.readFailed'));

async function loadLatestDeployRecords() {
  const targetId = recordsTargetId.value;
  if (!targetId) return;
  const requestId = ++recordsSeq;
  const isCurrent = () => requestId === recordsSeq && recordsTargetId.value === targetId;
  recordsLoading.value = true;
  try {
    const list = await getLatestDeployRecords(targetId);
    if (!isCurrent()) return;
    records.value = list;
    recordsFailure.value = null;
  } catch (error) {
    if (!isCurrent()) return;
    // 读取失败不等于"没有记录"：保留已读到的内容，并显式标注本次读取失败
    recordsFailure.value = toReadFailure(error);
  } finally {
    if (isCurrent()) {
      recordsLoading.value = false;
    }
  }
}

async function openLatestDeployRecords(row: Record<string, unknown>) {
  const targetId = Number(row.id);
  if (!Number.isFinite(targetId) || targetId <= 0) {
    ElMessage.warning(t('currentVersions.recordsMissingId'));
    return;
  }
  const identityChanged = recordsTargetId.value !== targetId;
  recordsTargetId.value = targetId;
  if (identityChanged) {
    records.value = [];
    recordsFailure.value = null;
  }
  recordsVisible.value = true;
  await loadLatestDeployRecords();
}

/** 只读重试：绑定当前版本的最近部署记录重新读取（仅 GET，不触发部署动作） */
async function retryLatestDeployRecords() {
  await loadLatestDeployRecords();
}

// V-D: navigateToReleaseOrder — jump to release orders list filtered by appId/envId
function navigateToReleaseOrder(row: Record<string, unknown>) {
  const query: Record<string, string> = {};
  if (row.appId) query.appId = String(row.appId);
  if (row.envId) query.envId = String(row.envId);
  if (row.serviceId) query.serviceId = String(row.serviceId);
  router.push({ path: '/release/orders', query });
}

function navigateToApplicationRollback(row: Record<string, unknown>) {
  router.push({ path: '/release/application-rollback', query: {
    appId: String(row.appId), envId: String(row.envId), serviceId: String(row.serviceId),
  } });
}

// V-D: navigateToRuntimeInstance — jump to runtime instances list filtered by serviceId/envId
function navigateToRuntimeInstance(row: Record<string, unknown>) {
  const query: Record<string, string> = {};
  if (row.serviceId) query.serviceId = String(row.serviceId);
  if (row.envId) query.envId = String(row.envId);
  router.push({ path: '/resource/runtime-instances', query });
}

/** 关闭弹窗即让在途读取失去更新当前展示的资格（flush: 'sync' 保证关闭当帧立刻失效） */
watch(statusVisible, (visible) => {
  if (visible) return;
  statusSeq += 1;
  statusLoading.value = false;
  statusInstancesLoading.value = false;
}, { flush: 'sync' });

watch(logsVisible, (visible) => {
  if (visible) return;
  logsSeq += 1;
  logsLoading.value = false;
  logsInstancesLoading.value = false;
}, { flush: 'sync' });

watch(configVisible, (visible) => {
  if (visible) return;
  configSeq += 1;
  configLoading.value = false;
  configInstancesLoading.value = false;
}, { flush: 'sync' });

watch(recordsVisible, (visible) => {
  if (visible) return;
  recordsSeq += 1;
  recordsLoading.value = false;
}, { flush: 'sync' });
</script>

<style scoped lang="scss">
.row-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ih-spacing-1);
}

.row-action-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--ih-spacing-1) var(--ih-spacing-3);
}

// 当前版本行的主诊断入口：使用主题令牌，明暗主题一致
.is-row-primary-action {
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: var(--color-accent);
  font-weight: 500;
}

.row-more-trigger {
  color: var(--ih-text-secondary);
}

/* 「更多」提示的外层包裹：让 ElDropdown 的默认插槽保持单一按钮节点 */
.row-more-wrap {
  display: inline-flex;
  align-items: center;
}

.instance-cell {
  word-break: break-word;
}

.instance-cell-muted {
  color: var(--ih-text-secondary);
}

.instance-cell-error {
  color: var(--ih-danger);
}

.diagnostic-section {
  margin-top: var(--ih-section-gap);
}

.diagnostic-alert {
  margin-bottom: var(--ih-spacing-5);
}

.diagnostic-title {
  margin-bottom: var(--ih-spacing-3);
  font-weight: 500;
}

// 目标实例头：长名称必须能完整查看，不因 nowrap 被截断
.target-header {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ih-spacing-3) var(--ih-spacing-lg);
  padding: var(--ih-spacing-3) var(--ih-spacing-5);
  margin-bottom: var(--ih-spacing-5);
  border: 1px solid var(--ih-border-light);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-subtle);
  font-size: 13px;
}

.target-header-item {
  word-break: break-word;

  b {
    color: var(--ih-text-secondary);
  }
}

// 多实例：必须显式选择目标并确认后才发起远程诊断
.target-select {
  padding: var(--ih-spacing-5);
  margin-bottom: var(--ih-spacing-5);
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-content);
}

.target-select-title {
  margin-bottom: var(--ih-spacing-3);
  font-weight: 500;
}

.target-select-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ih-spacing-3);
}

.target-select-input {
  min-width: 260px;
  flex: 1 1 260px;
}

.quick-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-lg);
  margin-bottom: var(--space-md);
}

.summary-item {
  display: flex;
  min-width: 126px;
  flex-direction: column;
  gap: var(--space-3xs);
  padding-left: var(--space-md);
  border-left: var(--rule-hair) solid var(--color-rule);
}

.summary-item:first-child {
  padding-left: 0;
  border-left: 0;
}

.summary-label {
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.summary-port {
  margin-left: var(--space-2xs);
  color: var(--color-ink);
  font-family: var(--font-outlier);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.summary-port-unconfirmed {
  color: var(--color-muted);
}

.logs-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-3);
}

.logs-path {
  word-break: break-all;
}

.logs-toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.logs-notice {
  margin-bottom: var(--ih-spacing-5);
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.read-failure-hint {
  margin-top: var(--ih-spacing-1);
  font-size: 12px;
  line-height: 1.6;
}

.raw-error-block {
  margin-top: var(--ih-spacing-3);
}

.raw-error-title {
  font-size: 12px;
  font-weight: 500;
}

.raw-error-text {
  margin: var(--ih-spacing-1) 0 0;
  padding: var(--ih-spacing-3);
  max-height: 200px;
  overflow: auto;
  border-radius: var(--ih-radius-sm);
  background: var(--ih-bg-row-hover);
  color: var(--ih-text-primary);
  font-family: var(--ih-font-family-mono);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.retry-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-5);
}

.read-again-hint {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.diagnostic-log {
  min-height: 120px;
  max-height: 520px;
  overflow: auto;
  padding: var(--ih-spacing-5);
  margin: 0;
  border: 1px solid var(--ih-border);
  border-radius: var(--ih-radius-md);
  background: var(--ih-bg-row-hover);
  color: var(--ih-text-primary);
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.runtime-log {
  margin-top: var(--ih-spacing-5);
}
</style>
