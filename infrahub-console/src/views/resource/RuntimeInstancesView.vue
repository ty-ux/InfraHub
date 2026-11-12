<template>
  <div class="runtime-instances-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('runtimeInstances.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('runtimeInstances.list') }}</span>
          <div class="card-header__actions">
            <CollectionScheduleControl @changed="handleRefresh" />
            <RegisterInstanceButton @changed="handleRefresh" />
            <el-button size="small" @click="legendVisible = true">
              {{ t('runtimeInstances.legendButton') }}
            </el-button>
            <!--
              「刷新列表」只重读已登记的数据，不等价于下面的「运行状态（实时探测）」：
              想让状态真的变成实时结果，必须走行内那个动作，这里不做隐式 SSH 探测。
            -->
            <el-tooltip :content="t('runtimeInstances.refreshListHint')" placement="top">
              <el-button size="small" class="refresh-list-btn" :loading="loading" @click="handleRefresh">
                {{ t('runtimeInstances.refreshList') }}
              </el-button>
            </el-tooltip>
          </div>
        </div>
      </template>

      <div class="filter-bar">
        <el-input
          v-model="queryParams.keyword"
          :placeholder="t('runtimeInstances.searchPlaceholder')"
          clearable
          class="filter-keyword"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.serviceId"
          :placeholder="t('runtimeInstances.serviceDisplayName')"
          clearable
          class="filter-service"
          :fit-input-width="false"
          popper-class="ih-select-popper-wide"
          @change="handleSearch"
        >
          <el-option
            v-for="svc in serviceOptions"
            :key="svc.id"
            :label="svc.serviceName"
            :value="svc.id"
          >
            <span class="option-full-text" :title="svc.serviceName">{{ svc.serviceName }}</span>
          </el-option>
        </el-select>
        <el-select
          v-model="queryParams.envId"
          :placeholder="t('runtimeInstances.envDisplayName')"
          clearable
          class="filter-env"
          @change="handleSearch"
        >
          <el-option
            v-for="env in envOptions"
            :key="env.id"
            :label="env.envName"
            :value="env.id"
          >
            <span class="option-full-text" :title="env.envName">{{ env.envName }}</span>
          </el-option>
        </el-select>
        <!--
          已选主机全文入口：hover（鼠标）与 focus（键盘 Tab 进选择框）都能读出完整
          "主机名 (IP)"。此前的完整值只挂在选项的原生 title 上，选择框本身被 240px
          宽度省略后，鼠标不移到选项、只用键盘，都看不到已选的是什么。
          提示内容只由当前 hostId 命中的那条选项派生（selectedHostFullText），
          清空或解析不到时 disabled，不渲染任何提示。
        -->
        <el-tooltip
          :content="selectedHostFullText"
          :disabled="!selectedHostFullText"
          placement="top"
          :trigger="['hover', 'focus']"
        >
          <el-select
            v-model="queryParams.hostId"
            :placeholder="t('runtimeInstances.hostDisplayName')"
            clearable
            class="filter-host"
            :fit-input-width="false"
            popper-class="ih-select-popper-wide"
            @change="handleSearch"
          >
            <el-option
              v-for="host in hostOptions"
              :key="host.id"
              :label="hostOptionLabel(host)"
              :value="host.id"
            >
              <span class="option-full-text" :title="hostOptionLabel(host)">{{ hostOptionLabel(host) }}</span>
            </el-option>
          </el-select>
        </el-tooltip>
        <!-- 筛选项与表格单元格、状态说明弹窗共用 statusSemantics 的一份取值定义 -->
        <el-select
          v-model="queryParams.instanceStatus"
          :placeholder="t('runtimeInstances.instanceStatus')"
          clearable
          class="filter-status"
          @change="handleSearch"
        >
          <el-option
            v-for="option in instanceStatusFilterOptions"
            :key="option.code"
            :label="option.label"
            :value="option.code"
          />
        </el-select>
        <el-select
          v-model="queryParams.healthStatus"
          :placeholder="t('runtimeInstances.healthStatus')"
          clearable
          class="filter-status"
          @change="handleSearch"
        >
          <el-option
            v-for="option in healthStatusFilterOptions"
            :key="option.code"
            :label="option.label"
            :value="option.code"
          />
        </el-select>
        <el-button @click="handleSearch">
          {{ t('common.search') }}
        </el-button>
      </div>

      <!--
        采集未启用时必须明说：否则用户会把一列永远不变的状态当成"现在是好的"。
        只在确认查到 false 时显示——null 表示还没查过，不能凭它喊"未启用"。
      -->
      <el-alert
        v-if="collectionEnabled === false"
        class="collection-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="t('runtimeInstances.collectionDisabledNotice')"
        :description="t('runtimeInstances.collectionDisabledDetail')"
      />
      <!-- 连续读取失败后自动轮询会停下，这件事不能悄悄发生 -->
      <el-alert
        v-if="pollingPaused"
        class="collection-alert"
        type="info"
        show-icon
        :closable="false"
        :title="t('runtimeInstances.collectionPollingPaused')"
        :description="t('runtimeInstances.collectionPollingPausedDetail')"
      />
      <!--
        读取失败时旧值仍然挂在表上：不标注的话，"上一轮的数据"会被当成"现在就是好的"。
        这是本页唯一允许把旧值留在屏幕上的情况，所以必须显式说明是哪一侧失败。
      -->
      <el-alert
        v-if="staleNotice"
        class="collection-alert"
        type="warning"
        show-icon
        :closable="false"
        :title="staleNotice"
        :description="staleNoticeDetail"
      />

      <el-table :data="tableRows" style="width: 100%" table-layout="fixed">
        <el-table-column prop="instanceName" :label="t('runtimeInstances.instanceName')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="t('runtimeInstances.serviceDisplayName')" min-width="180" show-overflow-tooltip header-align="center">
          <template #default="{ row }">
            <span>{{ getServiceDisplayName(row.serviceId) }}</span>
            <el-tooltip v-if="row.serviceId" :content="`ID: ${row.serviceId}`" placement="top">
              <span class="id-hint">{{ row.serviceId }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.envDisplayName')" min-width="130" show-overflow-tooltip header-align="center">
          <template #default="{ row }">
            <span>{{ getEnvDisplayName(row.envId) }}</span>
            <el-tooltip v-if="row.envId" :content="`ID: ${row.envId}`" placement="top">
              <span class="id-hint">{{ row.envId }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.hostDisplayName')" min-width="250" header-align="center">
          <template #default="{ row }">
            <div class="host-cell">
              <el-tooltip :content="getHostDisplayName(row.hostId)" placement="top" :trigger="['hover', 'focus']">
                <el-button link type="primary" size="small" class="host-link" @click="goToHost(row)">
                  {{ getHostDisplayName(row.hostId) }}
                </el-button>
              </el-tooltip>
              <el-tooltip v-if="row.hostId" :content="`ID: ${row.hostId}`" placement="top">
                <span class="id-hint">{{ row.hostId }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="instanceType" :label="t('runtimeInstances.instanceType')" min-width="120" show-overflow-tooltip header-align="center" />
        <el-table-column prop="instanceStatus" :label="t('runtimeInstances.instanceStatus')" width="140" align="center" header-align="center">
          <template #default="{ row }">
            <!-- 登记态：来自实例登记的 instance_status，不是进程实时探测结果 -->
            <StatusTag kind="instanceStatus" :code="row.instanceStatus" />
          </template>
        </el-table-column>
        <el-table-column prop="healthStatus" :label="t('runtimeInstances.healthStatus')" width="130" align="center" header-align="center">
          <template #default="{ row }">
            <!-- 健康检查记录：UNKNOWN 表示尚无法确认，不等同于 DOWN -->
            <StatusTag kind="instanceHealthStatus" :code="row.healthStatus" />
          </template>
        </el-table-column>
        <el-table-column prop="versionId" :label="t('runtimeInstances.versionId')" width="90" align="center" header-align="center" />
        <el-table-column :label="t('runtimeInstances.resourcePool')" min-width="130" show-overflow-tooltip header-align="center">
          <template #default="{ row }">
            <span>{{ getResourcePoolName(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.currentVersion')" min-width="150" show-overflow-tooltip header-align="center">
          <template #default="{ row }">
            <span>{{ getCurrentVersionNo(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.deployStatus')" width="130" align="center" header-align="center">
          <template #default="{ row }">
            <!-- 最近一次部署执行结果，不代表服务此刻是否正常 -->
            <StatusTag
              kind="deployStatus"
              :code="getDeployStatus(row.id)"
              :empty-text="t('runtimeInstances.noDeploy')"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.deployTime')" width="180" header-align="center">
          <template #default="{ row }">
            <span class="cell-time">{{ formatRecordedTime(getDeployTime(row.id), locale) || t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.monitorStatus')" width="130" align="center" header-align="center">
          <template #default="{ row }">
            <!-- 无监控快照时是「未记录」，不再用字面量 UNKNOWN 冒充一个已采到的状态 -->
            <StatusTag kind="monitorStatus" :code="getMonitorStatus(row.id)" />
          </template>
        </el-table-column>
        <el-table-column prop="lastHeartbeat" :label="t('runtimeInstances.lastHeartbeat')" width="180" header-align="center" class-name="col-last-heartbeat">
          <template #default="{ row }">
            <!-- 心跳时间只按记录展示，没记录就写「未记录」，不伪造当前时间 -->
            <span class="cell-time">{{ formatRecordedTime(row.lastHeartbeat, locale) || t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <!--
          采集五列：读的都是后台采集器**已经记录**的结果。界面轮询只是重读数据库，
          既不会去连这台实例所在的主机，也不会替后台调度；与上面的「运行状态（实时探测）」不是一回事。
        -->
        <el-table-column :label="t('runtimeInstances.collectionSource')" width="140" align="center" header-align="center" class-name="col-collection-source">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionSource" :code="row.collection.source" />
            <span v-else class="cell-muted">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.collectionLastSuccessAt')" width="200" header-align="center" class-name="col-collection-last-success">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.collection"
              :content="collectionAttemptTitle(row.collection)"
              placement="top"
              :trigger="['hover', 'focus']"
            >
              <!-- 最近尝试时间与连续失败次数只在这里出现，所以必须键盘可达：hover 之外还要能 Tab 进来 -->
              <span class="cell-time" tabindex="0">
                {{ formatRecordedTime(row.collection.lastSuccessAt, locale) || t('statusSemantics.notRecorded') }}
              </span>
            </el-tooltip>
            <span v-else class="cell-time">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.collectionFreshness')" width="130" align="center" header-align="center" class-name="col-collection-freshness">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionFreshness" :code="row.collection.freshness" />
            <span v-else class="cell-muted">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.collectionResult')" width="150" align="center" header-align="center" class-name="col-collection-result">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionResult" :code="row.collection.result" />
            <span v-else class="cell-muted">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('runtimeInstances.collectionFailureReason')" width="150" align="center" header-align="center" class-name="col-collection-failure">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.collection?.errorCategory"
              :content="row.collection.errorSummary || ''"
              :disabled="!row.collection.errorSummary"
              placement="top"
              :trigger="['hover', 'focus']"
            >
              <!-- 失败摘要在别处看不到，同样要给键盘用户留入口 -->
              <StatusTag kind="collectionErrorCategory" :code="row.collection.errorCategory" tabindex="0" />
            </el-tooltip>
            <span v-else class="cell-muted">{{ t('runtimeInstances.collectionNoFailure') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="470" fixed="right" align="center" header-align="center">
          <template #default="{ row }">
            <!-- 动作名写明"实时探测"：这是唯一会真的连服务器取当前状态的动作 -->
            <el-button link type="primary" size="small" @click="showStatus(row)">
              {{ t('runtimeInstances.statusRealtime') }}
            </el-button>
            <el-button link type="info" size="small" @click="showLogs(row)">
              {{ t('runtimeInstances.logs') }}
            </el-button>
            <el-button link type="info" size="small" @click="showDeployConfig(row)">
              {{ t('runtimeInstances.deployConfig') }}
            </el-button>
            <el-tooltip content="跳转到该服务环境的当前版本列表" placement="top">
              <el-button link type="primary" size="small" @click="navigateToCurrentVersion(row)">
                当前版本
              </el-button>
            </el-tooltip>
            <!--
              停止 = 对服务器进程发信号；移除登记 = 只删 InfraHub 的登记记录。
              两个动作分开入口、分别授权，避免把"删了记录"误解成"进程已停"。
            -->
            <el-tooltip v-if="canStopInstance" :content="t('runtimeInstances.stopHint')" placement="top">
              <el-button link type="danger" size="small" :loading="stopping" @click="confirmStop(row)">
                {{ t('runtimeInstances.stopInstance') }}
              </el-button>
            </el-tooltip>
            <el-tooltip v-if="canDeregisterInstance" :content="t('runtimeInstances.deregisterHint')" placement="top">
              <el-button link type="warning" size="small" @click="confirmDeregister(row)">
                {{ t('runtimeInstances.deregister') }}
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- Status Dialog -->
    <el-dialog v-model="statusVisible" :title="t('runtimeInstances.status')" width="760px">
      <el-alert
        class="probe-note"
        type="info"
        show-icon
        :closable="false"
        :title="t('runtimeInstances.statusProbeNote')"
      />
      <el-skeleton v-if="statusLoading" :rows="6" animated />
      <template v-else>
        <el-alert v-if="statusError" :title="statusError" type="error" show-icon :closable="false" />
        <template v-if="status">
          <div class="instance-header">
            <span class="instance-header-item"><b>{{ t('runtimeInstances.instanceId') }}:</b> {{ selectedInstance?.id }}</span>
            <span class="instance-header-item"><b>{{ t('runtimeInstances.instanceName') }}:</b> {{ selectedInstance?.instanceName }}</span>
          </div>
          <div class="quick-summary">
            <div class="summary-item" :class="{ 'summary-success': status.running, 'summary-danger': status.instanceStatus === 'STOPPED' }">
              <span class="summary-label">{{ status.composeProject ? 'Docker Compose' : t('runtimeInstances.process') }}</span>
              <StatusTag kind="instanceStatus" :code="status.instanceStatus ?? (status.running ? 'RUNNING' : 'STOPPED')" />
            </div>
            <div v-if="status.instanceStatus !== 'UNKNOWN'" class="summary-item" :class="{ 'summary-success': status.portListening, 'summary-danger': !status.portListening }">
              <span class="summary-label">{{ t('runtimeInstances.port') }} {{ deployConfig?.deployPort ?? '-' }}</span>
              <el-tag :type="status.portListening ? 'success' : 'danger'" size="small">
                {{ status.portListening ? t('runtimeInstances.portListening') : t('runtimeInstances.portNotListening') }}
              </el-tag>
            </div>
            <!-- 健康检查取值可能无法识别，配色跟着语义走：认不出就不给成功/失败色 -->
            <div class="summary-item" :class="probeHealthClass">
              <span class="summary-label">{{ t('runtimeInstances.healthCheck') }}</span>
              <StatusTag kind="instanceHealthStatus" :code="status.healthStatus" />
            </div>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-if="status.composeProject" label="Docker Compose">{{ status.composeProject }}</el-descriptions-item>
            <el-descriptions-item v-else :label="t('runtimeInstances.pid')">{{ status.pid || '-' }}</el-descriptions-item>
            <el-descriptions-item :label="t('runtimeInstances.checkedAt')">
              {{ formatRecordedTime(status.checkedAt, locale) || t('statusSemantics.notRecorded') }}
            </el-descriptions-item>
            <el-descriptions-item v-if="status.errorMessage" :label="t('runtimeInstances.errorMessage')" :span="2">
              {{ status.errorMessage }}
            </el-descriptions-item>
          </el-descriptions>
          <section v-if="status?.healthBody" class="diagnostic-section">
            <div class="diagnostic-title">{{ t('runtimeInstances.healthBody') }}</div>
            <pre class="diagnostic-log">{{ status.healthBody }}</pre>
          </section>
        </template>
        <el-empty v-if="!status && !statusError" :description="t('runtimeInstances.statusEmpty')" />
      </template>
    </el-dialog>

    <!-- Logs Dialog -->
    <el-dialog v-model="logsVisible" :title="t('runtimeInstances.logs')" width="1000px">
      <RuntimeLogPanel
        :active="logsVisible" :target-key="logsInstance?.id ?? ''"
        :content="logs?.content" :log-path="logs?.logPath" :fetched-at="logs?.fetchedAt"
        :loading="logsLoading" :failed="Boolean(logsError) || logs?.success === false"
        :downloadable="logs?.success === true" :lines="logsLines"
        @refresh="refreshLogs" @update:lines="changeLogsLines"
      >
        <el-alert v-if="logsError" :title="logsError" type="error" show-icon :closable="false" />
        <el-alert v-if="logs && !logs.success" :title="logs.errorMessage || '读取运行日志失败'" type="error" show-icon :closable="false" />
      </RuntimeLogPanel>
    </el-dialog>

    <!-- Deploy Config Dialog -->
    <el-dialog v-model="deployConfigVisible" :title="t('runtimeInstances.deployConfig')" width="760px">
      <el-skeleton v-if="deployConfigLoading" :rows="6" animated />
      <template v-else>
        <el-alert v-if="deployConfigError" :title="deployConfigError" type="error" show-icon :closable="false" />
        <template v-if="deployConfig">
          <div class="instance-header">
            <span class="instance-header-item"><b>实例ID:</b> {{ selectedInstance?.id }}</span>
            <span class="instance-header-item"><b>实例名:</b> {{ selectedInstance?.instanceName }}</span>
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="配置来源">{{ deployConfig.source }}</el-descriptions-item>
            <el-descriptions-item label="部署根目录">{{ deployConfig.deployRoot }}</el-descriptions-item>
            <el-descriptions-item label="端口">{{ deployConfig.deployPort }}</el-descriptions-item>
            <el-descriptions-item label="PID 文件">{{ deployConfig.pidFile }}</el-descriptions-item>
            <el-descriptions-item label="日志文件">{{ deployConfig.logFile }}</el-descriptions-item>
            <el-descriptions-item label="健康检查">{{ deployConfig.healthUrl }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-if="!deployConfig && !deployConfigError" description="暂无部署配置" />
      </template>
    </el-dialog>

    <!-- Stop Result Dialog：结论以 outcome 为准，不把"请求成功"当成"已停止" -->
    <el-dialog v-model="stopResultVisible" :title="t('runtimeInstances.stopResultTitle')" width="720px">
      <template v-if="stopResult">
        <div class="instance-header">
          <span class="instance-header-item">
            <b>{{ t('runtimeInstances.instanceName') }}:</b>
            {{ stopResultInstance?.instanceName || stopResult.instanceId }}
          </span>
          <span class="instance-header-item">
            <b>{{ t('runtimeInstances.hostDisplayName') }}:</b>
            {{ getHostDisplayName(stopResult.hostId) }}
          </span>
        </div>
        <el-alert
          class="stop-result-alert"
          :type="stopOutcomeTagType(stopResult.outcome)"
          :title="stopOutcomeLabel(stopResult.outcome)"
          :description="stopResult.message"
          show-icon
          :closable="false"
        />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('runtimeInstances.checkedAt')">
            {{ formatRecordedTime(stopResult.checkedAt, locale) || t('statusSemantics.notRecorded') }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="stop-result-note">{{ t('runtimeInstances.stopResultNote') }}</div>
      </template>
    </el-dialog>

    <StatusLegendDialog v-model="legendVisible" :kinds="instanceStatusKinds" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageRuntimeServiceInstances,
  getRuntimeServiceInstanceStatus,
  getRuntimeServiceInstanceLogs,
  getRuntimeServiceInstanceDeployConfig,
  getRuntimeServiceInstanceResourceView,
  stopRuntimeServiceInstance,
  deregisterRuntimeServiceInstance,
  type RuntimeServiceInstance,
  type RuntimeServiceInstanceStatus,
  type RuntimeServiceInstanceLogs,
  type RuntimeServiceInstanceDeployConfig,
  type RuntimeInstanceResourceView,
  type RuntimeInstanceStopResult
} from '@/api/runtime'
import { pageAssetHosts, type AssetHost } from '@/api/asset'
import { COLLECTION_TARGET_TYPE, type CollectionStateView } from '@/api/monitorCollection'
import { useCollectionStates } from '@/composables/useCollectionStates'
import { serviceApi, envApi } from '@/api/resource'
import { useAuthStore } from '@/store/auth'
import CollectionScheduleControl from '@/components/monitor/CollectionScheduleControl.vue'
import RegisterInstanceButton from '@/components/resource/RegisterInstanceButton.vue'
import RuntimeLogPanel from '@/components/resource/RuntimeLogPanel.vue'
import StatusTag from '@/components/status/StatusTag.vue'
import StatusLegendDialog from '@/components/status/StatusLegendDialog.vue'
import {
  describeStatus,
  formatRecordedTime,
  listStatusValues,
  statusLabel,
  type StatusKind
} from '@/utils/statusSemantics'

interface Service {
  id: number;
  serviceCode: string;
  serviceName: string;
}

interface Env {
  id: number;
  envCode: string;
  envName: string;
}

const router = useRouter()
const route = useRoute()
const { t, locale } = useI18n()

const tableData = ref<RuntimeServiceInstance[]>([])
const total = ref(0)

// Lookup data
const serviceMap = ref<Map<number, Service>>(new Map())
const envMap = ref<Map<number, Env>>(new Map())
const hostMap = ref<Map<number, AssetHost>>(new Map())
const serviceOptions = ref<Service[]>([])
const envOptions = ref<Env[]>([])
const hostOptions = ref<AssetHost[]>([])

// Resource view data
const resourceViewMap = ref<Map<number, RuntimeInstanceResourceView>>(new Map())

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  instanceStatus: '',
  healthStatus: '',
  serviceId: undefined as number | undefined,
  envId: undefined as number | undefined,
  hostId: undefined as number | undefined
})

// Dialogs
const statusVisible = ref(false)
const statusLoading = ref(false)
const status = ref<RuntimeServiceInstanceStatus | null>(null)
const statusError = ref('')
const logsVisible = ref(false)
const logsLoading = ref(false)
const logs = ref<RuntimeServiceInstanceLogs | null>(null)
const logsError = ref('')
const logsInstance = ref<RuntimeServiceInstance | null>(null)
const logsLines = ref(200)
let logsSequence = 0
const deployConfigVisible = ref(false)
const deployConfigLoading = ref(false)
const deployConfig = ref<RuntimeServiceInstanceDeployConfig | null>(null)
const deployConfigError = ref('')
const selectedInstance = ref<RuntimeServiceInstance | null>(null)

// ---- 单实例生命周期操作（停止进程 / 移除登记）----
// 两者是不同动作：停止作用于服务器进程，移除只作用于 InfraHub 的登记记录。
const authStore = useAuthStore()
const canStopInstance = computed(() => authStore.canAccess(['RELEASE_EXECUTE', 'SYSTEM_ADMIN']))
const canDeregisterInstance = computed(() => authStore.canAccess(['SYSTEM_ADMIN']))

const stopResultVisible = ref(false)
const stopResult = ref<RuntimeInstanceStopResult | null>(null)
const stopResultInstance = ref<RuntimeServiceInstance | null>(null)
const stopping = ref(false)

const stopOutcomeTagType = (outcome?: string) => {
  switch (outcome) {
    case 'STOPPED':
      return 'success'
    case 'UNSUPPORTED':
      return 'info'
    case 'FAILED':
      return 'error'
    default:
      // IDENTITY_UNCONFIRMED / REFUSED / UNKNOWN 都不是"已停止"
      return 'warning'
  }
}

const STOP_OUTCOME_LABEL_KEYS: Record<string, string> = {
  STOPPED: 'stopOutcomeStopped',
  IDENTITY_UNCONFIRMED: 'stopOutcomeIdentityUnconfirmed',
  UNSUPPORTED: 'stopOutcomeUnsupported',
  REFUSED: 'stopOutcomeRefused',
  FAILED: 'stopOutcomeFailed',
  UNKNOWN: 'stopOutcomeUnknown'
}

const stopOutcomeLabel = (outcome?: string) =>
  t(`runtimeInstances.${STOP_OUTCOME_LABEL_KEYS[outcome || ''] || 'stopOutcomeUnknown'}`)

const instanceIdentityText = (row: RuntimeServiceInstance) =>
  [
    t('runtimeInstances.instanceName'),
    row.instanceName || '-',
    t('runtimeInstances.serviceDisplayName'),
    getServiceDisplayName(row.serviceId),
    t('runtimeInstances.envDisplayName'),
    getEnvDisplayName(row.envId),
    t('runtimeInstances.hostDisplayName'),
    getHostDisplayName(row.hostId)
  ].join(' ') // 用空格连接即可，不拼 HTML，实例名不会被当作标记渲染

const confirmStop = async (row: RuntimeServiceInstance) => {
  try {
    await ElMessageBox.confirm(
      `${t('runtimeInstances.stopConfirmBody')} ${instanceIdentityText(row)}`,
      t('runtimeInstances.stopConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('runtimeInstances.stopConfirmOk'),
        cancelButtonText: t('common.cancel')
      }
    )
  } catch {
    return
  }
  stopping.value = true
  stopResultInstance.value = row
  try {
    const result = await stopRuntimeServiceInstance(row.id)
    stopResult.value = result
    stopResultVisible.value = true
    // 只有"已观察到进程退出"才算成功；当前后端一律返回身份无法确认
    if (result.outcome === 'STOPPED') {
      ElMessage.success(t('runtimeInstances.stopOutcomeStopped'))
    } else {
      ElMessage.warning(t('runtimeInstances.stopNotStopped'))
    }
    void manualSync()
  } catch {
    // 网络/权限错误已由 http 拦截器提示
  } finally {
    stopping.value = false
  }
}

const confirmDeregister = async (row: RuntimeServiceInstance) => {
  try {
    await ElMessageBox.confirm(
      `${t('runtimeInstances.deregisterConfirmBody')} ${instanceIdentityText(row)}`,
      t('runtimeInstances.deregisterConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('runtimeInstances.deregisterConfirmOk'),
        cancelButtonText: t('common.cancel')
      }
    )
  } catch {
    return
  }
  try {
    const result = await deregisterRuntimeServiceInstance(row.id)
    ElMessage.success(result.message || t('runtimeInstances.deregisterSucceeded'))
    void manualSync()
  } catch {
    // 阻碍项（执行中的发布/回滚、监控配置指向）由后端在错误消息里说明，拦截器已展示
  }
}

// 状态取值与文案统一来自 statusSemantics：筛选下拉、表格单元格、状态说明弹窗共用一份定义
const toStatusFilterOptions = (kind: StatusKind) =>
  listStatusValues(kind).map(item => ({ code: item.code, label: statusLabel(item, t) }))

const instanceStatusFilterOptions = computed(() => toStatusFilterOptions('instanceStatus'))
const healthStatusFilterOptions = computed(() => toStatusFilterOptions('instanceHealthStatus'))

const instanceStatusKinds: StatusKind[] = [
  'instanceStatus',
  'instanceHealthStatus',
  'deployStatus',
  'monitorStatus',
  'collectionSource',
  'collectionFreshness',
  'collectionResult',
  'collectionErrorCategory'
]

const legendVisible = ref(false)

// ---- 一轮同步（只读、只轮询数据库） ----
// 「一轮」= 重读列表（原状态列 instanceStatus / healthStatus）+ 重读采集状态（采集列），
// 两者同属一个令牌。只轮询采集表的话，采集器已经观测到 STOPPED，列表却还挂着 RUNNING。
// 这里不发起 SSH 探测、不写库，也不代替后端调度；「运行状态（实时探测）」才是真去连主机的入口。
/**
 * 资源视图是随列表带出来的附属数据，不是状态列；它沿用同一轮的 `isCurrent()`，
 * 但不阻塞整轮判定。
 */
const loadResourceViewData = async (instances: RuntimeServiceInstance[], isCurrent: () => boolean) => {
  // Clear previous data; isCurrent() identifies which round the data belongs to
  resourceViewMap.value.clear()

  // Batch load resource view data in background with true concurrency limit
  const concurrencyLimit = 5
  const delayBetweenBatches = 50

  for (let i = 0; i < instances.length; i += concurrencyLimit) {
    // Stop if a newer intent has arrived (this round is no longer the current one)
    if (!isCurrent()) {
      return
    }

    const batch = instances.slice(i, i + concurrencyLimit)
    // Use Promise.all for true concurrent execution within limit
    await Promise.all(batch.map(async (instance) => {
      // Check again before each request
      if (!isCurrent()) {
        return
      }
      try {
        const view = await getRuntimeServiceInstanceResourceView(instance.id)
        // Only write if this round is still the current one (not stale)
        if (isCurrent() && view) {
          resourceViewMap.value.set(instance.id, view)
        }
      } catch (err) {
        // Log warning but don't affect main list display
        console.warn(`Failed to load resource view for instance ${instance.id}:`, err)
      }
    }))

    // Small delay between batches to avoid overwhelming the server
    if (i + concurrencyLimit < instances.length) {
      await new Promise(resolve => setTimeout(resolve, delayBetweenBatches))
    }
  }
}

/**
 * 列表重读：**原状态列（instanceStatus / healthStatus）的唯一写入者**。
 *
 * `isCurrent()` 代表「这一轮是否仍是用户的当前意图」，新筛选/翻页意图一到就立刻变 false，
 * 所以旧一轮晚到的响应不会写进已经换过条件的表格。
 * 失败必须抛出去，让协调器判定整轮失败并标注过期——页面不自己吞掉错误。
 */
async function fetchInstancePage(_token: number, isCurrent: () => boolean): Promise<void> {
  const response = await pageRuntimeServiceInstances(queryParams)
  if (!isCurrent()) {
    // 期间用户改了筛选条件/翻页，这一份是上一轮问题的答案，丢掉
    return
  }
  tableData.value = response.records
  total.value = response.total
  // 资源视图不阻塞整轮判定，但写入同样受本轮有效性约束
  void loadResourceViewData(response.records, isCurrent)
}

const {
  stateMap: collectionStateMap,
  collectionEnabled,
  pollingPaused,
  listStale,
  collectionStale,
  lastSyncedAt,
  loading,
  syncNow
} = useCollectionStates({
  targetType: COLLECTION_TARGET_TYPE.SERVICE_INSTANCE,
  targetIds: () => tableData.value.map(row => row.id),
  reloadList: fetchInstancePage
})

/**
 * 手动同步（点刷新、改筛选、翻页）。
 *
 * 只在 `FAILED` 时提示：`COALESCED` 表示这一轮已被更新的意图取代（旧轮次还在飞时排队，
 * 或用户又改了条件），此时没有任何请求失败，弹"加载失败"是假报错。
 * 被合并的手动调用拿到的是**补跑**的真实结论，所以"改完筛选后接口真的挂了"照样提示。
 */
const manualSync = async () => {
  const outcome = await syncNow({ manual: true })
  if (outcome === 'FAILED') {
    ElMessage.error(t('common.loadFailed'))
  }
}

/** 列表或采集状态任一侧读取失败时，旧值仍在显示——这件事必须在页面上说出来 */
const staleNotice = computed(() => {
  if (listStale.value && collectionStale.value) {
    return t('runtimeInstances.syncStaleBoth')
  }
  if (listStale.value) {
    return t('runtimeInstances.syncStaleList')
  }
  if (collectionStale.value) {
    return t('runtimeInstances.syncStaleCollection')
  }
  return ''
})

const staleNoticeDetail = computed(() => {
  const time = lastSyncedAt.value
    ? formatRecordedTime(lastSyncedAt.value, locale.value)
    : t('statusSemantics.notRecorded')
  return t('runtimeInstances.syncStaleDetail', { time })
})

/** 表格行 = 实例登记 + 该实例当前的采集状态 */
type InstanceTableRow = RuntimeServiceInstance & { collection?: CollectionStateView }

// 采集状态后到，表体由它派生：避免在模板里反复查 Map，也避免两个数据源各写各的
const tableRows = computed<InstanceTableRow[]>(() =>
  tableData.value.map(row => ({ ...row, collection: collectionStateMap.value.get(row.id) }))
)

/** 「最近成功观察时间」格子的提示：把最近尝试时间、连续失败次数、被丢弃的旧样本都摊开 */
const collectionAttemptTitle = (state: CollectionStateView): string => {
  const parts: string[] = []
  const attempt = formatRecordedTime(state.lastAttemptAt, locale.value)
  parts.push(t('runtimeInstances.collectionLastAttempt', { time: attempt || t('statusSemantics.notRecorded') }))
  if (state.consecutiveFailureCount) {
    parts.push(t('runtimeInstances.collectionFailureCount', { count: state.consecutiveFailureCount }))
  }
  if (state.discardedSampleCount) {
    parts.push(t('runtimeInstances.collectionDiscardedCount', { count: state.discardedSampleCount }))
  }
  return parts.join(' · ')
}

// 实时探测的健康检查结论：认不出的取值不给成功/失败底色，避免把未知当故障
const probeHealthClass = computed(() => {
  const tone = describeStatus('instanceHealthStatus', status.value?.healthStatus).tone
  if (tone === 'success') return { 'summary-success': true }
  if (tone === 'danger') return { 'summary-danger': true }
  return {}
})

const getErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error) {
    return error.message || fallback
  }
  return fallback
}

const getServiceDisplayName = (serviceId?: number) => {
  if (!serviceId) return '-'
  const svc = serviceMap.value.get(serviceId)
  return svc?.serviceName || `服务 ${serviceId}`
}

const getEnvDisplayName = (envId?: number) => {
  if (!envId) return '-'
  const env = envMap.value.get(envId)
  return env?.envName || `环境 ${envId}`
}

const getHostDisplayName = (hostId?: number) => {
  if (!hostId) return '-'
  const host = hostMap.value.get(hostId)
  return host ? `${host.hostname} (${host.ip})` : `主机 ${hostId}`
}

type HostOptionLike = { hostname?: string; ip?: string }

// 主机选项文本（主机名 + IP）与表格列共用同一套拼接，避免两处格式漂移
const hostOptionLabel = (host: HostOptionLike) =>
  host.hostname ? `${host.hostname}${host.ip ? ` (${host.ip})` : ''}` : '-'

// 已选主机全文：只认当前 hostId 在 hostOptions（下拉实际渲染的那份数据）里命中的那一条，
// 每个 computed 重算，所以切换后立刻跟着变；清空、id 解析不到主机、主机缺 hostname 时一律为空
// （不回落成 `主机 ${id}` / undefined 之类的占位提示）。
const selectedHostFullText = computed(() => {
  const hostId = queryParams.hostId
  if (typeof hostId !== 'number') return ''
  const host = hostOptions.value.find(item => item.id === hostId)
  return host?.hostname ? hostOptionLabel(host) : ''
})

// Resource view helpers
const getResourcePoolName = (instanceId: number) => {
  const view = resourceViewMap.value.get(instanceId)
  return view?.resourcePoolName || '-'
}

const getCurrentVersionNo = (instanceId: number) => {
  const view = resourceViewMap.value.get(instanceId)
  return view?.currentVersionNo || '-'
}

const getDeployStatus = (instanceId: number) => {
  const view = resourceViewMap.value.get(instanceId)
  return view?.latestDeployStatus || null
}

const getDeployTime = (instanceId: number) => {
  const view = resourceViewMap.value.get(instanceId)
  return view?.latestDeployAt || null
}

const getMonitorStatus = (instanceId: number) => {
  const view = resourceViewMap.value.get(instanceId)
  return view?.monitorStatus || null
}

const goToHost = (row: RuntimeServiceInstance) => {
  const host = hostMap.value.get(row.hostId)
  if (host) {
    router.push({
      path: '/resource/hosts',
      query: { keyword: host.hostname }
    })
  } else if (row.hostId) {
    router.push({
      path: '/resource/hosts',
      query: { keyword: `host-${row.hostId}` }
    })
  }
}

// V-D: navigateToCurrentVersion — jump to current versions filtered by serviceId/envId
const navigateToCurrentVersion = (row: RuntimeServiceInstance) => {
  const query: Record<string, string> = {}
  if (row.serviceId) query.serviceId = String(row.serviceId)
  if (row.envId) query.envId = String(row.envId)
  router.push({ path: '/release/current-versions', query })
}

const loadLookupData = async () => {
  try {
    const [svcRes, envRes, hostRes] = await Promise.all([
      serviceApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Service[] }>,
      envApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Env[] }>,
      pageAssetHosts({ pageNum: 1, pageSize: 200 })
    ])
    serviceOptions.value = svcRes.records || []
    envOptions.value = envRes.records || []
    hostOptions.value = hostRes.records || []
    serviceMap.value = new Map(serviceOptions.value.map(s => [s.id, s]))
    envMap.value = new Map(envOptions.value.map(e => [e.id, e]))
    hostMap.value = new Map(hostOptions.value.map(h => [h.id, h]))
  } catch (err) {
    console.error('Failed to load lookup data:', err)
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  void manualSync()
}

const handleRefresh = () => {
  void manualSync()
}

const handleSizeChange = (size: number) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  void manualSync()
}

const handleCurrentChange = (page: number) => {
  queryParams.pageNum = page
  void manualSync()
}

const showStatus = async (row: RuntimeServiceInstance) => {
  statusVisible.value = true
  statusLoading.value = true
  status.value = null
  statusError.value = ''
  deployConfig.value = null
  selectedInstance.value = row
  try {
    deployConfig.value = await getRuntimeServiceInstanceDeployConfig(row.id)
    status.value = await getRuntimeServiceInstanceStatus(row.id)
  } catch (error) {
    statusError.value = getErrorMessage(error, '读取运行状态失败')
  } finally {
    statusLoading.value = false
  }
}

const showLogs = async (row: RuntimeServiceInstance) => {
  logsSequence += 1
  logsInstance.value = { ...row }
  logsLines.value = 200
  logs.value = null
  logsError.value = ''
  logsLoading.value = false
  logsVisible.value = true
  await refreshLogs()
}

const refreshLogs = async () => {
  const instance = logsInstance.value
  if (!instance || !logsVisible.value || logsLoading.value) return
  const sequence = ++logsSequence
  const isCurrent = () => sequence === logsSequence && logsVisible.value && logsInstance.value?.id === instance.id
  logsLoading.value = true
  logsError.value = ''
  try {
    const response = await getRuntimeServiceInstanceLogs(instance.id, logsLines.value)
    if (!isCurrent()) return
    if (response.instanceId !== instance.id || response.serviceId !== instance.serviceId || response.envId !== instance.envId) {
      logsError.value = t('runtimeLogs.scopeError')
      return
    }
    logs.value = response
  } catch (error) {
    if (isCurrent()) logsError.value = getErrorMessage(error, '读取运行日志失败')
  } finally {
    if (isCurrent()) logsLoading.value = false
  }
}
const changeLogsLines = (lines: number) => { logsLines.value = lines; void refreshLogs() }
watch(logsVisible, (visible) => {
  if (!visible) { logsSequence += 1; logsLoading.value = false }
}, { flush: 'sync' })
onBeforeUnmount(() => { logsSequence += 1 })

const showDeployConfig = async (row: RuntimeServiceInstance) => {
  deployConfigVisible.value = true
  deployConfigLoading.value = true
  deployConfig.value = null
  deployConfigError.value = ''
  selectedInstance.value = row
  try {
    deployConfig.value = await getRuntimeServiceInstanceDeployConfig(row.id)
  } catch (error) {
    deployConfigError.value = getErrorMessage(error, '读取部署配置失败')
  } finally {
    deployConfigLoading.value = false
  }
}

const initFromQuery = () => {
  const { keyword, serviceId, envId, hostId } = route.query
  if (typeof keyword === 'string' && keyword.trim()) {
    queryParams.keyword = keyword.trim()
  }
  if (typeof serviceId === 'string') {
    const parsed = parseInt(serviceId, 10)
    if (!isNaN(parsed)) queryParams.serviceId = parsed
  }
  if (typeof envId === 'string') {
    const parsed = parseInt(envId, 10)
    if (!isNaN(parsed)) queryParams.envId = parsed
  }
  if (typeof hostId === 'string') {
    const parsed = parseInt(hostId, 10)
    if (!isNaN(parsed)) queryParams.hostId = parsed
  }
}

onMounted(async () => {
  await loadLookupData()
  initFromQuery()
  // 首轮与后续自动轮询走同一条同步入口：不绕开"不重叠"与令牌守卫
  void manualSync()
})
</script>

<style scoped>
.runtime-instances-view {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
  min-width: 0;
  overflow-wrap: break-word;
  font-size: var(--text-xl);
  font-weight: 500;
  letter-spacing: var(--tracking-display);
  line-height: 1.25;
  color: var(--color-ink);
}

.table-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--color-ink);
}

/* 表头操作区：状态说明 / 刷新列表 并列 */
.card-header__actions {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-2);
}

/* 实时探测提示：说明结果不落库，避免与列表里的登记状态混淆 */
.probe-note {
  margin-bottom: var(--ih-spacing-4);
}

/* 停止结果：结论条与说明之间留白，避免把说明读成结论的一部分 */
.stop-result-alert {
  margin-bottom: var(--ih-spacing-3);
}

.stop-result-note {
  margin-top: var(--ih-spacing-3);
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-xs);
  line-height: 1.6;
}

/* 记录时间：等宽数字 + 不换行，缺值时由文案说明「未记录」 */
.cell-time {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* 采集提示条：紧贴表格上方，不抢占筛选区 */
.collection-alert {
  margin-bottom: 12px;
}

.cell-muted {
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-xs);
}

/*
 * 筛选区：控件按"真实显示文本"预算固定宽度，并禁止 flex 压缩。
 * 此前 6 个控件全是内联 150px/120px 且不换行，1024 下主机选择被压到约 119px、
 * 状态选择被压到约 95px，已选主机名 + IP 只剩前缀；1440 下也不够一行。
 * 现在允许有序换行，控件宽度与弹层宽度分开处理（弹层见全局 .ih-select-popper-wide），
 * 短值选择框（状态）不跟着拉宽。
 */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.filter-bar > * {
  flex: 0 0 auto;
}

.filter-keyword {
  width: 200px;
}

.filter-service {
  width: 200px;
}

.filter-env {
  width: 160px;
}

.filter-host {
  width: 240px;
}

.filter-status {
  width: 150px;
}

/* 长选项（服务名、主机名 + IP）自身省略，完整值由 title 属性提示 */
.option-full-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.id-hint {
  font-size: var(--ih-font-size-xs);
  color: var(--ih-text-secondary);
  margin-left: 4px;
  cursor: help;
}

/*
 * 主机列：主机名 + IP 是列内最宽的可见内容，190px 列宽（.cell 可用约 164px）
 * 装不下这个链接按钮，按钮会顶出 .cell 被裁掉。这里把列加宽到 250px，
 * 同时让按钮在真正超长时自己省略（而不是靠 overflow: hidden 把越界藏掉），
 * 完整主机名 + IP 由 hover/focus 均可触发的 tooltip 提供。
 */
.host-cell {
  display: flex;
  align-items: center;
  min-width: 0;
}

.host-link {
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
}

.host-link :deep(> span) {
  display: block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Dialog styles */
.instance-header {
  display: flex;
  gap: 20px;
  margin-bottom: var(--space-md);
  padding: 12px;
  background-color: var(--ih-bg-subtle);
  border-radius: 4px;
}

.instance-header-item {
  font-size: 14px;
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

.diagnostic-section {
  margin-top: var(--space-md);
}

.diagnostic-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
}

.diagnostic-log {
  background-color: var(--ih-bg-subtle);
  border: 1px solid var(--ih-border);
  border-radius: 4px;
  padding: 12px;
  font-family: var(--ih-font-family-mono);
  font-size: 13px;
  white-space: pre-wrap;
  word-wrap: break-word;
  max-height: 300px;
  overflow: auto;
}

.runtime-log {
  max-height: 400px;
}

.logs-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.text-placeholder {
  color: var(--ih-text-secondary);
  font-size: 12px;
}
</style>
