<template>
  <div class="notification-followup-review-tab">
    <!-- Page Title with Read-Only Notice -->
    <div class="page-title-section">
      <h3 class="section-main-title">{{ t('resourceGovernance.followupReview.title') }}</h3>
      <el-tag type="info" size="small">{{ t('resourceGovernance.followupReview.readOnlyNotice') }}</el-tag>
    </div>

    <!-- Overview Metrics Card -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.followupReview.overview.title') }}</span>
      <el-date-picker
        v-model="timeRange"
        type="datetimerange"
        range-separator="-"
        :start-placeholder="t('resourceGovernance.followupReview.startTime')"
        :end-placeholder="t('resourceGovernance.followupReview.endTime')"
        value-format="YYYY-MM-DDTHH:mm:ss"
        size="small"
        style="width: 360px"
        @change="loadOverview"
      />
    </div>
    <div v-if="overviewLoading" class="section-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert v-else-if="overviewError" :title="overviewError" type="error" :closable="false" show-icon />
    <div v-else-if="overview" class="overview-grid">
      <div class="overview-card">
        <div class="overview-value">{{ overview.totalNotifications }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.totalNotifications') }}</div>
      </div>
      <div class="overview-card overview-card-success">
        <div class="overview-value">{{ overview.followedUpNotifications }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.followedUpNotifications') }}</div>
      </div>
      <div class="overview-card overview-card-warning">
        <div class="overview-value">{{ overview.pendingFollowupNotifications }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.pendingFollowupNotifications') }}</div>
      </div>
      <div class="overview-card">
        <div class="overview-value">{{ overview.totalFollowupRecords }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.totalFollowupRecords') }}</div>
      </div>
      <div class="overview-card overview-card-success">
        <div class="overview-value">{{ overview.completedFollowupRecords }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.completedFollowupRecords') }}</div>
      </div>
      <div class="overview-card overview-card-running">
        <div class="overview-value">{{ overview.inProgressFollowupRecords }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.inProgressFollowupRecords') }}</div>
      </div>
      <div class="overview-card overview-card-info">
        <div class="overview-value">{{ overview.cancelledFollowupRecords }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.cancelledFollowupRecords') }}</div>
      </div>
      <div class="overview-card overview-card-warning">
        <div class="overview-value">{{ overview.pendingReceiptCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.pendingReceiptCount') }}</div>
      </div>
      <div class="overview-card overview-card-success">
        <div class="overview-value">{{ overview.confirmedReceiptCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.confirmedReceiptCount') }}</div>
      </div>
      <div class="overview-card overview-card-danger">
        <div class="overview-value">{{ overview.failedReceiptCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.failedReceiptCount') }}</div>
      </div>
      <div class="overview-card overview-card-info">
        <div class="overview-value">{{ overview.noResponseCount }}</div>
        <div class="overview-label">{{ t('resourceGovernance.followupReview.overview.noResponseCount') }}</div>
      </div>
    </div>

    <!-- Rate Metrics -->
    <div v-if="overview" class="rate-metrics-grid">
      <div class="rate-card">
        <div class="rate-header">{{ t('resourceGovernance.followupReview.overview.followupCompletionRate') }}</div>
        <el-progress :percentage="overview.followupCompletionRate || 0" :stroke-width="12" :format="(p: number) => p.toFixed(1) + '%'" />
      </div>
      <div class="rate-card">
        <div class="rate-header">{{ t('resourceGovernance.followupReview.overview.receiptConfirmationRate') }}</div>
        <el-progress :percentage="overview.receiptConfirmationRate || 0" :stroke-width="12" :format="(p: number) => p.toFixed(1) + '%'" />
      </div>
      <div class="rate-card">
        <div class="rate-header">{{ t('resourceGovernance.followupReview.overview.averageFirstFollowupMinutes') }}</div>
        <div class="rate-value">{{ (overview.averageFirstFollowupMinutes || 0).toFixed(1) }} {{ t('resourceGovernance.followupReview.minutes') }}</div>
      </div>
      <div class="rate-card rate-card-danger">
        <div class="rate-header">{{ t('resourceGovernance.followupReview.overview.overdueFollowupCount') }}</div>
        <div class="rate-value overdue-value">{{ overview.overdueFollowupCount || 0 }}</div>
      </div>
    </div>

    <!-- Receipt Stats Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.followupReview.receiptStats.title') }}</span>
    </div>
    <div v-if="receiptStatsLoading" class="section-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert v-else-if="receiptStatsError" :title="receiptStatsError" type="error" :closable="false" show-icon />
    <template v-else-if="receiptStats">
      <!-- By Channel Type -->
      <div class="stats-subsection">
        <div class="stats-subsection-title">{{ t('resourceGovernance.followupReview.receiptStats.byChannelType') }}</div>
        <el-table :data="receiptStats.byChannelType" stripe size="small">
          <el-table-column prop="channelType" :label="t('resourceGovernance.followupReview.receiptStats.channelType')" min-width="150">
            <template #default="{ row }">
              <el-tag size="small">{{ formatChannelType(row.channelType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalCount" :label="t('resourceGovernance.followupReview.receiptStats.totalCount')" width="100" align="right" />
          <el-table-column prop="confirmedCount" :label="t('resourceGovernance.followupReview.receiptStats.confirmedCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="success-text">{{ row.confirmedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="failedCount" :label="t('resourceGovernance.followupReview.receiptStats.failedCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="danger-text">{{ row.failedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="noResponseCount" :label="t('resourceGovernance.followupReview.receiptStats.noResponseCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="info-text">{{ row.noResponseCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="confirmationRate" :label="t('resourceGovernance.followupReview.receiptStats.confirmationRate')" min-width="160">
            <template #default="{ row }">
              <el-progress :percentage="row.confirmationRate || 0" :stroke-width="8" :show-text="false" />
              <span class="progress-label">{{ (row.confirmationRate || 0).toFixed(1) }}%</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- By Target User (Top 10) -->
      <div class="stats-subsection">
        <div class="stats-subsection-title">{{ t('resourceGovernance.followupReview.receiptStats.byTargetUser') }}</div>
        <el-table :data="receiptStats.byTargetUser" stripe size="small">
          <el-table-column prop="targetUserName" :label="t('resourceGovernance.followupReview.receiptStats.targetUserName')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="totalCount" :label="t('resourceGovernance.followupReview.receiptStats.totalCount')" width="100" align="right" />
          <el-table-column prop="confirmedCount" :label="t('resourceGovernance.followupReview.receiptStats.confirmedCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="success-text">{{ row.confirmedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="failedCount" :label="t('resourceGovernance.followupReview.receiptStats.failedCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="danger-text">{{ row.failedCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="noResponseCount" :label="t('resourceGovernance.followupReview.receiptStats.noResponseCount')" width="100" align="right">
            <template #default="{ row }">
              <span class="info-text">{{ row.noResponseCount }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="confirmationRate" :label="t('resourceGovernance.followupReview.receiptStats.confirmationRate')" min-width="160">
            <template #default="{ row }">
              <el-progress :percentage="row.confirmationRate || 0" :stroke-width="8" :show-text="false" />
              <span class="progress-label">{{ (row.confirmationRate || 0).toFixed(1) }}%</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- Overall -->
      <div v-if="receiptStats.overall" class="stats-subsection">
        <div class="stats-subsection-title">{{ t('resourceGovernance.followupReview.receiptStats.overall') }}</div>
        <div class="overall-stats-grid">
          <div class="overall-stat-item">
            <span class="overall-label">{{ t('resourceGovernance.followupReview.receiptStats.totalCount') }}</span>
            <span class="overall-value">{{ receiptStats.overall.totalCount }}</span>
          </div>
          <div class="overall-stat-item overall-success">
            <span class="overall-label">{{ t('resourceGovernance.followupReview.receiptStats.confirmedCount') }}</span>
            <span class="overall-value">{{ receiptStats.overall.confirmedCount }}</span>
          </div>
          <div class="overall-stat-item overall-danger">
            <span class="overall-label">{{ t('resourceGovernance.followupReview.receiptStats.failedCount') }}</span>
            <span class="overall-value">{{ receiptStats.overall.failedCount }}</span>
          </div>
          <div class="overall-stat-item overall-info">
            <span class="overall-label">{{ t('resourceGovernance.followupReview.receiptStats.noResponseCount') }}</span>
            <span class="overall-value">{{ receiptStats.overall.noResponseCount }}</span>
          </div>
          <div class="overall-stat-item">
            <span class="overall-label">{{ t('resourceGovernance.followupReview.receiptStats.confirmationRate') }}</span>
            <span class="overall-value">{{ (receiptStats.overall.confirmationRate || 0).toFixed(1) }}%</span>
          </div>
        </div>
      </div>
    </template>

    <!-- Followup Status Distribution Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.followupReview.statusDistribution.title') }}</span>
    </div>
    <div v-if="distributionLoading" class="section-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-alert v-else-if="distributionError" :title="distributionError" type="error" :closable="false" show-icon />
    <template v-else-if="distribution">
      <div class="distribution-grid">
        <!-- By FollowUp Status -->
        <div class="distribution-card">
          <div class="distribution-title">{{ t('resourceGovernance.followupReview.statusDistribution.byFollowUpStatus') }}</div>
          <div class="distribution-items">
            <div v-for="item in distribution.byFollowUpStatus" :key="item.followUpStatus" class="distribution-item">
              <el-tag :type="getFollowUpStatusTagType(item.followUpStatus)" size="small">
                {{ t(`resourceGovernance.followup.statuses.${item.followUpStatus}`) }}
              </el-tag>
              <span class="distribution-count">{{ item.count }}</span>
              <span class="distribution-percentage">({{ (item.percentage || 0).toFixed(1) }}%)</span>
            </div>
          </div>
        </div>
        <!-- By Receipt Status -->
        <div class="distribution-card">
          <div class="distribution-title">{{ t('resourceGovernance.followupReview.statusDistribution.byReceiptStatus') }}</div>
          <div class="distribution-items">
            <div v-for="item in distribution.byReceiptStatus" :key="item.receiptStatus" class="distribution-item">
              <el-tag :type="getReceiptStatusTagType(item.receiptStatus)" size="small">
                {{ t(`resourceGovernance.followup.receiptStatuses.${item.receiptStatus}`) }}
              </el-tag>
              <span class="distribution-count">{{ item.count }}</span>
              <span class="distribution-percentage">({{ (item.percentage || 0).toFixed(1) }}%)</span>
            </div>
          </div>
        </div>
        <!-- By FollowUp Type -->
        <div class="distribution-card">
          <div class="distribution-title">{{ t('resourceGovernance.followupReview.statusDistribution.byFollowUpType') }}</div>
          <div class="distribution-items">
            <div v-for="item in distribution.byFollowUpType" :key="item.followUpType" class="distribution-item">
              <el-tag size="small">{{ t(`resourceGovernance.followup.types.${item.followUpType}`) }}</el-tag>
              <span class="distribution-count">{{ item.count }}</span>
              <span class="distribution-percentage">({{ (item.percentage || 0).toFixed(1) }}%)</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Analysis Tabs -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.followupReview.analysis.title') }}</span>
    </div>
    <el-tabs v-model="activeAnalysisTab" class="analysis-tabs">
      <!-- By Handler Tab -->
      <el-tab-pane :label="t('resourceGovernance.followupReview.analysis.byHandler')" name="byHandler">
        <div class="tab-filter-bar">
          <el-button size="small" @click="loadByHandler">{{ t('common.refresh') }}</el-button>
        </div>
        <div v-if="byHandlerLoading" class="section-loading">
          <el-skeleton :rows="5" animated />
        </div>
        <el-alert v-else-if="byHandlerError" :title="byHandlerError" type="error" :closable="false" show-icon />
        <template v-else>
          <el-table :data="byHandlerRecords" stripe size="small">
            <el-table-column prop="handlerName" :label="t('resourceGovernance.followupReview.analysis.handlerName')" min-width="160" show-overflow-tooltip />
            <el-table-column prop="totalFollowupCount" :label="t('resourceGovernance.followupReview.analysis.totalFollowupCount')" width="100" align="right" />
            <el-table-column prop="completedCount" :label="t('resourceGovernance.followupReview.analysis.completedCount')" width="100" align="right">
              <template #default="{ row }">
                <span class="success-text">{{ row.completedCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="inProgressCount" :label="t('resourceGovernance.followupReview.analysis.inProgressCount')" width="100" align="right">
              <template #default="{ row }">
                <span class="warning-text">{{ row.inProgressCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="cancelledCount" :label="t('resourceGovernance.followupReview.analysis.cancelledCount')" width="100" align="right">
              <template #default="{ row }">
                <span class="info-text">{{ row.cancelledCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="completionRate" :label="t('resourceGovernance.followupReview.analysis.completionRate')" min-width="160">
              <template #default="{ row }">
                <el-progress :percentage="row.completionRate || 0" :stroke-width="8" :show-text="false" />
                <span class="progress-label">{{ (row.completionRate || 0).toFixed(1) }}%</span>
              </template>
            </el-table-column>
            <el-table-column prop="averageFollowupMinutes" :label="t('resourceGovernance.followupReview.analysis.averageFollowupMinutes')" width="120" align="right">
              <template #default="{ row }">
                {{ (row.averageFollowupMinutes || 0).toFixed(1) }} {{ t('resourceGovernance.followupReview.minutes') }}
              </template>
            </el-table-column>
          </el-table>
          <div v-if="byHandlerTotal > 0" class="pagination-wrapper">
            <el-pagination
              v-model:current-page="byHandlerPageNum"
              v-model:page-size="byHandlerPageSize"
              :total="byHandlerTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              size="small"
              @size-change="loadByHandler"
              @current-change="loadByHandler"
            />
          </div>
        </template>
      </el-tab-pane>

      <!-- By Alert Tab -->
      <el-tab-pane :label="t('resourceGovernance.followupReview.analysis.byAlert')" name="byAlert">
        <div class="tab-filter-bar">
          <el-select v-model="byAlertFilter.alertLevel" :placeholder="t('resourceGovernance.followupReview.filter.alertLevel')" clearable size="small" style="width: 120px" @change="loadByAlert">
            <el-option :label="t('resourceGovernance.riskLevel.CRITICAL')" value="CRITICAL" />
            <el-option :label="t('resourceGovernance.riskLevel.WARNING')" value="WARNING" />
            <el-option :label="t('resourceGovernance.riskLevel.NORMAL')" value="INFO" />
          </el-select>
          <el-select v-model="byAlertFilter.alertStatus" :placeholder="t('resourceGovernance.followupReview.filter.alertStatus')" clearable size="small" style="width: 120px" @change="loadByAlert">
            <el-option :label="t('resourceGovernance.alertRecords.alertStatuses.OPEN')" value="OPEN" />
            <el-option :label="t('resourceGovernance.alertRecords.alertStatuses.ACKED')" value="ACKED" />
            <el-option :label="t('resourceGovernance.alertRecords.alertStatuses.RESOLVED')" value="RESOLVED" />
          </el-select>
          <el-button size="small" @click="loadByAlert">{{ t('common.refresh') }}</el-button>
        </div>
        <div v-if="byAlertLoading" class="section-loading">
          <el-skeleton :rows="5" animated />
        </div>
        <el-alert v-else-if="byAlertError" :title="byAlertError" type="error" :closable="false" show-icon />
        <template v-else>
          <el-table :data="byAlertRecords" stripe size="small">
            <el-table-column prop="alertNo" :label="t('resourceGovernance.followupReview.analysis.alertNo')" width="140">
              <template #default="{ row }">
                <el-button type="primary" link size="small">{{ row.alertNo }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="alertTitle" :label="t('resourceGovernance.followupReview.analysis.alertTitle')" min-width="180" show-overflow-tooltip />
            <el-table-column prop="alertLevel" :label="t('resourceGovernance.followupReview.analysis.alertLevel')" width="110">
              <template #default="{ row }">
                <el-tag :type="getAlertLevelTagType(row.alertLevel)" size="small">{{ t(`resourceGovernance.riskLevel.${row.alertLevel}`) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="alertStatus" :label="t('resourceGovernance.followupReview.analysis.alertStatus')" width="140">
              <template #default="{ row }">
                <el-tag :type="getAlertStatusTagType(row.alertStatus)" size="small">{{ t(`resourceGovernance.alertRecords.alertStatuses.${row.alertStatus}`) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="notificationCount" :label="t('resourceGovernance.followupReview.analysis.notificationCount')" width="100" align="right" />
            <el-table-column prop="followupCount" :label="t('resourceGovernance.followupReview.analysis.followupCount')" width="100" align="right" />
            <el-table-column prop="closedLoopRate" :label="t('resourceGovernance.followupReview.analysis.closedLoopRate')" width="140">
              <template #default="{ row }">
                <el-progress :percentage="row.closedLoopRate || 0" :stroke-width="8" :show-text="false" />
                <span class="progress-label">{{ (row.closedLoopRate || 0).toFixed(1) }}%</span>
              </template>
            </el-table-column>
            <el-table-column prop="latestFollowupAt" :label="t('resourceGovernance.followupReview.analysis.latestFollowupAt')" width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.latestFollowupAt) }}
              </template>
            </el-table-column>
          </el-table>
          <div v-if="byAlertTotal > 0" class="pagination-wrapper">
            <el-pagination
              v-model:current-page="byAlertPageNum"
              v-model:page-size="byAlertPageSize"
              :total="byAlertTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              size="small"
              @size-change="loadByAlert"
              @current-change="loadByAlert"
            />
          </div>
        </template>
      </el-tab-pane>

      <!-- By Notification Tab -->
      <el-tab-pane :label="t('resourceGovernance.followupReview.analysis.byNotification')" name="byNotification">
        <div class="tab-filter-bar">
          <el-select v-model="byNotificationFilter.channelType" :placeholder="t('resourceGovernance.followupReview.filter.channelType')" clearable size="small" style="width: 120px" @change="loadByNotification">
            <el-option :label="t('resourceGovernance.followupReview.channelTypes.INTERNAL')" value="INTERNAL" />
            <el-option :label="t('resourceGovernance.followupReview.channelTypes.EMAIL_DRAFT')" value="EMAIL_DRAFT" />
            <el-option :label="t('resourceGovernance.followupReview.channelTypes.WEBHOOK_DRAFT')" value="WEBHOOK_DRAFT" />
          </el-select>
          <el-select v-model="byNotificationFilter.followUpStatus" :placeholder="t('resourceGovernance.followupReview.filter.followUpStatus')" clearable size="small" style="width: 120px" @change="loadByNotification">
            <el-option :label="t('resourceGovernance.followup.statuses.IN_PROGRESS')" value="IN_PROGRESS" />
            <el-option :label="t('resourceGovernance.followup.statuses.COMPLETED')" value="COMPLETED" />
            <el-option :label="t('resourceGovernance.followup.statuses.CANCELLED')" value="CANCELLED" />
          </el-select>
          <el-select v-model="byNotificationFilter.receiptStatus" :placeholder="t('resourceGovernance.followupReview.filter.receiptStatus')" clearable size="small" style="width: 120px" @change="loadByNotification">
            <el-option :label="t('resourceGovernance.followup.receiptStatuses.PENDING')" value="PENDING" />
            <el-option :label="t('resourceGovernance.followup.receiptStatuses.CONFIRMED')" value="CONFIRMED" />
            <el-option :label="t('resourceGovernance.followup.receiptStatuses.FAILED')" value="FAILED" />
            <el-option :label="t('resourceGovernance.followup.receiptStatuses.NO_RESPONSE')" value="NO_RESPONSE" />
          </el-select>
          <el-button size="small" @click="loadByNotification">{{ t('common.refresh') }}</el-button>
        </div>
        <div v-if="byNotificationLoading" class="section-loading">
          <el-skeleton :rows="5" animated />
        </div>
        <el-alert v-else-if="byNotificationError" :title="byNotificationError" type="error" :closable="false" show-icon />
        <template v-else>
          <el-table :data="byNotificationRecords" stripe size="small">
            <el-table-column prop="notificationNo" :label="t('resourceGovernance.followupReview.analysis.notificationNo')" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <el-button type="primary" link size="small">{{ row.notificationNo }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="alertNo" :label="t('resourceGovernance.followupReview.analysis.alertNo')" min-width="160" show-overflow-tooltip />
            <el-table-column prop="channelType" :label="t('resourceGovernance.followupReview.analysis.channelType')" width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ formatChannelType(row.channelType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetUserName" :label="t('resourceGovernance.followupReview.analysis.targetUserName')" min-width="140" show-overflow-tooltip />
            <el-table-column prop="followupCount" :label="t('resourceGovernance.followupReview.analysis.followupCount')" width="80" align="right" />
            <el-table-column prop="latestFollowupStatus" :label="t('resourceGovernance.followupReview.analysis.latestFollowupStatus')" width="140">
              <template #default="{ row }">
                <el-tag :type="getFollowUpStatusTagType(row.latestFollowupStatus)" size="small">
                  {{ t(`resourceGovernance.followup.statuses.${row.latestFollowupStatus}`) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="latestReceiptStatus" :label="t('resourceGovernance.followupReview.analysis.latestReceiptStatus')" width="140">
              <template #default="{ row }">
                <el-tag :type="getReceiptStatusTagType(row.latestReceiptStatus)" size="small">
                  {{ t(`resourceGovernance.followup.receiptStatuses.${row.latestReceiptStatus}`) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="firstFollowupAt" :label="t('resourceGovernance.followupReview.analysis.firstFollowupAt')" width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.firstFollowupAt) }}
              </template>
            </el-table-column>
          </el-table>
          <div v-if="byNotificationTotal > 0" class="pagination-wrapper">
            <el-pagination
              v-model:current-page="byNotificationPageNum"
              v-model:page-size="byNotificationPageSize"
              :total="byNotificationTotal"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              size="small"
              @size-change="loadByNotification"
              @current-change="loadByNotification"
            />
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- Detail List Section -->
    <div class="section-header">
      <span class="section-title">{{ t('resourceGovernance.followupReview.detailList.title') }}</span>
    </div>
    <div class="detail-filter-bar">
      <el-select v-model="detailFilter.followUpType" :placeholder="t('resourceGovernance.followupReview.filter.followUpType')" clearable size="small" style="width: 140px" @change="loadDetails">
        <el-option :label="t('resourceGovernance.followup.types.FOLLOW_UP')" value="FOLLOW_UP" />
        <el-option :label="t('resourceGovernance.followup.types.REPLY_RECEIVED')" value="REPLY_RECEIVED" />
        <el-option :label="t('resourceGovernance.followup.types.CALL_MADE')" value="CALL_MADE" />
        <el-option :label="t('resourceGovernance.followup.types.VISIT_MADE')" value="VISIT_MADE" />
        <el-option :label="t('resourceGovernance.followup.types.OTHER')" value="OTHER" />
      </el-select>
      <el-select v-model="detailFilter.followUpStatus" :placeholder="t('resourceGovernance.followupReview.filter.followUpStatus')" clearable size="small" style="width: 120px" @change="loadDetails">
        <el-option :label="t('resourceGovernance.followup.statuses.IN_PROGRESS')" value="IN_PROGRESS" />
        <el-option :label="t('resourceGovernance.followup.statuses.COMPLETED')" value="COMPLETED" />
        <el-option :label="t('resourceGovernance.followup.statuses.CANCELLED')" value="CANCELLED" />
      </el-select>
      <el-select v-model="detailFilter.receiptStatus" :placeholder="t('resourceGovernance.followupReview.filter.receiptStatus')" clearable size="small" style="width: 120px" @change="loadDetails">
        <el-option :label="t('resourceGovernance.followup.receiptStatuses.PENDING')" value="PENDING" />
        <el-option :label="t('resourceGovernance.followup.receiptStatuses.CONFIRMED')" value="CONFIRMED" />
        <el-option :label="t('resourceGovernance.followup.receiptStatuses.FAILED')" value="FAILED" />
        <el-option :label="t('resourceGovernance.followup.receiptStatuses.NO_RESPONSE')" value="NO_RESPONSE" />
      </el-select>
      <el-button size="small" @click="loadDetails">{{ t('common.refresh') }}</el-button>
    </div>
    <div v-if="detailLoading" class="section-loading">
      <el-skeleton :rows="5" animated />
    </div>
    <el-alert v-else-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
    <template v-else>
      <el-table :data="detailRecords" stripe size="small">
        <el-table-column prop="followUpNo" :label="t('resourceGovernance.followupReview.detailList.followUpNo')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button type="primary" link size="small">{{ row.followUpNo }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="notificationNo" :label="t('resourceGovernance.followupReview.detailList.notificationNo')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="alertNo" :label="t('resourceGovernance.followupReview.detailList.alertNo')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="followUpType" :label="t('resourceGovernance.followupReview.detailList.followUpType')" width="140">
          <template #default="{ row }">
            <el-tag size="small">{{ t(`resourceGovernance.followup.types.${row.followUpType}`) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="followUpStatus" :label="t('resourceGovernance.followupReview.detailList.followUpStatus')" width="140">
          <template #default="{ row }">
            <el-tag :type="getFollowUpStatusTagType(row.followUpStatus)" size="small">
              {{ t(`resourceGovernance.followup.statuses.${row.followUpStatus}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="receiptStatus" :label="t('resourceGovernance.followupReview.detailList.receiptStatus')" width="140">
          <template #default="{ row }">
            <el-tag :type="getReceiptStatusTagType(row.receiptStatus)" size="small">
              {{ t(`resourceGovernance.followup.receiptStatuses.${row.receiptStatus}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdByName" :label="t('resourceGovernance.followupReview.detailList.createdByName')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createdAt" :label="t('resourceGovernance.followupReview.detailList.createdAt')" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="firstFollowupForNotification" :label="t('resourceGovernance.followupReview.detailList.firstFollowup')" width="80" align="center">
          <template #default="{ row }">
            <el-icon v-if="row.firstFollowupForNotification" class="first-followup-icon"><Star /></el-icon>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="detailTotal > 0" class="pagination-wrapper">
        <el-pagination
          v-model:current-page="detailPageNum"
          v-model:page-size="detailPageSize"
          :total="detailTotal"
          :page-sizes="[10, 20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next"
          size="small"
          @size-change="loadDetails"
          @current-change="loadDetails"
        />
      </div>
    </template>

    <!-- Manual Only Notice -->
    <p class="manual-only-notice">{{ t('resourceGovernance.followupReview.manualOnlyNotice') }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Star } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import {
  getGovernanceAlertNotificationFollowupReviewOverview,
  getGovernanceAlertReceiptStats,
  getGovernanceAlertFollowupStatusDistribution,
  listGovernanceAlertFollowupByHandler,
  listGovernanceAlertFollowupByAlert,
  listGovernanceAlertFollowupByNotification,
  listGovernanceAlertFollowupReviewDetails,
  type GovernanceAlertNotificationFollowupReviewOverviewResponse,
  type GovernanceAlertReceiptStatsResponse,
  type GovernanceAlertFollowupStatusDistributionResponse,
  type GovernanceAlertFollowupByHandlerItem,
  type GovernanceAlertFollowupByAlertItem,
  type GovernanceAlertFollowupByNotificationItem,
  type GovernanceAlertFollowupReviewDetailItem,
  type GovernanceAlertNotificationFollowupStatus,
  type GovernanceAlertReceiptStatus,
  type GovernanceAlertNotificationFollowupType,
  type GovernanceAlertNotificationChannelType,
  type GovernanceAlertLevel,
  type GovernanceAlertStatus
} from '@/api/asset'

const props = defineProps<{
  envId?: number
  resourcePoolId?: number
  serviceId?: number
}>()

const { t } = useI18n()

// Time range
const timeRange = ref<[string, string] | null>(null)

// Overview state
const overview = ref<GovernanceAlertNotificationFollowupReviewOverviewResponse | null>(null)
const overviewLoading = ref(false)
const overviewError = ref('')

// Receipt stats state
const receiptStats = ref<GovernanceAlertReceiptStatsResponse | null>(null)
const receiptStatsLoading = ref(false)
const receiptStatsError = ref('')

// Distribution state
const distribution = ref<GovernanceAlertFollowupStatusDistributionResponse | null>(null)
const distributionLoading = ref(false)
const distributionError = ref('')

// Analysis tab
const activeAnalysisTab = ref('byHandler')

// By Handler state
const byHandlerRecords = ref<GovernanceAlertFollowupByHandlerItem[]>([])
const byHandlerLoading = ref(false)
const byHandlerError = ref('')
const byHandlerPageNum = ref(1)
const byHandlerPageSize = ref(20)
const byHandlerTotal = ref(0)

// By Alert state
const byAlertRecords = ref<GovernanceAlertFollowupByAlertItem[]>([])
const byAlertLoading = ref(false)
const byAlertError = ref('')
const byAlertPageNum = ref(1)
const byAlertPageSize = ref(20)
const byAlertTotal = ref(0)
const byAlertFilter = reactive({
  alertLevel: undefined as GovernanceAlertLevel | undefined,
  alertStatus: undefined as GovernanceAlertStatus | undefined
})

// By Notification state
const byNotificationRecords = ref<GovernanceAlertFollowupByNotificationItem[]>([])
const byNotificationLoading = ref(false)
const byNotificationError = ref('')
const byNotificationPageNum = ref(1)
const byNotificationPageSize = ref(20)
const byNotificationTotal = ref(0)
const byNotificationFilter = reactive({
  channelType: undefined as GovernanceAlertNotificationChannelType | undefined,
  followUpStatus: undefined as GovernanceAlertNotificationFollowupStatus | undefined,
  receiptStatus: undefined as GovernanceAlertReceiptStatus | undefined
})

// Detail state
const detailRecords = ref<GovernanceAlertFollowupReviewDetailItem[]>([])
const detailLoading = ref(false)
const detailError = ref('')
const detailPageNum = ref(1)
const detailPageSize = ref(20)
const detailTotal = ref(0)
const detailFilter = reactive({
  followUpType: undefined as GovernanceAlertNotificationFollowupType | undefined,
  followUpStatus: undefined as GovernanceAlertNotificationFollowupStatus | undefined,
  receiptStatus: undefined as GovernanceAlertReceiptStatus | undefined
})

// Helper functions
function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return '-'
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm')
}

function formatChannelType(type: string): string {
  return t(`resourceGovernance.followupReview.channelTypes.${type}`)
}

function getFollowUpStatusTagType(status: string): '' | 'success' | 'warning' | 'info' {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'IN_PROGRESS': return 'warning'
    case 'CANCELLED': return 'info'
    default: return ''
  }
}

function getReceiptStatusTagType(status: string): 'warning' | 'success' | 'danger' | 'info' {
  switch (status) {
    case 'PENDING': return 'warning'
    case 'CONFIRMED': return 'success'
    case 'FAILED': return 'danger'
    case 'NO_RESPONSE': return 'info'
    default: return 'info'
  }
}

function getAlertLevelTagType(level: string): 'danger' | 'warning' | 'success' | 'info' {
  switch (level) {
    case 'CRITICAL': return 'danger'
    case 'WARNING': return 'warning'
    case 'INFO': return 'success'
    default: return 'info'
  }
}

function getAlertStatusTagType(status: string): 'danger' | 'warning' | 'success' | 'info' {
  switch (status) {
    case 'OPEN': return 'danger'
    case 'ACKED': return 'warning'
    case 'RESOLVED': return 'success'
    default: return 'info'
  }
}

function getTimeRangeParams() {
  if (timeRange.value && timeRange.value.length === 2) {
    return {
      startTime: timeRange.value[0],
      endTime: timeRange.value[1]
    }
  }
  return {}
}

// API calls
async function loadOverview() {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    const params = getTimeRangeParams()
    overview.value = await getGovernanceAlertNotificationFollowupReviewOverview(params)
  } catch (err) {
    overviewError.value = t('resourceGovernance.followupReview.overview.loadFailed')
    console.error('Failed to load overview:', err)
  } finally {
    overviewLoading.value = false
  }
}

async function loadReceiptStats() {
  receiptStatsLoading.value = true
  receiptStatsError.value = ''
  try {
    const params = getTimeRangeParams()
    receiptStats.value = await getGovernanceAlertReceiptStats(params)
  } catch (err) {
    receiptStatsError.value = t('resourceGovernance.followupReview.receiptStats.loadFailed')
    console.error('Failed to load receipt stats:', err)
  } finally {
    receiptStatsLoading.value = false
  }
}

async function loadDistribution() {
  distributionLoading.value = true
  distributionError.value = ''
  try {
    const params = getTimeRangeParams()
    distribution.value = await getGovernanceAlertFollowupStatusDistribution(params)
  } catch (err) {
    distributionError.value = t('resourceGovernance.followupReview.statusDistribution.loadFailed')
    console.error('Failed to load distribution:', err)
  } finally {
    distributionLoading.value = false
  }
}

async function loadByHandler() {
  byHandlerLoading.value = true
  byHandlerError.value = ''
  try {
    const params = {
      ...getTimeRangeParams(),
      pageNum: byHandlerPageNum.value,
      pageSize: Math.min(byHandlerPageSize.value, 200)
    }
    const result = await listGovernanceAlertFollowupByHandler(params)
    byHandlerRecords.value = result.records || []
    byHandlerTotal.value = result.total || 0
  } catch (err) {
    byHandlerError.value = t('resourceGovernance.followupReview.analysis.loadFailed')
    console.error('Failed to load by handler:', err)
  } finally {
    byHandlerLoading.value = false
  }
}

async function loadByAlert() {
  byAlertLoading.value = true
  byAlertError.value = ''
  try {
    const params: Record<string, unknown> = {
      ...getTimeRangeParams(),
      pageNum: byAlertPageNum.value,
      pageSize: Math.min(byAlertPageSize.value, 200)
    }
    if (byAlertFilter.alertLevel) params.alertLevel = byAlertFilter.alertLevel
    if (byAlertFilter.alertStatus) params.alertStatus = byAlertFilter.alertStatus
    const result = await listGovernanceAlertFollowupByAlert(params)
    byAlertRecords.value = result.records || []
    byAlertTotal.value = result.total || 0
  } catch (err) {
    byAlertError.value = t('resourceGovernance.followupReview.analysis.loadFailed')
    console.error('Failed to load by alert:', err)
  } finally {
    byAlertLoading.value = false
  }
}

async function loadByNotification() {
  byNotificationLoading.value = true
  byNotificationError.value = ''
  try {
    const params: Record<string, unknown> = {
      ...getTimeRangeParams(),
      pageNum: byNotificationPageNum.value,
      pageSize: Math.min(byNotificationPageSize.value, 200)
    }
    if (byNotificationFilter.channelType) params.channelType = byNotificationFilter.channelType
    if (byNotificationFilter.followUpStatus) params.followUpStatus = byNotificationFilter.followUpStatus
    if (byNotificationFilter.receiptStatus) params.receiptStatus = byNotificationFilter.receiptStatus
    const result = await listGovernanceAlertFollowupByNotification(params)
    byNotificationRecords.value = result.records || []
    byNotificationTotal.value = result.total || 0
  } catch (err) {
    byNotificationError.value = t('resourceGovernance.followupReview.analysis.loadFailed')
    console.error('Failed to load by notification:', err)
  } finally {
    byNotificationLoading.value = false
  }
}

async function loadDetails() {
  detailLoading.value = true
  detailError.value = ''
  try {
    const params: Record<string, unknown> = {
      ...getTimeRangeParams(),
      pageNum: detailPageNum.value,
      pageSize: Math.min(detailPageSize.value, 200)
    }
    if (detailFilter.followUpType) params.followUpType = detailFilter.followUpType
    if (detailFilter.followUpStatus) params.followUpStatus = detailFilter.followUpStatus
    if (detailFilter.receiptStatus) params.receiptStatus = detailFilter.receiptStatus
    const result = await listGovernanceAlertFollowupReviewDetails(params)
    detailRecords.value = result.records || []
    detailTotal.value = result.total || 0
  } catch (err) {
    detailError.value = t('resourceGovernance.followupReview.detailList.loadFailed')
    console.error('Failed to load details:', err)
  } finally {
    detailLoading.value = false
  }
}

// Public refresh method
function refresh() {
  loadOverview()
  loadReceiptStats()
  loadDistribution()
  loadByHandler()
  loadByAlert()
  loadByNotification()
  loadDetails()
}

// Watch props changes
watch(() => [props.envId, props.resourcePoolId, props.serviceId], () => {
  refresh()
})

onMounted(() => {
  refresh()
})

defineExpose({ refresh })
</script>

<style scoped>
.notification-followup-review-tab {
  padding: 0;
}

.page-title-section {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.section-main-title {
  font-size: 18px;
  font-weight: 500;
  color: var(--ih-text-primary);
  margin: 0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  margin-top: 20px;
}

.section-header:first-of-type {
  margin-top: 0;
}

.section-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--ih-text-primary);
}

.section-loading {
  padding: 16px;
  background: var(--ih-bg-content);
  border-radius: 4px;
}

/* Overview Grid */
.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 0;
  margin-bottom: var(--space-md);
}

.overview-card {
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.overview-card:first-child {
  border-left: 0;
  padding-left: 0;
}

.overview-value {
  font-family: var(--font-outlier);
  font-size: var(--text-2xl);
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.overview-card-danger .overview-value {
  color: var(--color-bad);
}

.overview-card-warning .overview-value,
.overview-card-running .overview-value {
  color: var(--color-accent);
}

.overview-card-success .overview-value {
  color: var(--color-ok);
}

.overview-card-info .overview-value {
  color: var(--color-cool);
}

.overview-label {
  margin-top: var(--space-3xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

/* Rate Metrics */
.rate-metrics-grid,
.overall-stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 0;
  margin-bottom: var(--space-md);
}

.rate-card,
.overall-stat-item {
  display: flex;
  flex-direction: column;
  padding: var(--space-2xs) var(--space-md) var(--space-2xs) 0;
  border-left: var(--rule-hair) solid var(--color-rule);
  padding-left: var(--space-md);
}

.rate-card:first-child,
.overall-stat-item:first-child {
  border-left: 0;
  padding-left: 0;
}

.rate-header,
.overall-label {
  margin-bottom: var(--space-2xs);
  font-size: var(--text-xs);
  color: var(--color-muted);
}

.rate-value,
.overall-value {
  font-family: var(--font-outlier);
  font-size: var(--text-xl);
  font-weight: 400;
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.overdue-value,
.rate-card-danger .rate-value {
  color: var(--color-bad);
}

.overall-success .overall-value {
  color: var(--color-ok);
}

.overall-danger .overall-value {
  color: var(--color-bad);
}

.overall-info .overall-value {
  color: var(--color-cool);
}

.distribution-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-lg);
  margin-bottom: var(--space-md);
}

.distribution-card {
  padding-top: var(--space-sm);
  border-top: var(--rule-hair) solid var(--color-rule);
}

.distribution-title {
  margin-bottom: var(--space-xs);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-ink);
}

.distribution-items {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.distribution-item {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.distribution-count {
  font-family: var(--font-outlier);
  font-weight: 400;
  font-variant-numeric: tabular-nums;
  color: var(--color-ink);
}

.distribution-percentage {
  font-family: var(--font-outlier);
  font-size: var(--text-xs);
  color: var(--color-muted);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .distribution-grid {
    grid-template-columns: 1fr;
  }
}

/* Analysis Tabs */
.analysis-tabs {
  margin-bottom: 16px;
}

.tab-filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

/* Detail Filter Bar */
.detail-filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

/* Pagination */
.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

/* First Followup Icon */
.first-followup-icon {
  color: var(--ih-warning);
  font-size: 16px;
}

/* Manual Only Notice */
.manual-only-notice {
  margin-top: var(--space-md);
  padding-top: var(--space-xs);
  border-top: var(--rule-hair) solid var(--color-rule);
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: 1.6;
}
</style>
