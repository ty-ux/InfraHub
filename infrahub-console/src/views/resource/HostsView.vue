<template>
  <div class="hosts-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('hosts.title') }}</h2>
    </div>

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('hosts.list') }}</span>
          <div class="card-header__actions">
            <CollectionScheduleControl @changed="handleRefresh" />
            <el-button size="small" @click="legendVisible = true">
              {{ t('hosts.legendButton') }}
            </el-button>
            <!--
              「刷新列表」只重读已记录的数据，不做实时探测：这是与运行实例页「运行状态（实时探测）」
              的关键区别，所以按钮文案与提示都写明这一点。
            -->
            <el-tooltip :content="t('hosts.refreshListHint')" placement="top">
              <el-button size="small" class="refresh-list-btn" :loading="loading" @click="handleRefresh">
                {{ t('hosts.refreshList') }}
              </el-button>
            </el-tooltip>
            <!-- 提交中禁止再次登记：否则会换掉表单，让进行中的响应失去归属 -->
            <el-button type="primary" size="small" :disabled="submitting" @click="openCreateDialog">
              {{ t('hosts.createHost') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="filter-bar">
        <el-input
          v-model="queryParams.keyword"
          :placeholder="t('hosts.searchPlaceholder')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select
          v-model="queryParams.envId"
          :placeholder="t('hosts.envDisplayName')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option
            v-for="env in envOptions"
            :key="env.id"
            :label="env.envName"
            :value="env.id"
          />
        </el-select>
        <el-select
          v-model="queryParams.resourcePoolId"
          :placeholder="t('hosts.resourcePoolDisplayName')"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option
            v-for="pool in poolOptions"
            :key="pool.id"
            :label="pool.poolName"
            :value="pool.id"
          />
        </el-select>
        <!-- 状态筛选项与表格单元格共用同一份取值文案（statusSemantics），含 UNKNOWN -->
        <el-select
          v-model="queryParams.hostStatus"
          :placeholder="t('hosts.hostStatus')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option
            v-for="option in hostStatusFilterOptions"
            :key="option.code"
            :label="option.label"
            :value="option.code"
          />
        </el-select>
        <el-select
          v-model="queryParams.agentStatus"
          :placeholder="t('hosts.agentStatus')"
          clearable
          style="width: 120px"
          @change="handleSearch"
        >
          <el-option
            v-for="option in agentStatusFilterOptions"
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
        :title="t('hosts.collectionDisabledNotice')"
        :description="t('hosts.collectionDisabledDetail')"
      />
      <!-- 连续读取失败后自动轮询会停下，这件事不能悄悄发生 -->
      <el-alert
        v-if="pollingPaused"
        class="collection-alert"
        type="info"
        show-icon
        :closable="false"
        :title="t('hosts.collectionPollingPaused')"
        :description="t('hosts.collectionPollingPausedDetail')"
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

      <el-table :data="tableRows" style="width: 100%" @row-click="handleRowClick">
        <el-table-column prop="hostname" :label="t('hosts.hostname')" min-width="240" show-overflow-tooltip />
        <el-table-column prop="ip" :label="t('hosts.ip')" width="150" show-overflow-tooltip />
        <el-table-column :label="t('hosts.envDisplayName')" width="140">
          <template #default="{ row }">
            <div class="cell-ellipsis">
              <el-tooltip :content="getEnvDisplayName(row.envId)" placement="top">
                <span class="cell-ellipsis__text">{{ getEnvDisplayName(row.envId) }}</span>
              </el-tooltip>
              <el-tooltip v-if="row.envId" :content="`ID: ${row.envId}`" placement="top">
                <span class="id-hint">{{ row.envId }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.resourcePoolDisplayName')" width="140">
          <template #default="{ row }">
            <div class="cell-ellipsis">
              <el-tooltip :content="getPoolDisplayName(row.resourcePoolId)" placement="top">
                <span class="cell-ellipsis__text">{{ getPoolDisplayName(row.resourcePoolId) }}</span>
              </el-tooltip>
              <el-tooltip v-if="row.resourcePoolId" :content="`ID: ${row.resourcePoolId}`" placement="top">
                <span class="id-hint">{{ row.resourcePoolId }}</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="osType" :label="t('hosts.osType')" width="100" show-overflow-tooltip />
        <el-table-column prop="hostType" :label="t('hosts.hostType')" width="110" show-overflow-tooltip />
        <el-table-column :label="t('hosts.specs')" min-width="280">
          <template #default="{ row }">
            <div class="spec-list">
              <span>CPU: {{ formatSpec(row.cpuCores) }}</span>
              <span>MEM: {{ formatSpec(row.memoryGb, 'GB') }}</span>
              <span>DISK: {{ formatSpec(row.diskGb, 'GB') }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.healthStatus')" width="130" align="center">
          <template #default="{ row }">
            <StatusTag
              v-if="row.derivedHealthStatus"
              kind="hostHealthStatus"
              :code="row.derivedHealthStatus"
            />
            <span v-else class="spec-unfilled">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="hostStatus" :label="t('hosts.hostStatus')" width="150" align="center">
          <template #default="{ row }">
            <StatusTag kind="hostStatus" :code="row.hostStatus" />
          </template>
        </el-table-column>
        <el-table-column prop="agentStatus" :label="t('hosts.agentStatus')" width="140" align="center">
          <template #default="{ row }">
            <StatusTag kind="agentStatus" :code="row.agentStatus" />
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.runtimeInstances')" width="120" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.runtimeInstanceCount > 0"
              link
              type="primary"
              size="small"
              @click.stop="viewRuntimeInstances(row)"
            >
              {{ row.runtimeInstanceCount }}
            </el-button>
            <span v-else>0</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastHeartbeat" :label="t('hosts.lastHeartbeat')" width="190" class-name="col-last-heartbeat">
          <template #default="{ row }">
            <!-- 无心跳记录时不填时间、也不伪造：直接说明「未记录」 -->
            <span class="cell-time">{{ formatRecordedTime(row.lastHeartbeat, locale) || t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <!--
          采集五列：读的都是后台采集器**已经记录**的结果。界面轮询只是重读数据库，
          既不会去探这台主机，也不会替后台调度。
        -->
        <el-table-column :label="t('hosts.collectionSource')" width="140" align="center" class-name="col-collection-source">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionSource" :code="row.collection.source" />
            <span v-else class="spec-unfilled">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.collectionLastSuccessAt')" width="200" class-name="col-collection-last-success">
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
        <el-table-column :label="t('hosts.collectionFreshness')" width="130" align="center" class-name="col-collection-freshness">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionFreshness" :code="row.collection.freshness" />
            <span v-else class="spec-unfilled">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.collectionResult')" width="150" align="center" class-name="col-collection-result">
          <template #default="{ row }">
            <StatusTag v-if="row.collection" kind="collectionResult" :code="row.collection.result" />
            <span v-else class="spec-unfilled">{{ t('statusSemantics.notRecorded') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('hosts.collectionFailureReason')" width="150" align="center" class-name="col-collection-failure">
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
            <span v-else class="cell-muted">{{ t('hosts.collectionNoFailure') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="250" align="center" fixed="right">
          <template #default="{ row }">
            <HostSshSetupButton :host-id="row.id" />
            <el-button link type="primary" size="small" @click.stop="viewRuntimeInstances(row)">
              {{ t('hosts.viewRuntimeInstances') }}
            </el-button>
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

    <!-- Host Profile Drawer -->
    <el-drawer
      v-model="drawerVisible"
      :title="t('hosts.profileTitle')"
      direction="rtl"
      size="400px"
      v-loading="profileLoading"
    >
      <div v-if="hostProfile" class="profile-content">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('hosts.hostname')">
            {{ hostProfile.hostname }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.ip')">
            {{ hostProfile.ip }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.envDisplayName')">
            {{ hostProfile.envName || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.resourcePoolDisplayName')">
            {{ hostProfile.resourcePoolName || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.runtimeInstances')">
            {{ hostProfile.runtimeInstanceCount }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.healthStatus')">
            <StatusTag kind="hostHealthStatus" :code="hostProfile.healthStatus" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.healthReason')">
            {{ hostProfile.healthReason }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.latestDeployAt')">
            {{ formatRecordedTime(hostProfile.latestDeployAt, locale) || t('statusSemantics.notRecorded') }}
            <StatusTag
              v-if="hostProfile.latestDeployStatus"
              kind="deployStatus"
              :code="hostProfile.latestDeployStatus"
            />
          </el-descriptions-item>
          <el-descriptions-item :label="t('hosts.latestMonitorSnapshotAt')">
            {{ formatRecordedTime(hostProfile.latestMonitorSnapshotAt, locale) || t('statusSemantics.notRecorded') }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <div v-else class="profile-empty">
        {{ t('hosts.profileNotFound') }}
      </div>
    </el-drawer>

    <!--
      手动登记主机。
      提交期间这个弹窗是冻结的：X（before-close）、Escape、遮罩关闭、取消按钮全部被拦下，
      表单字段也一并禁用，用户无法在响应回来之前改内容或换一张表单。
    -->
    <el-dialog
      v-model="createVisible"
      :title="t('hosts.createTitle')"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="!submitting"
      :before-close="handleCreateDialogBeforeClose"
    >
      <el-alert
        class="register-alert"
        type="info"
        show-icon
        :closable="false"
        :title="t('hosts.registerTitle')"
        :description="t('hosts.registerNote')"
      />
      <p class="register-note">{{ t('hosts.registerVsSsh') }}</p>

      <!-- 提交中禁用整张表单，字段值在响应回来前不可再改 -->
      <el-form
        ref="formRef"
        :model="createFormData"
        :rules="createFormRules"
        :disabled="submitting"
        label-width="110px"
        @submit.prevent
      >
        <el-form-item :label="t('hosts.hostname')" prop="hostname">
          <el-input v-model="createFormData.hostname" :placeholder="t('hosts.hostnamePlaceholder')" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('hosts.ip')" prop="ip">
          <el-input v-model="createFormData.ip" :placeholder="t('hosts.ipPlaceholder')" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('hosts.envDisplayName')" prop="envId">
          <el-select v-model="createFormData.envId" :placeholder="t('hosts.envPlaceholder')" style="width: 100%">
            <el-option v-for="env in envOptions" :key="env.id" :label="env.envName" :value="env.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('hosts.resourcePoolDisplayName')" prop="resourcePoolId">
          <el-select
            v-model="createFormData.resourcePoolId"
            :placeholder="t('hosts.poolPlaceholder')"
            clearable
            style="width: 100%"
          >
            <el-option v-for="pool in poolOptions" :key="pool.id" :label="pool.poolName" :value="pool.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('hosts.osType')" prop="osType">
          <el-input v-model="createFormData.osType" :placeholder="t('hosts.osTypePlaceholder')" maxlength="32" />
        </el-form-item>
        <el-form-item :label="t('hosts.hostType')" prop="hostType">
          <el-input v-model="createFormData.hostType" :placeholder="t('hosts.hostTypePlaceholder')" maxlength="32" />
        </el-form-item>
        <el-form-item :label="t('hosts.cpuCores')" prop="cpuCores">
          <el-input-number v-model="createFormData.cpuCores" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('hosts.memoryGb')" prop="memoryGb">
          <el-input-number v-model="createFormData.memoryGb" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('hosts.diskGb')" prop="diskGb">
          <el-input-number v-model="createFormData.diskGb" :min="0" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="register-spec-hint">{{ t('hosts.specOptional') }}</p>

      <template #footer>
        <el-button :disabled="submitting" @click="closeCreateDialog">{{ t('common.cancel') }}</el-button>
        <!-- 提交中同时禁用与转圈：按钮既点不动，也能看出这次提交还在飞 -->
        <el-button type="primary" :disabled="submitting" :loading="submitting" @click="handleCreateSubmit">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <StatusLegendDialog v-model="legendVisible" :kinds="hostStatusKinds" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormItemRule, FormRules } from 'element-plus'
import {
  pageAssetHosts,
  pageResourcePools,
  getHostProfile,
  createAssetHost,
  type CreateAssetHostPayload,
  type AssetHost,
  type ResourcePool,
  type HostProfile
} from '@/api/asset'
import { envApi } from '@/api/resource'
import { COLLECTION_TARGET_TYPE, type CollectionStateView } from '@/api/monitorCollection'
import { useCollectionStates } from '@/composables/useCollectionStates'
import CollectionScheduleControl from '@/components/monitor/CollectionScheduleControl.vue'
import HostSshSetupButton from '@/components/resource/HostSshSetupButton.vue'
import StatusTag from '@/components/status/StatusTag.vue'
import StatusLegendDialog from '@/components/status/StatusLegendDialog.vue'
import {
  listStatusValues,
  statusLabel,
  formatRecordedTime,
  type StatusKind
} from '@/utils/statusSemantics'
import { CODE_MAX_LENGTH, CODE_PATTERN, CPU_CORES_MAX, CAPACITY_GB_MAX, HOSTNAME_MAX_LENGTH, HOSTNAME_PATTERN, IP_MAX_LENGTH, isIpLiteral, isSpecValueAllowed } from '@/utils/hostValidation'

interface Env {
  id: number;
  envCode: string;
  envName: string;
}

interface AssetHostWithProfile extends AssetHost {
  derivedHealthStatus?: string;
  healthReason?: string;
  runtimeInstanceCount?: number;
}

/** 表格行 = 主机台账 + 该主机当前的采集状态（采集状态由后台采集器记录，界面只是读它） */
type HostTableRow = AssetHostWithProfile & { collection?: CollectionStateView };

const router = useRouter()
const route = useRoute()
const { t, locale } = useI18n()

const tableData = ref<AssetHostWithProfile[]>([])
const total = ref(0)

// Lookup data
const envMap = ref<Map<number, Env>>(new Map())
const poolMap = ref<Map<number, ResourcePool>>(new Map())
const envOptions = ref<Env[]>([])
const poolOptions = ref<ResourcePool[]>([])

// Profile drawer
const drawerVisible = ref(false)
const profileLoading = ref(false)
const hostProfile = ref<HostProfile | null>(null)

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  hostStatus: '',
  agentStatus: '',
  envId: undefined as number | undefined,
  resourcePoolId: undefined as number | undefined
})

// 状态取值与文案统一来自 statusSemantics：筛选下拉、表格单元格、说明弹窗共用一份定义
const toStatusFilterOptions = (kind: StatusKind) =>
  listStatusValues(kind).map(item => ({ code: item.code, label: statusLabel(item, t) }))

const hostStatusFilterOptions = computed(() => toStatusFilterOptions('hostStatus'))
const agentStatusFilterOptions = computed(() => toStatusFilterOptions('agentStatus'))

const hostStatusKinds: StatusKind[] = [
  'hostStatus',
  'agentStatus',
  'hostHealthStatus',
  'deployStatus',
  'collectionSource',
  'collectionFreshness',
  'collectionResult',
  'collectionErrorCategory'
]

const legendVisible = ref(false)

// ---- 一轮同步（只读、只轮询数据库） ----
// 「一轮」= 重读列表（原状态列 + 健康推导）+ 重读采集状态（采集列），两者同属一个令牌。
// 只轮询采集表的话，hostStatus 会永远停在进页面时的值——采集表说 STOPPED，列表还挂着 RUNNING。
// 这里不发起 SSH 探测、不写库，也不代替后端调度：真正去探主机的是后端调度器。

/**
 * 列表重读：**原状态列（hostStatus）与健康推导的唯一写入者**。
 *
 * `isCurrent()` 由协调器给出，代表「这一轮是否仍是用户的当前意图」。
 * 新筛选/翻页意图一到它就**立刻**变 false——不是等下一轮开始——所以旧一轮的晚到响应
 * 不会写进已经换过条件的表格。每次写入前都要重新问一次，不能只在开头问一次。
 *
 * 失败必须抛出去，让协调器判定整轮失败并标注过期——页面不自己吞掉错误。
 */
async function fetchHostPage(_token: number, isCurrent: () => boolean): Promise<void> {
  const response = await pageAssetHosts(queryParams)
  if (!isCurrent()) {
    // 期间用户改了筛选条件/翻页，这一份是上一轮问题的答案，丢掉
    return
  }
  tableData.value = response.records.map(host => ({
    ...host,
    derivedHealthStatus: undefined,
    healthReason: undefined,
    runtimeInstanceCount: 0
  }))
  total.value = response.total
  // 健康推导与列表同属一轮：先清空再逐台补，避免把上一页的值挂到新行上
  await loadHealthStatusBatch(tableData.value, isCurrent)
}

const loadHealthStatusBatch = async (hosts: AssetHostWithProfile[], isCurrent: () => boolean) => {
  for (const host of hosts) {
    if (!isCurrent()) {
      return
    }
    try {
      const profile = await getHostProfile(host.id)
      if (profile && isCurrent()) {
        host.derivedHealthStatus = profile.healthStatus
        host.healthReason = profile.healthReason
        host.runtimeInstanceCount = profile.runtimeInstanceCount
      }
    } catch (err) {
      // 单台主机的健康推导取不到：不退化成"故障"，也不让整轮失败
      console.warn(`Failed to load profile for host ${host.id}:`, err)
    }
  }
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
  targetType: COLLECTION_TARGET_TYPE.HOST,
  targetIds: () => tableData.value.map(row => row.id),
  reloadList: fetchHostPage
})

/**
 * 手动同步（点刷新、改筛选、翻页、登记成功后回读）。
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
    return t('hosts.syncStaleBoth')
  }
  if (listStale.value) {
    return t('hosts.syncStaleList')
  }
  if (collectionStale.value) {
    return t('hosts.syncStaleCollection')
  }
  return ''
})

const staleNoticeDetail = computed(() => {
  const time = lastSyncedAt.value
    ? formatRecordedTime(lastSyncedAt.value, locale.value)
    : t('statusSemantics.notRecorded')
  return t('hosts.syncStaleDetail', { time })
})

// 采集状态后到，表体由它派生：避免在模板里反复查 Map，也避免两个数据源各写各的
const tableRows = computed<HostTableRow[]>(() =>
  tableData.value.map(row => ({ ...row, collection: collectionStateMap.value.get(row.id) }))
)

/** 「最近成功观察时间」格子的提示：把最近尝试时间、连续失败次数、被丢弃的旧样本都摊开 */
const collectionAttemptTitle = (state: CollectionStateView): string => {
  const parts: string[] = []
  const attempt = formatRecordedTime(state.lastAttemptAt, locale.value)
  parts.push(t('hosts.collectionLastAttempt', { time: attempt || t('statusSemantics.notRecorded') }))
  if (state.consecutiveFailureCount) {
    parts.push(t('hosts.collectionFailureCount', { count: state.consecutiveFailureCount }))
  }
  if (state.discardedSampleCount) {
    parts.push(t('hosts.collectionDiscardedCount', { count: state.discardedSampleCount }))
  }
  return parts.join(' · ')
}

// 规格：未登记（null/undefined/0）时说明「未填写」，不把空值渲染成 0 造成"已登记"的错觉
const formatSpec = (value: number | null | undefined, unit = '') => {
  if (value === null || value === undefined || value === 0) {
    return t('hosts.specUnfilled')
  }
  return `${value.toFixed(1)}${unit}`
}

const getEnvDisplayName = (envId?: number) => {
  if (!envId) return '-'
  const env = envMap.value.get(envId)
  return env?.envName || `环境 ${envId}`
}

const getPoolDisplayName = (poolId?: number) => {
  if (!poolId) return '-'
  const pool = poolMap.value.get(poolId)
  return pool?.poolName || `资源池 ${poolId}`
}

const loadLookupData = async () => {
  try {
    const [envRes, poolRes] = await Promise.all([
      envApi.page({ pageNum: 1, pageSize: 200 }) as Promise<{ records: Env[] }>,
      pageResourcePools({ pageNum: 1, pageSize: 200 })
    ])
    envOptions.value = envRes.records || []
    poolOptions.value = poolRes.records || []
    envMap.value = new Map(envOptions.value.map(e => [e.id, e]))
    poolMap.value = new Map(poolOptions.value.map(p => [p.id, p]))
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

const handleRowClick = async (row: AssetHostWithProfile) => {
  drawerVisible.value = true
  profileLoading.value = true
  hostProfile.value = null
  try {
    hostProfile.value = await getHostProfile(row.id)
  } catch (err) {
    console.error('Failed to load host profile:', err)
    ElMessage.error(t('hosts.profileLoadFailed'))
  } finally {
    profileLoading.value = false
  }
}

const viewRuntimeInstances = (row: AssetHost) => {
  router.push({
    path: '/resource/runtime-instances',
    query: { hostId: row.id }
  })
}

// ---- 手动登记主机 ----
// 前端规则与后端 CreateAssetHostRequest / AssetHostService 一一对应，只做即时反馈；
// 必填、长度、格式、环境/资源池是否存在、主机名与 IP 是否重复最终仍由后端裁决。
const createVisible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

type CreateHostForm = {
  hostname: string
  ip: string
  envId?: number
  resourcePoolId?: number
  osType: string
  hostType: string
  cpuCores?: number
  memoryGb?: number
  diskGb?: number
}

/**
 * 每次打开弹窗都换一个全新的表单对象，而不是把同一个对象清空复用。
 *
 * 这样「这次提交属于哪张表单」可以靠对象身份判断：提交时记下当时那个对象，
 * 响应回来时如果弹窗里的已经不是它了，就说明这是迟到的旧响应，不能再拿它去关闭或清空当前表单。
 */
const emptyCreateForm = (): CreateHostForm => ({
  hostname: '',
  ip: '',
  envId: undefined,
  resourcePoolId: undefined,
  osType: '',
  hostType: '',
  cpuCores: undefined,
  memoryGb: undefined,
  diskGb: undefined
})

const createFormData = ref<CreateHostForm>(emptyCreateForm())

const createFormRules: FormRules = {
  hostname: [
    { required: true, message: t('hosts.requiredRule'), trigger: 'blur' },
    { max: 128, message: t('hosts.hostnameRule'), trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const trimmed = (value || '').trim()
        if (!trimmed || HOSTNAME_PATTERN.test(trimmed)) {
          callback()
        } else {
          callback(new Error(t('hosts.hostnameRule')))
        }
      },
      trigger: 'blur'
    }
  ],
  ip: [
    { required: true, message: t('hosts.requiredRule'), trigger: 'blur' },
    { max: 64, message: t('hosts.ipRule'), trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const trimmed = (value || '').trim()
        if (!trimmed || isIpLiteral(trimmed)) {
          callback()
        } else {
          callback(new Error(t('hosts.ipRule')))
        }
      },
      trigger: 'blur'
    }
  ],
  envId: [{ required: true, message: t('hosts.requiredRule'), trigger: 'change' }],
  osType: [
    { required: true, message: t('hosts.requiredRule'), trigger: 'blur' },
    { max: 32, message: t('hosts.osTypeRule'), trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const trimmed = (value || '').trim()
        if (!trimmed || CODE_PATTERN.test(trimmed)) {
          callback()
        } else {
          callback(new Error(t('hosts.osTypeRule')))
        }
      },
      trigger: 'blur'
    }
  ],
  hostType: [
    { required: true, message: t('hosts.requiredRule'), trigger: 'blur' },
    { max: 32, message: t('hosts.hostTypeRule'), trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const trimmed = (value || '').trim()
        if (!trimmed || CODE_PATTERN.test(trimmed)) {
          callback()
        } else {
          callback(new Error(t('hosts.hostTypeRule')))
        }
      },
      trigger: 'blur'
    }
  ],
  cpuCores: [createSpecRule(CPU_CORES_MAX, 'hosts.cpuRule')],
  memoryGb: [createSpecRule(CAPACITY_GB_MAX, 'hosts.memoryRule')],
  diskGb: [createSpecRule(CAPACITY_GB_MAX, 'hosts.diskRule')]
}

/**
 * 规格字段规则：留空表示「未填写」，放行并在提交时省略该字段；
 * 一旦填写就必须是明确可用值——大于 0、不超过列宽上限、最多 2 位小数。
 * 显式 0 会被拒绝而不是当成未填写，避免把"确实填了 0"静默改写成"没登记"。
 */
function createSpecRule(max: number, messageKey: string): FormItemRule {
  return {
    validator: (_rule, value: unknown, callback) => {
      if (isSpecValueAllowed(value, max)) {
        callback()
      } else {
        callback(new Error(t(messageKey)))
      }
    },
    trigger: 'change'
  }
}

const resetCreateForm = () => {
  createFormData.value = emptyCreateForm()
  formRef.value?.clearValidate()
}

const openCreateDialog = () => {
  // 提交还在飞的时候不开新表单：否则进行中的响应会失去可归属的表单
  if (submitting.value) return
  resetCreateForm()
  createVisible.value = true
}

const closeCreateDialog = () => {
  if (submitting.value) return
  createVisible.value = false
}

/**
 * 弹窗关闭的统一入口。
 *
 * Element Plus 的 X 按钮、Escape、遮罩点击最终都会走到 before-close，
 * 在这里统一拦下，保证提交期间没有任何一条路能把弹窗关掉。
 */
const handleCreateDialogBeforeClose = (done: () => void) => {
  if (submitting.value) return
  done()
}

const buildCreatePayload = (): CreateAssetHostPayload => {
  const form = createFormData.value
  const payload: CreateAssetHostPayload = {
    hostname: form.hostname.trim(),
    ip: form.ip.trim(),
    envId: form.envId as number,
    osType: form.osType.trim(),
    hostType: form.hostType.trim()
  }
  if (form.resourcePoolId !== undefined && form.resourcePoolId !== null) {
    payload.resourcePoolId = form.resourcePoolId
  }
  // 规格留空就不提交，由后端按「未登记」落库，不用 0 冒充已知规格
  if (form.cpuCores !== undefined && form.cpuCores !== null) {
    payload.cpuCores = form.cpuCores
  }
  if (form.memoryGb !== undefined && form.memoryGb !== null) {
    payload.memoryGb = form.memoryGb
  }
  if (form.diskGb !== undefined && form.diskGb !== null) {
    payload.diskGb = form.diskGb
  }
  return payload
}

// 提交前的兜底校验：即使字段级校验被略过，也不把非法数据发给后端。
// 弹窗里已有的字段级规则负责逐字段提示，这一层只保证「不放行」。
const createFieldLabels = {
  hostname: 'hosts.hostname',
  ip: 'hosts.ip',
  envId: 'hosts.envDisplayName',
  osType: 'hosts.osType',
  hostType: 'hosts.hostType',
  cpuCores: 'hosts.cpuCores',
  memoryGb: 'hosts.memoryGb',
  diskGb: 'hosts.diskGb'
} as const

const createFormRejection = (): string | null => {
  const form = createFormData.value
  const complain = (field: keyof typeof createFieldLabels, ruleKey: string) =>
    `${t(createFieldLabels[field])}：${t(ruleKey)}`

  const hostname = form.hostname.trim()
  if (!hostname) return complain('hostname', 'hosts.requiredRule')
  if (hostname.length > HOSTNAME_MAX_LENGTH || !HOSTNAME_PATTERN.test(hostname)) {
    return complain('hostname', 'hosts.hostnameRule')
  }

  const ip = form.ip.trim()
  if (!ip) return complain('ip', 'hosts.requiredRule')
  if (ip.length > IP_MAX_LENGTH || !isIpLiteral(ip)) return complain('ip', 'hosts.ipRule')

  if (form.envId === undefined || form.envId === null) {
    return complain('envId', 'hosts.requiredRule')
  }

  const osType = form.osType.trim()
  if (!osType) return complain('osType', 'hosts.requiredRule')
  if (osType.length > CODE_MAX_LENGTH || !CODE_PATTERN.test(osType)) {
    return complain('osType', 'hosts.osTypeRule')
  }

  const hostType = form.hostType.trim()
  if (!hostType) return complain('hostType', 'hosts.requiredRule')
  if (hostType.length > CODE_MAX_LENGTH || !CODE_PATTERN.test(hostType)) {
    return complain('hostType', 'hosts.hostTypeRule')
  }

  if (!isSpecValueAllowed(form.cpuCores, CPU_CORES_MAX)) {
    return complain('cpuCores', 'hosts.cpuRule')
  }
  if (!isSpecValueAllowed(form.memoryGb, CAPACITY_GB_MAX)) {
    return complain('memoryGb', 'hosts.memoryRule')
  }
  if (!isSpecValueAllowed(form.diskGb, CAPACITY_GB_MAX)) {
    return complain('diskGb', 'hosts.diskRule')
  }

  return null
}

const handleCreateSubmit = async () => {
  if (submitting.value) return
  const form = formRef.value
  if (!form) return
  try {
    await form.validate()
  } catch {
    // 校验失败：保留已填内容，让用户就地修正
    return
  }
  const rejection = createFormRejection()
  if (rejection) {
    ElMessage.error(rejection)
    return
  }

  // 归属凭据：记下这次提交属于哪个表单对象。
  const submittedForm = createFormData.value
  submitting.value = true
  try {
    await createAssetHost(buildCreatePayload())
    // 真正成功的登记通知不能被静默丢弃，先无条件提示
    ElMessage.success(t('hosts.createSuccess'))
    // 只有弹窗里还是这次提交的那张表单，才有权关闭并清空它；
    // 否则说明这是迟到的旧响应，不能拿它去动别人正在填的内容。
    if (createFormData.value === submittedForm) {
      createFormData.value = emptyCreateForm()
      createVisible.value = false
    }
    await manualSync()
  } catch (err) {
    // 失败原因（含权限不足、重复登记、关联对象不存在）已由 http 拦截器统一提示，
    // 这里保持弹窗与表单内容不变，避免用户重复提交。
    console.error('Failed to create host:', err)
  } finally {
    submitting.value = false
  }
}

const initFromQuery = () => {
  const { keyword, envId, resourcePoolId } = route.query
  if (typeof keyword === 'string' && keyword.trim()) {
    queryParams.keyword = keyword.trim()
  }
  if (typeof envId === 'string') {
    const parsed = parseInt(envId, 10)
    if (!isNaN(parsed)) queryParams.envId = parsed
  }
  if (typeof resourcePoolId === 'string') {
    const parsed = parseInt(resourcePoolId, 10)
    if (!isNaN(parsed)) queryParams.resourcePoolId = parsed
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
.hosts-view {
  padding: 0;
}

.page-header {
  margin-bottom: var(--space-md);
}

.page-title {
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

/* 表头操作区：状态说明 / 刷新列表 / 登记主机 三个入口并列 */
.card-header__actions {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-2);
}

/* 登记说明：区分台账登记与 SSH 连接配置，避免被误读成"接入主机" */
.register-alert {
  margin-bottom: var(--ih-spacing-3);
}

.register-note {
  margin: 0 0 var(--ih-spacing-4);
  font-size: var(--ih-font-size-sm);
  color: var(--ih-text-secondary);
  line-height: 1.6;
}

.register-spec-hint {
  margin: var(--ih-spacing-2) 0 0;
  padding-left: 112px;
  font-size: var(--ih-font-size-xs);
  color: var(--ih-text-secondary);
}

.spec-unfilled {
  color: var(--ih-text-secondary);
  font-size: var(--ih-font-size-xs);
}

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.pagination-wrapper {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
}

.id-hint {
  font-size: var(--ih-font-size-xs);
  color: var(--ih-text-secondary);
  margin-left: 0;
  cursor: help;
  flex: none;
}

/* 名称类单元格：文本超长省略，ID 提示始终可见 */
.cell-ellipsis {
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-1);
  min-width: 0;
}

.cell-ellipsis__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 规格：按 CPU/MEM/DISK 分段换行，不在数值与单位中间断开 */
.spec-list {
  display: flex;
  flex-wrap: wrap;
  column-gap: var(--ih-spacing-3);
  row-gap: var(--ih-spacing-1);
}

.spec-list > span {
  white-space: nowrap;
}

.spec-list > span:not(:last-child)::after {
  content: '|';
  margin-left: var(--ih-spacing-3);
  color: var(--ih-text-placeholder);
}

/* 心跳：保持既有时间格式，仅禁止随机断行 */
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

.profile-content {
  padding: 16px;
}

.profile-empty {
  padding: 32px;
  text-align: center;
  color: var(--ih-text-secondary);
}
</style>