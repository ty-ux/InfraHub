<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    direction="rtl"
    size="480px"
    :before-close="handleClose"
  >
    <div v-if="loading" class="loading-container">
      <el-skeleton :rows="8" animated />
    </div>

    <!-- 读取失败：给出原因与只读重试，不与“没有数据”混淆 -->
    <div v-else-if="loadFailure" class="state-block" role="alert">
      <el-icon class="state-icon"><WarningFilled /></el-icon>
      <div class="state-main">
        <div class="state-title">{{ t('releaseDetail.loadErrorTitle') }}</div>
        <div class="state-text">{{ loadFailure.reason || t('releaseDetail.loadErrorFallback') }}</div>
        <div class="state-hint">
          {{ t('releaseDetail.loadErrorHint') }}
        </div>
      </div>
      <el-button size="small" :loading="loading" @click="loadDetail()">{{ t('releaseDetail.retryReadOnly') }}</el-button>
    </div>

    <div v-else-if="detail" class="detail-content">
      <!-- 分组：对象与目标环境 -->
      <div class="group-title">{{ t('releaseDetail.groupTarget') }}</div>
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionBasic') }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="type === 'release' ? t('releaseDetail.orderNoRelease') : t('releaseDetail.orderNoRollback')">
            {{ factText(type === 'release' ? detail.orderNo : detail.rollbackNo) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.orderStatus')">
            <ReleaseStatusTag v-if="orderStatus" :status="orderStatus" size="small" />
            <span v-else class="missing-text">{{ t('releaseDetail.statusMissing') }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.app')">{{ factText(detail.appName) }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.service')">{{ factText(detail.serviceName) }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.env')">{{ factText(detail.envName) }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.createdAt')">{{ detail.createdAt ? formatDateTime(detail.createdAt) : t('releaseDetail.missingFact') }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.reason" :label="t('releaseDetail.reason')">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.approvalInfo')">
            <span class="fact-note">{{ t('releaseDetail.approvalNote') }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 分组：版本变化 -->
      <div class="group-title">{{ t('releaseDetail.groupVersion') }}</div>
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionVersion') }}</div>
        <el-descriptions :column="1" border size="small">
          <template v-if="type === 'release'">
            <el-descriptions-item :label="t('releaseDetail.releaseVersion')">{{ factText(detail.versionNo) }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item :label="t('releaseDetail.releaseOrderNo')">{{ factText(detail.releaseOrderNo) }}</el-descriptions-item>
            <el-descriptions-item :label="t('releaseDetail.fromVersion')">{{ factText(detail.fromVersionNo) }}</el-descriptions-item>
            <el-descriptions-item :label="t('releaseDetail.toVersion')">{{ factText(detail.toVersionNo) }}</el-descriptions-item>
          </template>
        </el-descriptions>
      </div>

      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionCurrentVersion') }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('releaseDetail.currentVersion')">{{ factText(detail.currentVersionNo) }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.currentVersionSourceRelease')">{{ factText(detail.currentVersionSourceReleaseOrderNo) }}</el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.currentVersionSourceRollback')">{{ factText(detail.currentVersionSourceRollbackOrderNo) }}</el-descriptions-item>
          <!--
            覆盖范围：指针只说“最近一次成功发布的版本号”，实例版本是逐实例的。
            FULL 只表示本次执行覆盖了当前实例集合，不代表当前登记版本一致；详细空间在下方独立区块。
          -->
          <el-descriptions-item v-if="type === 'release'" :label="t('releaseDetail.deployCoverage')">
            <DeployCoverageTag :coverage="coverageDetail" />
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 本次执行覆盖范围：覆盖了谁、谁未参与、谁所在批次未成功、谁已被移除/改绑 -->
      <div v-if="type === 'release' && coverageDetail" class="detail-section">
        <div class="section-title">{{ t('deployCoverage.sectionTitle') }}</div>
        <div class="coverage-head">
          <DeployCoverageTag :coverage="coverageDetail" />
          <span v-if="coverageTargetText" class="fact-note">
            {{ t('deployCoverage.targetVersion') }}: {{ coverageTargetText }}
          </span>
        </div>
        <div v-if="coverageStatement" class="fact-note">{{ coverageStatement }}</div>
        <div v-if="coverageNextStep" class="fact-note">{{ coverageNextStep }}</div>
        <div v-if="coverageDisclaimer" class="fact-note">{{ coverageDisclaimer }}</div>
        <div v-for="group in coverageGroups" :key="group.key" class="coverage-group">
          <div class="coverage-group-title">{{ group.title }}</div>
          <div class="coverage-id-list">
            <el-tag v-for="instanceId in group.ids" :key="instanceId" :type="group.tagType" size="small">
              #{{ instanceId }}
            </el-tag>
          </div>
        </div>
      </div>

      <!-- 当前登记版本一致性：逐实例比较平台登记版本，与“本次覆盖了谁”分开表达 -->
      <div v-if="type === 'release' && registrationDetail" class="detail-section">
        <div class="section-title">{{ t('registrationConsistency.sectionTitle') }}</div>
        <div class="coverage-head">
          <VersionRegistrationTag :consistency="registrationDetail" />
          <span class="fact-note">
            {{ t('registrationConsistency.targetVersion') }}: {{ registrationTargetText }}
          </span>
        </div>
        <div v-if="registrationSourceText" class="fact-note">
          {{ t('registrationConsistency.targetSource') }}: {{ registrationSourceText }}
        </div>
        <div v-if="registrationSourceMismatch" class="fact-note">
          {{ registrationSourceMismatch }}
        </div>
        <div v-if="registrationStatement" class="fact-note">{{ registrationStatement }}</div>
        <el-table
          v-if="registrationRows.length > 0"
          :data="registrationRows"
          size="small"
          border
          class="registration-table"
        >
          <el-table-column prop="instanceName" :label="t('registrationConsistency.instance')" min-width="140">
            <template #default="{ row }">
              {{ row.instanceName || `#${row.instanceId}` }}
            </template>
          </el-table-column>
          <el-table-column prop="hostName" :label="t('registrationConsistency.host')" min-width="120">
            <template #default="{ row }">
              {{ row.hostName || (row.hostId ? `#${row.hostId}` : '-') }}
            </template>
          </el-table-column>
          <el-table-column :label="t('registrationConsistency.registeredVersion')" min-width="140">
            <template #default="{ row }">
              {{ row.registeredVersionNo
                || (row.registeredVersionId ? `#${row.registeredVersionId}` : t('registrationConsistency.stateUnregistered')) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('registrationConsistency.state')" width="130">
            <template #default="{ row }">
              <el-tag :type="registrationStateTagType(row.state)" size="small">
                {{ registrationStateLabel(row.state) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="fact-note">{{ t('registrationConsistency.platformNote') }}</div>
      </div>

      <!-- 分组：执行状态 -->
      <div class="group-title">{{ t('releaseDetail.groupExecution') }}</div>
      <!-- F-D: Executor Info (release only) -->
      <div v-if="type === 'release'" class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionExecutor') }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('releaseDetail.executorType')">
            <el-tag :type="getExecutorTypeTagType(detail.executorType)" size="small">
              {{ getExecutorTypeLabel(detail.executorType) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.executorConfig')">
            {{ detail.executorConfigName || t('releaseDetail.executorConfigDefault') }}
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- E1: 灰度进度区块 (仅灰度发布单显示) -->
      <div v-if="type === 'release' && canaryPlan" class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionCanary') }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('releaseDetail.planStatus')">
            <el-tag :type="getCanaryStatusTagType(canaryPlan.status)" size="small">
              {{ getCanaryStatusLabel(canaryPlan.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('releaseDetail.batchProgress')">
            {{ getCanaryBatchProgress(canaryPlan) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="canaryPlan.autoRollbackEnabled" :label="t('releaseDetail.autoRollback')">
            <el-tag :type="canaryPlan.autoRollbackAvailable ? 'success' : 'warning'" size="small">
              {{ canaryPlan.autoRollbackAvailable
                ? t('releaseDetail.enabled')
                : t('releaseDetail.autoRollbackUnavailable') }}
            </el-tag>
          </el-descriptions-item>
          <!-- E2: 质量门禁配置 -->
          <el-descriptions-item v-if="canaryPlan.qualityGateEnabled !== undefined" :label="t('releaseDetail.qualityGate')">
            <el-tag :type="canaryPlan.qualityGateEnabled ? 'success' : 'info'" size="small">
              {{ canaryPlan.qualityGateEnabled ? t('releaseDetail.enabled') : t('releaseDetail.disabled') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="canaryPlan.qualityGateEnabled && canaryPlan.maxFiringAlerts !== undefined" :label="t('releaseDetail.alertThreshold')">
            {{ t('releaseDetail.alertThresholdCount', { count: canaryPlan.maxFiringAlerts }) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="canaryPlan.qualityGateEnabled && canaryPlan.gateFailureAction" :label="t('releaseDetail.failureAction')">
            {{ canaryPlan.gateFailureAction === 'BLOCK_AND_ROLLBACK' ? t('releaseDetail.failureActionBlockAndRollback') : t('releaseDetail.failureActionBlockOnly') }}
          </el-descriptions-item>
          <el-descriptions-item v-if="canaryPlan.executionOutcome" :label="t('releaseDetail.executionOutcome')">
            <el-tag :type="getExecutionOutcomeTagType(canaryPlan.executionOutcome)" size="small">
              {{ getExecutionOutcomeLabel(canaryPlan.executionOutcome) }}
            </el-tag>
            <span v-if="canaryPlan.executionOutcome === 'UNKNOWN' || canaryPlan.executionOutcome === 'PARTIAL'" class="failure-reason-text">
              {{ t('releaseDetail.executionOutcomeManualCheck') }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="canaryPlan.failureReason" :label="t('releaseDetail.failureReason')">
            <span class="failure-reason-text">{{ canaryPlan.failureReason }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <!-- 批次列表 -->
        <div v-if="canaryPlan.batches && canaryPlan.batches.length > 0" class="canary-batches">
          <div
            v-for="batch in canaryPlan.batches"
            :key="batch.id"
            class="batch-item"
            :class="{ 'is-current': batch.status === 'RUNNING' }"
          >
            <div class="batch-header">
              <span class="batch-no">{{ t('releaseDetail.batchLabel', { batchNo: batch.batchNo }) }}</span>
              <el-tag :type="getCanaryStatusTagType(batch.status)" size="small">
                {{ getCanaryStatusLabel(batch.status) }}
              </el-tag>
              <el-tag
                v-if="batch.executionOutcome && batch.executionOutcome !== 'SUCCEEDED'"
                :type="getExecutionOutcomeTagType(batch.executionOutcome)"
                size="small"
                effect="plain"
              >
                {{ getExecutionOutcomeLabel(batch.executionOutcome) }}
              </el-tag>
            </div>
            <div class="batch-meta">
              <span class="batch-instances">{{ t('releaseDetail.batchInstanceCount', { count: batch.instanceIds?.length || 0 }) }}</span>
              <span v-if="batch.startedAt" class="batch-time">
                {{ batch.status === 'RUNNING' ? t('releaseDetail.batchStarted') : t('releaseDetail.batchFinished') }}: {{ formatDateTime(batch.startedAt) }}
              </span>
            </div>
            <div v-if="batch.failureReason" class="batch-failure">{{ batch.failureReason }}</div>
            <!-- E2: 质量门禁状态 -->
            <div v-if="batch.qualityGateStatus && batch.qualityGateStatus !== 'NOT_EVALUATED'" class="batch-gate">
              <span class="gate-label">{{ t('releaseDetail.gateLabel') }}</span>
              <el-tag :type="getGateStatusTagType(batch.qualityGateStatus)" size="small">
                {{ getGateStatusLabel(batch.qualityGateStatus) }}
              </el-tag>
              <span v-if="batch.qualityGateReason" class="gate-reason">{{ batch.qualityGateReason }}</span>
              <!-- E2: 重评估按钮 -->
              <el-button
                v-if="batch.status === 'SUCCESS' && canaryPlan.status !== 'SUCCESS' && orderStatus === 'RUNNING'"
                size="small"
                text
                type="primary"
                @click="handleReEvaluateGate(batch)"
              >
                {{ t('releaseDetail.gateReEvaluate') }}
              </el-button>
              <!-- E3.1: 查看历史按钮 -->
              <el-button
                size="small"
                text
                type="info"
                @click="toggleBatchHistory(batch)"
              >
                {{ expandedBatchIds.has(batch.id) ? t('releaseDetail.gateHistoryCollapse') : t('releaseDetail.gateHistoryExpand') }}
              </el-button>
            </div>
            <!-- E3.1: 门禁评估历史 -->
            <div v-if="expandedBatchIds.has(batch.id) && gateEvaluationHistories.get(batch.id)" class="gate-history">
              <div v-if="gateEvaluationHistories.get(batch.id)?.length === 0" class="no-history">
                {{ t('releaseDetail.gateHistoryEmpty') }}
              </div>
              <div v-else class="history-list">
                <div
                  v-for="h in gateEvaluationHistories.get(batch.id)"
                  :key="h.id"
                  class="history-item"
                >
                  <div class="history-header">
                    <span class="history-type">{{ h.evaluationTypeLabel }}</span>
                    <el-tag :type="getGateStatusTagType(h.gateResult)" size="small">
                      {{ h.gateResultLabel }}
                    </el-tag>
                  </div>
                  <div class="history-meta">
                    <span>{{ t('releaseDetail.gateHistoryHit', { hit: h.hitCount, threshold: h.threshold }) }}</span>
                    <span>{{ formatDateTime(h.evaluatedAt) }}</span>
                  </div>
                  <div v-if="h.reason" class="history-reason">{{ h.reason }}</div>
                  <div v-if="h.monitorSnapshotId" class="history-links">
                    <el-button
                      size="small"
                      text
                      type="primary"
                      @click="openSnapshotFromHistory(h.monitorSnapshotId)"
                    >
                      {{ t('releaseDetail.snapshotLink', { id: h.monitorSnapshotId }) }}
                    </el-button>
                    <el-button
                      v-if="h.monitorAlertEventIds?.length"
                      size="small"
                      text
                      type="warning"
                      @click="openAlertEventsFromHistory(h.monitorAlertEventIds)"
                    >
                      {{ t('releaseDetail.alertEventCount', { count: h.monitorAlertEventIds.length }) }}
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 灰度信息读取失败：与“没有灰度计划”区分 -->
      <div v-if="type === 'release' && !canaryPlan && canaryFailure" class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionCanaryError') }}</div>
        <div class="aux-error" role="alert">
          <div class="aux-error-text">
            {{ t('releaseDetail.canaryReadError', { reason: canaryFailure.reason || t('releaseDetail.canaryReadErrorFallback') }) }}
          </div>
          <div class="aux-error-hint">{{ t('releaseDetail.canaryReadErrorHint') }}</div>
          <el-button size="small" text type="primary" @click="reloadCanary()">{{ t('releaseDetail.retryReadOnly') }}</el-button>
        </div>
      </div>

      <!-- Execution Summary -->
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionSummary') }}</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('releaseDetail.latestDeployStatus')">
            <DeployStatusTag :status="String(detail.latestDeployStatus ?? '')" size="small" />
            <span class="fact-note">{{ t('releaseDetail.latestDeployStatusNote') }}</span>
            <span v-if="String(detail.latestDeployStatus ?? '') === 'UNKNOWN'" class="fact-note">
              {{ t('releaseDetail.latestDeployStatusUnknownNote') }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="!hasFailureInfo && !isTerminalStatus" :label="t('releaseDetail.hintLabel')">
            <span class="status-hint">{{ executionHint }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 分组：失败原因 -->
      <div v-if="hasFailureInfo" class="detail-section">
        <div class="group-title">{{ t('releaseDetail.groupFailure') }}</div>
        <div v-for="item in failureReasons" :key="item.source" class="failure-item">
          <el-alert type="error" :closable="false" show-icon>
            <template #title>
              <span class="failure-hint-text">{{ t('releaseDetail.failureItem', { source: item.source, text: item.text }) }}</span>
            </template>
          </el-alert>
        </div>
        <div v-if="orderStatusFailedWithoutHint" class="failure-missing">
          {{ t('releaseDetail.failedWithoutHint') }}
        </div>
      </div>

      <!-- 分组：下一步入口 -->
      <div class="group-title">{{ t('releaseDetail.groupNextStep') }}</div>
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionNextStep') }}</div>
        <el-descriptions :column="1" border size="small">
          <!-- V1.20: 下一步建议，与列表页保持一致 -->
          <el-descriptions-item v-if="nextStepSuggestion" :label="t('releaseDetail.nextStepLabel')">
            <el-tag type="primary" size="small">{{ nextStepSuggestion }}</el-tag>
          </el-descriptions-item>
          <!-- V1.21: 动作后去向说明 -->
          <el-descriptions-item v-if="postActionGuide" :label="t('releaseDetail.postActionGuide')">
            <span class="post-action-guide">{{ postActionGuide }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="!nextStepSuggestion && !postActionGuide" :label="t('releaseDetail.nextStepLabel')">
            <span class="missing-text">{{ t('releaseDetail.nextStepMissing') }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 分组：历史证据 -->
      <div class="group-title">{{ t('releaseDetail.groupHistory') }}</div>
      <!-- Execution Timeline -->
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionTimeline') }}</div>
        <el-timeline v-if="detail.timeline && detail.timeline.length > 0">
          <el-timeline-item
            v-for="node in detail.timeline"
            :key="node.key"
            :type="getTimelineNodeType(node.status)"
            :timestamp="formatDateTime(node.time)"
            placement="top"
          >
            <div class="timeline-node">
              <div class="timeline-node-header">
                <span class="timeline-node-title">{{ node.title }}</span>
                <el-tag :type="getTimelineNodeType(node.status)" size="small">{{ getTimelineStatusLabel(node.status) }}</el-tag>
              </div>
              <div class="timeline-node-summary">{{ node.summary }}</div>
              <el-button
                v-if="node.actionType && node.actionType !== 'NONE'"
                size="small"
                text
                type="primary"
                @click="handleTimelineAction(node)"
              >
                {{ getTimelineActionLabel(node.actionType) }}
              </el-button>
            </div>
          </el-timeline-item>
        </el-timeline>
        <div v-else class="missing-text">{{ t('releaseDetail.timelineMissing') }}</div>
      </div>

      <!-- Execution History -->
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionHistory') }}</div>
        <div v-if="detail.executionHistory && detail.executionHistory.length > 0" class="execution-history-list">
          <div
            v-for="(item, index) in detail.executionHistory"
            :key="item.deployRecordId"
            class="history-item"
            @click="openHistoryRecord(item)"
          >
            <div class="history-header">
              <span class="history-index">{{ t('releaseDetail.historyAttempt', { n: item.attemptNumber || (detail.executionHistory.length - index) }) }}</span>
              <el-tag :type="getHistoryStatusType(item.status)" size="small">{{ getHistoryStatusLabel(item.status) }}</el-tag>
            </div>
            <div class="history-meta">
              <span class="history-type">{{ item.operationType || '-' }}</span>
              <span class="history-time">{{ formatDateTime(item.startedAt) }}</span>
            </div>
            <div v-if="item.failureHint" class="history-failure">{{ item.failureHint }}</div>
            <div v-else-if="item.summary" class="history-summary">{{ item.summary }}</div>
          </div>
        </div>
        <div v-else class="missing-text">{{ t('releaseDetail.historyMissing') }}</div>
      </div>

      <!-- Related Records -->
      <div class="detail-section">
        <div class="section-title">{{ t('releaseDetail.sectionRelated') }}</div>
        <div class="action-buttons">
          <el-button size="small" @click="openPrecheck">
            <el-icon><DocumentChecked /></el-icon>
            {{ t('releaseDetail.openPrecheck') }}
          </el-button>
          <el-button size="small" @click="openDeployRecords">
            <el-icon><List /></el-icon>
            {{ t('releaseDetail.openDeployRecords') }}
          </el-button>
          <el-button v-if="type === 'release' && detail.pipelineRunId" size="small" @click="openPipelineRun">
            <el-icon><Promotion /></el-icon>
            {{ t('releaseDetail.openPipelineLog') }}
          </el-button>
          <!-- T-C: Config check button (Contract 8.1) -->
          <el-button v-if="type === 'release'" size="small" type="warning" @click="openConfigCheck">
            <el-icon><Setting /></el-icon>
            {{ t('releaseDetail.openConfigCheck') }}
          </el-button>
          <!-- V-B: Cross-navigation: runtime instances (Contract 8) -->
          <el-button v-if="hasAppAndEnv" size="small" type="info" @click="navigateToRuntimeInstance">
            <el-icon><Monitor /></el-icon>
            {{ t('releaseDetail.openRuntimeInstance') }}
          </el-button>
          <!-- V-B: Cross-navigation: current versions (Contract 8) -->
          <el-button v-if="hasAppAndEnv" size="small" type="info" @click="navigateToCurrentVersion">
            <el-icon><Coin /></el-icon>
            {{ t('releaseDetail.openCurrentVersion') }}
          </el-button>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="handleClose">{{ t('releaseDetail.close') }}</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Coin, DocumentChecked, List, Monitor, Promotion, Setting, WarningFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { getReleaseDetail, getRollbackDetail, getCanaryPlan, evaluateBatchGate, getBatchGateEvaluations, type CanaryPlan, type CanaryBatch, type GateEvaluationHistory } from '@/api/release';
import { computeReleaseNextStep, computeRollbackNextStep } from '@/views/configs/page-configs';
import ReleaseStatusTag from '@/components/common/ReleaseStatusTag.vue';
import DeployStatusTag from '@/components/common/DeployStatusTag.vue';
import DeployCoverageTag from '@/components/release/DeployCoverageTag.vue';
import VersionRegistrationTag from '@/components/release/VersionRegistrationTag.vue';

interface Props {
  modelValue: boolean;
  id: number | null;
  type: 'release' | 'rollback';
  recentAction?: string;
}

interface Emits {
  (e: 'update:modelValue', value: boolean): void;
  (e: 'open-precheck'): void;
  (e: 'open-deploy-records'): void;
  (e: 'open-deploy-records-with-id', recordId: number): void;
  (e: 'check-deploy-config'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();
const router = useRouter();
const { t, locale } = useI18n();

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
});

/**
 * 读取失败信息：reason 是后端或异常给出的原文，原样展示、不翻译不改写；
 * reason 为空时才渲染当前语言下的兜底文案，因此兜底文案会随语言切换更新
 */
interface ReadFailure {
  reason: string | null;
}

const loading = ref(false);
const detail = ref<Record<string, unknown> | null>(null);

/**
 * 版本变更的实例覆盖范围（后端证据算出）。
 *
 * 只在发布单详情里展示：回滚单的部署记录不带实例身份，回滚来源的指针覆盖范围在结构上
 * 无法证明，该结论由“当前版本”页面统一按 verdict=UNPROVABLE 说明，不在这里另说一遍。
 *
 * FULL 只表示该次执行覆盖了当时的当前实例集合，不代表这些实例现在仍登记在该版本；
 * 当前登记版本由下面的 registrationConsistency 单独表达。
 */
interface DeployCoverageDetail {
  verdict?: string;
  evidenceProvable?: boolean;
  sourceType?: string;
  targetVersionId?: number;
  targetVersionNo?: string;
  deployedInstanceIds?: number[];
  uncoveredInstanceIds?: number[];
  unfinishedInstanceIds?: number[];
  removedInstanceIds?: number[];
  reboundInstanceIds?: number[];
  deployedCount?: number;
  uncoveredCount?: number;
  unfinishedCount?: number;
  currentInstanceCount?: number;
  statement?: string;
  nextStep?: string;
  disclaimer?: string;
}

interface RegistrationConsistencyDetail {
  status?: string;
  targetSourceType?: string;
  sourceOrderId?: number;
  sourceOrderNo?: string;
  targetVersionId?: number;
  targetVersionNo?: string;
  sourceConsistent?: boolean;
  sourceRejectionReason?: string | null;
  currentInstanceCount?: number;
  matchingCount?: number;
  differingCount?: number;
  unregisteredCount?: number;
  instances?: RegistrationInstanceRow[];
  statement?: string;
  disclaimer?: string;
}

interface RegistrationInstanceRow {
  instanceId?: number;
  instanceName?: string;
  hostId?: number;
  hostName?: string;
  registeredVersionId?: number;
  registeredVersionNo?: string;
  state?: string;
  matchesTarget?: boolean;
}

const coverageDetail = computed<DeployCoverageDetail | undefined>(() =>
  (detail.value?.deploymentCoverage as DeployCoverageDetail | undefined | null) ?? undefined
);

const coverageStatement = computed(() => coverageDetail.value?.statement ?? '');
const coverageNextStep = computed(() => coverageDetail.value?.nextStep ?? '');
const coverageDisclaimer = computed(() => coverageDetail.value?.disclaimer ?? '');

const coverageTargetText = computed(() => {
  const coverage = coverageDetail.value;
  if (!coverage) return '';
  if (coverage.targetVersionNo) return coverage.targetVersionNo;
  if (coverage.targetVersionId) return `#${coverage.targetVersionId}`;
  return '';
});

/** 按事实分组展示实例：未参与执行与批次未成功不能混成“部署失败”。 */
type CoverageTagType = 'success' | 'warning' | 'info' | 'danger';

interface CoverageGroup {
  key: string;
  title: string;
  ids: number[];
  tagType: CoverageTagType;
}

const coverageGroups = computed<CoverageGroup[]>(() => {
  const coverage = coverageDetail.value;
  if (!coverage) return [];
  const groups: CoverageGroup[] = [
    { key: 'deployed', title: t('deployCoverage.coveredTitle'), ids: coverage.deployedInstanceIds ?? [], tagType: 'success' },
    { key: 'uncovered', title: t('deployCoverage.uncoveredTitle'), ids: coverage.uncoveredInstanceIds ?? [], tagType: 'warning' },
    { key: 'unfinished', title: t('deployCoverage.unfinishedTitle'), ids: coverage.unfinishedInstanceIds ?? [], tagType: 'warning' },
    { key: 'removed', title: t('deployCoverage.removedTitle'), ids: coverage.removedInstanceIds ?? [], tagType: 'info' },
    { key: 'rebound', title: t('deployCoverage.reboundTitle'), ids: coverage.reboundInstanceIds ?? [], tagType: 'info' },
  ];
  return groups.filter((group) => group.ids.length > 0);
});

const registrationDetail = computed<RegistrationConsistencyDetail | undefined>(() =>
  (detail.value?.registrationConsistency as RegistrationConsistencyDetail | undefined | null) ?? undefined
);

const registrationStatement = computed(() => registrationDetail.value?.statement ?? '');

const registrationTargetText = computed(() => {
  const consistency = registrationDetail.value;
  if (!consistency) return '';
  if (consistency.targetVersionNo) return consistency.targetVersionNo;
  if (consistency.targetVersionId) return `#${consistency.targetVersionId}`;
  return t('releaseDetail.missingFact');
});

/** 目标版本来源：发布单、回滚单或“没有可确认的来源”；来源不一致单独说明。 */
const registrationSourceText = computed(() => {
  const consistency = registrationDetail.value;
  if (!consistency) return '';
  const orderRef = consistency.sourceOrderNo
    ?? (consistency.sourceOrderId ? `#${consistency.sourceOrderId}` : '');
  switch (consistency.targetSourceType) {
    case 'RELEASE_ORDER':
    case 'CURRENT_VERSION_RELEASE':
      return orderRef
        ? t('registrationConsistency.sourceReleaseOrder', { orderNo: orderRef })
        : t('registrationConsistency.sourceNone');
    case 'CURRENT_VERSION_ROLLBACK':
      return orderRef
        ? t('registrationConsistency.sourceRollbackOrder', { orderNo: orderRef })
        : t('registrationConsistency.sourceNone');
    default:
      return t('registrationConsistency.sourceNone');
  }
});

const registrationSourceMismatch = computed(() => {
  const consistency = registrationDetail.value;
  if (!consistency || consistency.sourceConsistent !== false) return '';
  return consistency.sourceRejectionReason
    ? `${t('registrationConsistency.sourceInconsistent')}: ${consistency.sourceRejectionReason}`
    : t('registrationConsistency.sourceInconsistent');
});

const registrationRows = computed<RegistrationInstanceRow[]>(
  () => registrationDetail.value?.instances ?? [],
);

function registrationStateTagType(state: string | undefined): CoverageTagType {
  switch (state) {
    case 'MATCH': return 'success';
    case 'DIFFERENT': return 'warning';
    case 'UNREGISTERED': return 'danger';
    default: return 'info';
  }
}

function registrationStateLabel(state: string | undefined): string {
  switch (state) {
    case 'MATCH': return t('registrationConsistency.stateMatch');
    case 'DIFFERENT': return t('registrationConsistency.stateDifferent');
    case 'UNREGISTERED': return t('registrationConsistency.stateUnregistered');
    default: return t('registrationConsistency.stateUnknown');
  }
}
const canaryPlan = ref<CanaryPlan | null>(null);
/** 详情读取失败信息：失败时给出说明，不伪装成“没有数据” */
const loadFailure = ref<ReadFailure | null>(null);
/** 灰度信息读取失败信息：与“没有灰度计划”区分 */
const canaryFailure = ref<ReadFailure | null>(null);
/**
 * 读取顺序保护：只有最后一次发起的读取可以写回状态，
 * 迟到的 A 响应不会覆盖 B，也不会污染切换后的对象。
 */
let loadSequence = 0;
// E3.1: 门禁评估历史
const gateEvaluationHistories = ref<Map<number, GateEvaluationHistory[]>>(new Map());
const expandedBatchIds = ref<Set<number>>(new Set());

const drawerTitle = computed(() => props.type === 'release'
  ? t('releaseDetail.titleRelease')
  : t('releaseDetail.titleRollback'));

const isTerminalStatus = computed(() => {
  const status = String(detail.value?.status ?? '');
  return ['SUCCESS', 'FAILED', 'REJECTED', 'ROLLED_BACK'].includes(status);
});

// V1.21: 动作后去向说明
const postActionGuide = computed(() => {
  const status = String(detail.value?.status ?? '');
  if (status !== 'RUNNING' && !props.recentAction) return '';
  if (props.type === 'release') {
    const hasPipeline = Boolean(detail.value?.hasPipelineRun ?? detail.value?.pipelineRunId);
    return hasPipeline
      ? t('releaseDetail.postActionGuideWithPipeline')
      : t('releaseDetail.postActionGuidePlain');
  }
  return t('releaseDetail.postActionGuidePlain');
});

const executionHint = computed(() => {
  const status = String(detail.value?.status ?? '');
  if (status === 'CREATED' || status === 'APPROVED') return t('releaseDetail.pendingExecution');
  if (status === 'RUNNING') return t('releaseDetail.runningExecution');
  return '';
});

const nextStepSuggestion = computed(() => {
  if (!detail.value) return undefined;
  return props.type === 'release'
    ? computeReleaseNextStep(detail.value)
    : computeRollbackNextStep(detail.value);
});

/** 缺失信息统一使用明确占位，不用“-”冒充已读取到的值 */
function factText(value: unknown, placeholder?: string): string {
  const fallback = placeholder ?? t('releaseDetail.missingFact');
  if (value === null || value === undefined) return fallback;
  const text = String(value).trim();
  return text === '' ? fallback : text;
}

/** 单据状态：空值一律按“未返回”处理，不猜测成功/失败 */
const orderStatus = computed(() => String(detail.value?.status ?? '').trim());

interface FailureReasonItem {
  source: string;
  text: string;
}

/** 失败原因按来源分条列出，不把单据状态、审批与部署记录混为一谈 */
const failureReasons = computed<FailureReasonItem[]>(() => {
  const items: FailureReasonItem[] = [];
  const detailHint = factText(detail.value?.latestFailureHint, '');
  if (detailHint) {
    items.push({ source: t('releaseDetail.failureSourceLatestDeploy'), text: detailHint });
  }
  const plan = canaryPlan.value;
  if (plan?.failureReason) {
    items.push({ source: t('releaseDetail.failureSourceCanaryPlan'), text: plan.failureReason });
  }
  plan?.batches?.forEach((batch) => {
    if (batch.failureReason) {
      items.push({
        source: t('releaseDetail.failureSourceCanaryBatch', { batchNo: batch.batchNo }),
        text: batch.failureReason,
      });
    }
  });
  return items;
});

const orderStatusFailedWithoutHint = computed(() =>
  orderStatus.value === 'FAILED' && failureReasons.value.length === 0
);

const hasFailureInfo = computed(() =>
  failureReasons.value.length > 0 || orderStatusFailedWithoutHint.value
);

/** 时间按当前控制台语言区域格式化，不写死某一语言 */
function formatDateTime(date: string | OffsetDateTime | null | undefined): string {
  if (!date) return '-';
  const str = String(date);
  try {
    const parsed = new Date(str);
    if (Number.isNaN(parsed.getTime())) return str.replace('T', ' ').substring(0, 19);
    return parsed.toLocaleString(locale.value, {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return str.replace('T', ' ').substring(0, 19);
  }
}

/** 取出后端/异常给出的原因原文；取不到时返回空串，由调用方决定兜底文案 */
function describeReadError(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error.trim()) return error;
  const response = (error as { response?: { data?: { message?: unknown } } } | null)?.response;
  const message = response?.data?.message;
  if (typeof message === 'string' && message.trim()) return message;
  return '';
}

/** 灰度计划属于辅助信息：读取失败要单独记录，绝不折叠成“没有灰度计划” */
async function loadCanary(id: number, requestId: number) {
  try {
    const plan = await getCanaryPlan(id);
    if (requestId !== loadSequence) return;
    canaryPlan.value = plan;
    canaryFailure.value = null;
  } catch (error) {
    if (requestId !== loadSequence) return;
    canaryPlan.value = null;
    canaryFailure.value = { reason: describeReadError(error) || null };
  }
}

/** 只读重试：只重新读取灰度计划，不触发执行、恢复或重试等写操作 */
async function reloadCanary() {
  const id = props.id;
  if (!id || props.type !== 'release') return;
  canaryFailure.value = null;
  await loadCanary(id, loadSequence);
}

async function loadDetail() {
  const id = props.id;
  if (!id) return;
  const requestId = ++loadSequence;
  loading.value = true;
  loadFailure.value = null;
  try {
    const data = props.type === 'release'
      ? await getReleaseDetail(id)
      : await getRollbackDetail(id);
    // 迟到响应直接丢弃，避免 A 覆盖 B
    if (requestId !== loadSequence) return;
    detail.value = data;
    canaryPlan.value = null;
    canaryFailure.value = null;
    if (props.type === 'release') {
      // E1: 加载灰度计划（失败只影响灰度区块）
      await loadCanary(id, requestId);
    }
  } catch (error) {
    if (requestId !== loadSequence) return;
    detail.value = null;
    canaryPlan.value = null;
    canaryFailure.value = null;
    loadFailure.value = { reason: describeReadError(error) || null };
  } finally {
    if (requestId === loadSequence) loading.value = false;
  }
}

// V1.21: Expose loadDetail for parent to refresh after action
defineExpose({ loadDetail });

/**
 * 打开抽屉、切换单据、切换单据类型统一走这里：
 * 1) 先作废所有在途读取，保证迟到响应不会覆盖当前对象；
 * 2) 切换对象时立即清空上一对象的详情与辅助信息，避免串详情；
 * 3) 只在抽屉可见且有单据 ID 时发起读取。
 */
watch(
  () => [props.modelValue, props.id, props.type] as const,
  ([isVisible, id, type], previous) => {
    const identityChanged = !previous || previous[1] !== id || previous[2] !== type;
    loadSequence += 1;
    if (identityChanged) {
      detail.value = null;
      canaryPlan.value = null;
      canaryFailure.value = null;
      loadFailure.value = null;
      gateEvaluationHistories.value = new Map();
      expandedBatchIds.value = new Set();
    }
    if (!isVisible) {
      loading.value = false;
      return;
    }
    if (id) {
      loadDetail();
    }
  }
);

function handleClose() {
  visible.value = false;
  detail.value = null;
  canaryPlan.value = null;
  canaryFailure.value = null;
  loadFailure.value = null;
  loading.value = false;
  loadSequence += 1;
}

function openPrecheck() {
  emit('open-precheck');
}

function openDeployRecords() {
  emit('open-deploy-records');
}

// T-C: Open config check (Contract 8.1)
function openConfigCheck() {
  emit('check-deploy-config');
}

// V-B: Cross-navigation helpers (Contract 8)
const hasAppAndEnv = computed(() => {
  return Boolean(detail.value?.appId && detail.value?.envId);
});

// V-B: navigateToRuntimeInstance (Contract 7)
function navigateToRuntimeInstance() {
  if (!detail.value) return;
  const query: Record<string, string> = {};
  if (detail.value.appId) query.appId = String(detail.value.appId);
  if (detail.value.envId) query.envId = String(detail.value.envId);
  if (detail.value.serviceId) query.serviceId = String(detail.value.serviceId);
  router.push({ path: '/resource/runtime-instances', query });
}

// V-B: navigateToCurrentVersion (Contract 7)
function navigateToCurrentVersion() {
  if (!detail.value) return;
  const query: Record<string, string> = {};
  if (detail.value.appId) query.appId = String(detail.value.appId);
  if (detail.value.envId) query.envId = String(detail.value.envId);
  router.push({ path: '/release/current-versions', query });
}

function openPipelineRun() {
  const pipelineRunId = Number(detail.value?.pipelineRunId);
  if (pipelineRunId) {
    router.push(`/pipeline/runs/${pipelineRunId}/steps`);
  }
}

function openHistoryRecord(item: Record<string, unknown>) {
  const recordId = Number(item.deployRecordId);
  if (recordId) {
    emit('open-deploy-records-with-id', recordId);
  }
}

function getHistoryStatusType(status: string | null | undefined): string {
  const value = String(status ?? '');
  if (value === 'SUCCESS') return 'success';
  if (value === 'FAILED') return 'danger';
  if (value === 'RUNNING') return 'warning';
  return 'info';
}

function getHistoryStatusLabel(status: string | null | undefined): string {
  const value = String(status ?? '');
  const labels: Record<string, string> = {
    RUNNING: t('releaseDetail.statusRunning'),
    SUCCESS: t('releaseDetail.statusSuccess'),
    FAILED: t('releaseDetail.statusFailed'),
  };
  return labels[value] || value || '-';
}

function getTimelineNodeType(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    case 'RUNNING': return 'warning';
    default: return 'info';
  }
}

function getTimelineStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: t('releaseDetail.statusPending'),
    RUNNING: t('releaseDetail.statusRunning'),
    SUCCESS: t('releaseDetail.statusSuccess'),
    FAILED: t('releaseDetail.statusFailed'),
  };
  return labels[status] || status;
}

function getTimelineActionLabel(actionType: string): string {
  const labels: Record<string, string> = {
    PRECHECK: t('releaseDetail.timelineActionPrecheck'),
    DEPLOY_RECORDS: t('releaseDetail.timelineActionDeployRecords'),
    PIPELINE_RUN: t('releaseDetail.timelineActionPipelineRun'),
  };
  return labels[actionType] || '';
}

function handleTimelineAction(node: Record<string, unknown>) {
  const actionType = String(node.actionType ?? '');
  if (actionType === 'PRECHECK') {
    openPrecheck();
  } else if (actionType === 'DEPLOY_RECORDS') {
    openDeployRecords();
  } else if (actionType === 'PIPELINE_RUN') {
    openPipelineRun();
  }
}

// E1: 灰度状态辅助函数
function getCanaryStatusTagType(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'FAILED': return 'danger';
    case 'RUNNING': return 'warning';
    case 'CREATED': return 'info';
    case 'PAUSED': return 'warning';
    case 'ROLLED_BACK': return 'info';
    // 蓝色统一用合法的 Element Plus 类型 'primary'（渲染与默认蓝一致，同时消除非法的空 type 警告）
    case 'WAITING_NEXT': return 'primary';
    case 'BLOCKED': return 'danger';
    default: return 'info';
  }
}

function getCanaryStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    CREATED: t('releaseDetail.canaryStatusCreated'),
    RUNNING: t('releaseDetail.canaryStatusRunning'),
    PAUSED: t('releaseDetail.canaryStatusPaused'),
    SUCCESS: t('releaseDetail.canaryStatusSuccess'),
    FAILED: t('releaseDetail.canaryStatusFailed'),
    ROLLED_BACK: t('releaseDetail.canaryStatusRolledBack'),
    WAITING_NEXT: t('releaseDetail.canaryStatusWaitingNext'),
    PENDING: t('releaseDetail.canaryStatusPending'),
    BLOCKED: t('releaseDetail.canaryStatusBlocked'),
  };
  return labels[status] || status;
}

function getCanaryBatchProgress(plan: CanaryPlan): string {
  if (!plan.batches || plan.batches.length === 0) return '-';
  const completed = plan.batches.filter(b => b.status === 'SUCCESS').length;
  const running = plan.batches.filter(b => b.status === 'RUNNING').length;
  const pending = plan.batches.filter(b => b.status === 'PENDING').length;
  // 批次状态 FAILED 只表示“没有成功收口”；结果未知/部分成功的批次不能算进“确定失败”
  const unconfirmed = plan.batches.filter(
    b => b.status === 'FAILED' && (b.executionOutcome === 'UNKNOWN' || b.executionOutcome === 'PARTIAL'),
  ).length;
  const failed = plan.batches.filter(b => b.status === 'FAILED').length - unconfirmed;

  let progress = t('releaseDetail.batchProgressBase', { completed, total: plan.batchCount });
  if (running > 0) progress += `, ${t('releaseDetail.batchProgressRunning', { count: running })}`;
  if (pending > 0) progress += `, ${t('releaseDetail.batchProgressPending', { count: pending })}`;
  if (failed > 0) progress += `, ${t('releaseDetail.batchProgressFailed', { count: failed })}`;
  if (unconfirmed > 0) progress += `, ${t('releaseDetail.batchProgressUnknown', { count: unconfirmed })}`;
  return progress;
}

// E2: 质量门禁状态辅助函数
function getGateStatusTagType(status: string): string {
  switch (status) {
    case 'PASSED': return 'success';
    case 'FAILED': return 'danger';
    case 'SKIPPED': return 'info';
    default: return 'info';
  }
}

function getGateStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    NOT_EVALUATED: t('releaseDetail.gateStatusNotEvaluated'),
    PASSED: t('releaseDetail.gateStatusPassed'),
    FAILED: t('releaseDetail.gateStatusFailed'),
    SKIPPED: t('releaseDetail.gateStatusSkipped'),
  };
  return labels[status] || status;
}

// 执行结果类别由服务端结构化输出；UNKNOWN / PARTIAL 用警示色（不是红），因为它们不是“确定失败”
const EXECUTION_OUTCOME_KEYS: Record<string, string> = {
  NOT_STARTED: 'releaseDetail.executionOutcomeNotStarted',
  SUCCEEDED: 'releaseDetail.executionOutcomeSucceeded',
  FAILED: 'releaseDetail.executionOutcomeFailed',
  PARTIAL: 'releaseDetail.executionOutcomePartial',
  UNKNOWN: 'releaseDetail.executionOutcomeUnknown',
};

function getExecutionOutcomeTagType(outcome: string | null | undefined): string {
  switch (outcome) {
    case 'SUCCEEDED': return 'success';
    case 'FAILED': return 'danger';
    case 'NOT_STARTED': return 'info';
    default: return 'warning';
  }
}

function getExecutionOutcomeLabel(outcome: string | null | undefined): string {
  if (!outcome) return '';
  const key = EXECUTION_OUTCOME_KEYS[outcome];
  // 认不出的取值保留原值并显式标注，不折算成任何确定状态
  return key ? t(key) : t('deployStatus.unknownRaw', { status: outcome });
}

// F-D: 执行器类型辅助函数
function getExecutorTypeTagType(type: string | undefined): string {
  switch (type) {
    case 'SSH': return 'info';
    case 'DOCKER': return 'success';
    case 'KUBERNETES': return 'warning';
    default: return 'info';
  }
}

function getExecutorTypeLabel(type: string | undefined): string {
  const labels: Record<string, string> = {
    SSH: 'SSH',
    DOCKER: 'Docker',
    KUBERNETES: 'Kubernetes',
  };
  return labels[type || 'SSH'] || 'SSH';
}

// E2: 重评估门禁
async function handleReEvaluateGate(batch: CanaryBatch) {
  if (!props.id || !batch.id) return;
  try {
    await evaluateBatchGate(props.id, batch.id);
    // 刷新详情
    await loadDetail();
  } catch (e) {
    // 错误由全局处理
  }
}

// E3.1: 加载门禁评估历史
async function loadGateEvaluationHistory(batch: CanaryBatch) {
  if (!props.id || !batch.id) return;
  try {
    const history = await getBatchGateEvaluations(props.id, batch.id);
    gateEvaluationHistories.value.set(batch.id, history);
  } catch {
    gateEvaluationHistories.value.set(batch.id, []);
  }
}

// E3.1: 展开/收起历史
async function toggleBatchHistory(batch: CanaryBatch) {
  if (!batch.id) return;
  if (expandedBatchIds.value.has(batch.id)) {
    expandedBatchIds.value.delete(batch.id);
  } else {
    expandedBatchIds.value.add(batch.id);
    await loadGateEvaluationHistory(batch);
  }
}

function openSnapshotFromHistory(snapshotId?: number) {
  if (!snapshotId) return;
  router.push({
    path: '/monitor/snapshots',
    query: { snapshotId: String(snapshotId) },
  });
}

function openAlertEventsFromHistory(eventIds?: number[]) {
  if (!eventIds || eventIds.length === 0) return;
  const eventId = eventIds[0];
  router.push({
    path: '/monitor/alert-events',
    query: { eventId: String(eventId) },
  });
  if (eventIds.length > 1) {
    ElMessage.info(t('releaseDetail.alertEventRedirect', { id: eventId, count: eventIds.length - 1 }));
  }
}
</script>

<style scoped lang="scss">
.loading-container {
  padding: var(--ih-spacing-xl);
}

.detail-content {
  padding: 0 var(--ih-section-gap);
}

// 分组标题：让“对象与目标环境 / 版本变化 / 执行状态 / 失败原因 / 下一步入口 / 历史证据”在长抽屉里可扫读
.group-title {
  margin: var(--space-lg) 0 var(--space-xs);
  padding: 0;
  border-radius: 0;
  background: none;
  color: var(--color-muted);
  font-size: var(--text-xs);
  font-weight: 500;
  letter-spacing: 0;
}

// 读取失败：说明原因并提供只读重试
.state-block {
  display: flex;
  align-items: flex-start;
  gap: var(--ih-spacing-sm);
  margin: var(--ih-spacing-xl) var(--ih-section-gap);
  padding: var(--ih-spacing-sm);
  border: 1px solid var(--ih-danger);
  border-radius: var(--ih-radius-md);
  background-color: var(--ih-danger-bg);
}

.state-icon {
  color: var(--ih-danger);
  margin-top: 2px;
}

.state-main {
  flex: 1;
  min-width: 0;
}

.state-title {
  font-weight: 500;
  color: var(--ih-text-primary);
}

.state-text {
  font-size: 12px;
  margin-top: 2px;
  word-break: break-word;
  color: var(--ih-text-primary);
}

.state-hint {
  font-size: 12px;
  margin-top: 2px;
  color: var(--ih-text-secondary);
}

// 缺失信息的明确占位
.missing-text {
  color: var(--ih-text-secondary);
  font-style: italic;
}

.fact-note {
  display: block;
  font-size: 12px;
  color: var(--ih-text-secondary);
}

// 覆盖范围与登记一致性：结论标签与来源说明并排，逐实例证据按事实分组
.coverage-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--ih-spacing-3);
  margin-bottom: var(--ih-spacing-1);
}

.coverage-group {
  margin-top: var(--ih-spacing-2);
}

.coverage-group-title {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-bottom: var(--ih-spacing-1);
}

.coverage-id-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ih-spacing-1);
}

.registration-table {
  margin: var(--ih-spacing-2) 0;
}

// 辅助信息（灰度）读取失败：与“没有该信息”区分
.aux-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ih-spacing-3);
  padding: var(--ih-spacing-sm);
  border: 1px solid var(--ih-warning);
  border-radius: var(--ih-radius-md);
  background-color: var(--ih-warning-bg);
}

.aux-error-text {
  font-size: 13px;
  color: var(--ih-text-primary);
  word-break: break-word;
}

.aux-error-hint {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.failure-item {
  margin-bottom: var(--ih-spacing-3);
}

.failure-missing {
  font-size: 13px;
  color: var(--ih-text-secondary);
  padding: var(--ih-spacing-3) 0;
}

.detail-section {
  margin-bottom: var(--ih-spacing-xl);

  .section-title {
    font-weight: 500;
    font-size: 14px;
    color: var(--ih-text-primary);
    margin-bottom: var(--ih-spacing-sm);
    padding-bottom: var(--ih-spacing-3);
    border-bottom: 1px solid var(--ih-border-light);
  }
}

.action-buttons {
  display: flex;
  gap: var(--ih-spacing-3);
  flex-wrap: wrap;
}

.failure-hint-text {
  font-size: 13px;
}

.status-hint {
  color: var(--ih-text-secondary);
  font-size: 13px;
}

.post-action-guide {
  color: var(--ih-primary);
  font-size: 13px;
}

.timeline-node {
  .timeline-node-header {
    display: flex;
    align-items: center;
    gap: var(--ih-spacing-3);
    margin-bottom: var(--ih-spacing-1);
  }

  .timeline-node-title {
    font-weight: 500;
    color: var(--ih-text-primary);
  }

  .timeline-node-summary {
    font-size: 13px;
    color: var(--ih-text-secondary);
    margin-bottom: var(--ih-spacing-1);
  }
}

.execution-history-list {
  display: flex;
  flex-direction: column;
  gap: var(--ih-spacing-3);
}

.history-item {
  padding: var(--ih-spacing-3) var(--ih-spacing-sm);
  border: 1px solid var(--ih-border-light);
  border-radius: var(--ih-radius-md);
  cursor: pointer;
  transition: background-color 0.2s;

  &:hover {
    background-color: var(--ih-bg-row-hover);
  }
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ih-spacing-1);
}

.history-index {
  font-weight: 500;
  font-size: 13px;
  color: var(--ih-text-primary);
}

.history-meta {
  display: flex;
  gap: var(--ih-spacing-sm);
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.history-summary {
  font-size: 13px;
  color: var(--ih-text-secondary);
  margin-top: var(--ih-spacing-1);
}

.history-failure {
  font-size: 13px;
  color: var(--ih-danger);
  margin-top: var(--ih-spacing-1);
}

// E1: 灰度批次样式
.canary-batches {
  margin-top: var(--ih-spacing-sm);
  display: flex;
  flex-direction: column;
  gap: var(--ih-spacing-3);
}

.batch-item {
  padding: var(--space-sm);
  border: var(--rule-hair) solid var(--color-rule);
  border-radius: var(--radius-panel);
  background-color: transparent;

  &.is-current {
    border-color: var(--ih-warning);
    background-color: var(--ih-warning-bg);
  }
}

.batch-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ih-spacing-1);
}

.batch-no {
  font-weight: 500;
  font-size: 13px;
  color: var(--ih-text-primary);
}

.batch-meta {
  display: flex;
  gap: var(--ih-spacing-sm);
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.batch-instances {
  color: var(--ih-primary);
}

.batch-time {
  color: var(--ih-text-secondary);
}

.batch-failure {
  font-size: 13px;
  color: var(--ih-danger);
  margin-top: var(--ih-spacing-1);
}

// E2: 质量门禁样式
.batch-gate {
  margin-top: var(--ih-spacing-2);
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-2);
  font-size: 12px;
}

.gate-label {
  color: var(--ih-text-secondary);
}

.gate-reason {
  color: var(--ih-text-secondary);
  font-size: 12px;
  margin-left: var(--ih-spacing-1);
}

// E3.1: 门禁评估历史样式
.gate-history {
  margin-top: var(--ih-spacing-3);
  padding: var(--ih-spacing-3);
  background-color: transparent;
  border-radius: var(--ih-radius-md);
}

.no-history {
  font-size: 12px;
  color: var(--ih-text-secondary);
  text-align: center;
  padding: var(--ih-spacing-3);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--ih-spacing-3);

  .history-item {
    padding: var(--ih-spacing-3);
    background-color: transparent;
    border-radius: var(--ih-spacing-1);
    border: 1px solid var(--ih-border-light);
    cursor: default;
  }

  .history-header {
    margin-bottom: var(--ih-spacing-1);
  }

  .history-meta {
    justify-content: space-between;
    font-size: var(--ih-font-size-xs);
    gap: var(--ih-spacing-sm);
  }
}

.history-type {
  font-weight: 500;
  font-size: 12px;
  color: var(--ih-text-primary);
}

.history-reason {
  font-size: 12px;
  color: var(--ih-text-secondary);
  margin-top: var(--ih-spacing-1);
}

.history-links {
  margin-top: var(--ih-spacing-1);
  display: flex;
  align-items: center;
  gap: var(--ih-spacing-3);
}

.alert-count {
  font-size: var(--ih-font-size-xs);
  color: var(--ih-warning);
}

.failure-reason-text {
  font-size: 13px;
  color: var(--ih-danger);
}
</style>
