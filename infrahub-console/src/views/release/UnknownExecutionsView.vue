<template>
  <div class="unknown-executions-view">
    <div class="page-header">
      <h2 class="page-title">{{ t('unknownExecutions.title') }}</h2>
      <p class="page-subtitle">{{ t('unknownExecutions.subtitle') }}</p>
    </div>

    <el-alert
      v-if="!canAdmin"
      type="info"
      :closable="false"
      show-icon
      class="permission-alert"
      :title="t('unknownExecutions.notAdmin')"
    />

    <el-card class="table-card" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('unknownExecutions.list') }}</span>
          <div class="header-actions">
            <el-button size="small" @click="openClosure">{{ t('unknownExecutions.closureAction') }}</el-button>
            <el-button size="small" @click="openRecover">{{ t('unknownExecutions.recover') }}</el-button>
            <el-button size="small" @click="loadData">
              {{ t('unknownExecutions.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="filter-bar">
        <el-input
          v-model="filter.serviceId"
          :placeholder="t('unknownExecutions.filterService')"
          clearable
          style="width: 180px"
          @keyup.enter="loadData"
        />
        <el-input
          v-model="filter.envId"
          :placeholder="t('unknownExecutions.filterEnv')"
          clearable
          style="width: 180px"
          @keyup.enter="loadData"
        />
        <el-button @click="loadData">{{ t('common.search') }}</el-button>
      </div>

      <el-table :data="rows" style="width: 100%" empty-text="">
        <el-table-column :label="t('unknownExecutions.source')" width="120">
          <template #default="{ row }">
            <el-tag :type="row.sourceType === 'ROLLBACK' ? 'warning' : 'primary'" size="small">
              {{ row.sourceType }}
            </el-tag>
            <el-tag v-if="row.legacy" type="info" size="small" class="legacy-tag">
              {{ t('unknownExecutions.legacyTag') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="serviceId" :label="t('unknownExecutions.serviceId')" width="90" align="center" />
        <el-table-column prop="envId" :label="t('unknownExecutions.envId')" width="90" align="center" />
        <el-table-column prop="releaseOrderId" :label="t('unknownExecutions.releaseOrderId')" width="110" align="center">
          <template #default="{ row }">{{ row.releaseOrderId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="rollbackOrderId" :label="t('unknownExecutions.rollbackOrderId')" width="110" align="center">
          <template #default="{ row }">{{ row.rollbackOrderId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="deployRecordId" :label="t('unknownExecutions.deployRecordId')" width="110" align="center">
          <template #default="{ row }">{{ row.deployRecordId ?? '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.factLevel')" width="130">
          <template #default="{ row }">
            <el-tag :type="row.factLevel === 'ORDER_LEVEL' ? 'warning' : 'danger'" size="small">
              {{ factLevelLabel(row.factLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.mergeLedgerLabel')" width="140" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.mergedFactCount > 0"
              :content="mergedFactsTooltip(row)"
              placement="top"
            >
              <el-tag type="danger" size="small">
                {{ t('unknownExecutions.mergeCount', { count: row.mergedFactCount }) }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else-if="row.mergeLedgerAvailable === false" type="info" size="small">
              {{ t('unknownExecutions.mergeUnverifiable') }}
            </el-tag>
            <span v-else class="text-secondary">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="detectionSource" :label="t('unknownExecutions.detectionSource')" width="140" />
        <el-table-column prop="detectionReason" :label="t('unknownExecutions.detectionReason')" min-width="240" show-overflow-tooltip />
        <el-table-column :label="t('unknownExecutions.detectedAt')" width="180">
          <template #default="{ row }">{{ formatDate(row.detectedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="260" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.releaseOrderId != null"
              link
              type="success"
              size="small"
              @click="openClaims(row)"
            >
              {{ t('unknownExecutions.claimsAction') }}
            </el-button>
            <el-button
              v-if="canAdmin"
              link
              type="primary"
              size="small"
              @click="openResolve(row)"
            >
              {{ t('unknownExecutions.resolve') }}
            </el-button>
            <span v-else class="text-secondary">-</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && rows.length === 0" class="empty-hint">
        {{ t('unknownExecutions.empty') }}
      </div>
    </el-card>

    <el-dialog v-model="resolveVisible" :title="t('unknownExecutions.resolveTitle')" width="620px">
      <el-alert
        v-if="resolveTarget?.legacy"
        type="warning"
        :closable="false"
        show-icon
        :title="t('unknownExecutions.legacyHint')"
        class="dialog-alert"
      />
      <p class="dialog-hint">{{ t('unknownExecutions.resolveHint') }}</p>
      <template v-if="hasMergedFacts(resolveTarget)">
        <el-alert
          v-if="resolveTarget?.mergeLedgerAvailable === false"
          type="error"
          :closable="false"
          show-icon
          :title="t('unknownExecutions.mergeUnverifiableHint')"
          class="dialog-alert"
        />
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          :title="t('unknownExecutions.mergeHint', { count: resolveTarget?.mergedFactCount ?? 0 })"
          class="dialog-alert"
        />
        <el-descriptions
          v-if="resolveTarget?.mergedFacts?.length"
          :column="1"
          border
          size="small"
          class="dialog-alert"
          :title="t('unknownExecutions.mergeListTitle')"
        >
          <el-descriptions-item
            v-for="(fact, index) in resolveTarget?.mergedFacts ?? []"
            :key="`${fact.unknownId ?? 'x'}-${index}`"
            :label="fact.sourceType || '-'"
          >
            {{ mergedFactLabel(fact) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <el-form label-position="top">
        <el-form-item :label="t('unknownExecutions.outcome')" required>
          <el-radio-group v-model="resolveForm.outcome">
            <el-radio value="REMOTE_EFFECT_APPLIED">{{ t('unknownExecutions.outcomeApplied') }}</el-radio>
            <el-radio value="REMOTE_EFFECT_NOT_APPLIED">{{ t('unknownExecutions.outcomeNotApplied') }}</el-radio>
            <el-radio value="REMOTE_EFFECT_PARTIAL">{{ t('unknownExecutions.outcomePartial') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="hasMergedFacts(resolveTarget)">
          <el-checkbox v-model="resolveForm.acknowledgeMergedFacts">
            {{ t('unknownExecutions.mergeAcknowledge') }}
          </el-checkbox>
        </el-form-item>
        <el-form-item :label="t('unknownExecutions.note')" required>
          <el-input
            v-model="resolveForm.note"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            :placeholder="t('unknownExecutions.notePlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitResolve">
          {{ t('unknownExecutions.confirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="recoverVisible" :title="t('unknownExecutions.recoverTitle')" width="620px">
      <p class="dialog-hint">{{ t('unknownExecutions.recoverHint') }}</p>
      <el-form label-position="top">
        <el-form-item :label="t('unknownExecutions.recoverReleaseId')">
          <el-input v-model="recoverForm.releaseOrderId" clearable />
        </el-form-item>
        <el-form-item :label="t('unknownExecutions.recoverRollbackId')">
          <el-input v-model="recoverForm.rollbackOrderId" clearable />
        </el-form-item>
        <el-form-item :label="t('unknownExecutions.recoverNote')" required>
          <el-input
            v-model="recoverForm.note"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            :placeholder="t('unknownExecutions.recoverNotePlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recoverVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRecover">
          {{ t('unknownExecutions.recoverConfirm') }}
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="claimsVisible" :title="t('unknownExecutions.claimsTitle')" width="820px">
      <p class="dialog-hint">{{ t('unknownExecutions.claimsHint') }}</p>
      <p v-if="claimsTarget" class="dialog-hint">
        {{ t('unknownExecutions.claimsForOrder', { releaseOrderId: claimsTarget.releaseOrderId ?? '-' }) }}
      </p>
      <el-table :data="claimsRows" style="width: 100%" v-loading="claimsLoading" empty-text="">
        <el-table-column prop="id" :label="t('unknownExecutions.claimId')" width="80" align="center" />
        <el-table-column prop="claimPurpose" :label="t('unknownExecutions.claimPurpose')" width="110" />
        <el-table-column :label="t('unknownExecutions.claimStatus')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'HELD' ? 'danger' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.claimHolder')" min-width="220">
          <template #default="{ row }">
            {{ t('unknownExecutions.claimHolderDetail', { run: row.pipelineRunId ?? '-', step: row.pipelineStepRunId ?? '-' }) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.claimOutcome')" width="150">
          <template #default="{ row }">{{ row.releaseOutcome || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.claimReason')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.releaseReason || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canAdmin && row.blocking"
              link
              type="danger"
              size="small"
              @click="openAbandon(row)"
            >
              {{ t('unknownExecutions.abandon') }}
            </el-button>
            <span v-else class="text-secondary">-</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!claimsLoading && claimsRows.length === 0" class="empty-hint">
        {{ t('unknownExecutions.claimsEmpty') }}
      </div>
      <template #footer>
        <el-button @click="claimsVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="abandonVisible" :title="t('unknownExecutions.abandonTitle')" width="580px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="t('unknownExecutions.abandonWarning')"
        class="dialog-alert"
      />
      <el-form label-position="top">
        <el-form-item :label="t('unknownExecutions.abandonNote')" required>
          <el-input
            v-model="abandonForm.note"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            :placeholder="t('unknownExecutions.abandonNotePlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="abandonVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
        <el-button type="danger" :loading="submitting" @click="submitAbandon">
          {{ t('unknownExecutions.abandonConfirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closureVisible" :title="t('unknownExecutions.closureTitle')" width="780px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="t('unknownExecutions.closureHint')"
        class="dialog-alert"
      />
      <el-form label-position="top">
        <el-form-item :label="t('unknownExecutions.closureReleaseId')">
          <div class="closure-lookup">
            <el-input v-model="closureForm.releaseOrderId" clearable style="width: 200px" />
            <el-button :loading="closurePreviewing" @click="loadClosurePreview">
              {{ t('unknownExecutions.closurePreview') }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <template v-if="closurePreview">
        <el-descriptions :column="1" border size="small" class="dialog-alert">
          <el-descriptions-item :label="t('unknownExecutions.closureOrderStatus')">
            {{ closurePreview.orderStatus || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('unknownExecutions.closureClaim')">
            <span v-if="closurePreview.claim?.claimId != null">
              {{
                t('unknownExecutions.closureClaimValue', {
                  claimId: closurePreview.claim.claimId,
                  status: closurePreview.claim.status ?? '-',
                  outcome: closurePreview.claim.releaseOutcome ?? '-',
                })
              }}
            </span>
            <span v-else>{{ t('unknownExecutions.closureClaimMissing') }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('unknownExecutions.closureRun')">
            <span v-if="closurePreview.pipelineRun?.pipelineRunId != null">
              {{
                t('unknownExecutions.closureRunValue', {
                  runId: closurePreview.pipelineRun.pipelineRunId,
                  status: closurePreview.pipelineRun.runStatus ?? '-',
                })
              }}
            </span>
            <span v-else>
              {{ closurePreview.claim?.pipelineRunId != null
                ? t('unknownExecutions.closureRunValue', {
                    runId: closurePreview.claim.pipelineRunId,
                    status: closurePreview.claim.pipelineRunStatus ?? '-',
                  })
                : t('unknownExecutions.closureRunMissing') }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('unknownExecutions.closureRecords')">
            {{
              t('unknownExecutions.closureRecordsValue', {
                total: closureRecordTotal,
                success: closurePreview.successRecordCount ?? 0,
                failed: closurePreview.failedRecordCount ?? 0,
                unknown: closurePreview.unknownRecordCount ?? 0,
                running: closurePreview.runningRecordCount ?? 0,
                unattributed: closurePreview.recordCountWithoutInstanceAttribution ?? 0,
              })
            }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('unknownExecutions.closureInstanceAttribution')">
            <span v-if="closurePreview.instanceAttributionProvable">
              {{
                t('unknownExecutions.closureAttributionProvable', {
                  count: closurePreview.provenInstanceIds?.length ?? 0,
                  ids: idListText(closurePreview.provenInstanceIds),
                })
              }}
            </span>
            <span v-else>
              {{ t('unknownExecutions.closureAttributionNotProvable') }}
              <span v-if="closurePreview.instanceAttributionRejectionReason">
                （{{ closurePreview.instanceAttributionRejectionReason }}）
              </span>
            </span>
            <div v-if="closurePreview.currentInstanceIdsNotDeployed?.length" class="attribution-hint">
              {{
                t('unknownExecutions.closureNotDeployed', {
                  ids: idListText(closurePreview.currentInstanceIdsNotDeployed),
                })
              }}
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('unknownExecutions.closureFacts')">
            {{
              t('unknownExecutions.closureFactsValue', {
                order: closurePreview.unresolvedFactCountForOrder ?? 0,
                env: closurePreview.unresolvedFactCountForServiceEnv ?? 0,
              })
            }}
            <div v-if="closurePreview.heldClaimOnServiceEnv" class="attribution-hint">
              {{ t('unknownExecutions.closureHeldClaim') }}
            </div>
            <div v-if="closurePreview.activeCanaryPlan" class="attribution-hint">
              {{ t('unknownExecutions.closureCanaryPlan') }}
            </div>
          </el-descriptions-item>
          <el-descriptions-item v-if="missingTables.length" :label="t('unknownExecutions.closureTables')">
            <span class="attribution-hint">
              {{ t('unknownExecutions.closureTablesMissing', { tables: missingTables.join('、') }) }}
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="route-list dialog-alert">
          <div class="route-title">{{ t('unknownExecutions.closureRoutes') }}</div>
          <el-alert
            v-if="closurePreview.noRouteReason"
            type="error"
            :closable="false"
            show-icon
            :title="t('unknownExecutions.closureNoRoute')"
            class="dialog-alert"
          >
            <div class="route-reason">{{ closurePreview.noRouteReason }}</div>
          </el-alert>
          <div v-for="route in closurePreview.routes ?? []" :key="route.route" class="route-item">
            <div class="route-head">
              <el-tag :type="route.available ? 'success' : 'info'" size="small">{{ routeLabel(route) }}</el-tag>
              <span class="route-state">
                {{ route.available
                  ? t('unknownExecutions.closureRouteAvailable')
                  : t('unknownExecutions.closureRouteBlocked') }}
              </span>
              <span v-if="route.retryableLater" class="route-flag">
                {{ t('unknownExecutions.closureRouteRetryableLater') }}
              </span>
              <span v-if="route.requiresRemoteManualCheck" class="route-flag">
                {{ t('unknownExecutions.closureRouteRemoteCheck') }}
              </span>
            </div>
            <div v-if="route.actionPath" class="route-path">{{ route.actionPath }}</div>
            <ul v-if="route.blockingReasons?.length" class="route-reasons">
              <li v-for="(reason, index) in route.blockingReasons" :key="index">{{ reason }}</li>
            </ul>
            <div v-if="route.note" class="route-note">{{ route.note }}</div>
          </div>
        </div>

        <el-table
          v-if="closurePreview.deployRecords?.length"
          :data="closurePreview.deployRecords"
          size="small"
          class="dialog-alert"
        >
          <el-table-column prop="deployRecordId" :label="t('unknownExecutions.deployRecordId')" width="110" align="center" />
          <el-table-column prop="deployStatus" :label="t('unknownExecutions.resolvedOutcome')" width="120" />
          <el-table-column prop="deployBatch" label="batch" width="80" align="center" />
          <el-table-column :label="t('unknownExecutions.instanceId')" min-width="160">
            <template #default="{ row }">
              {{ row.instanceId != null ? `${row.instanceId} / ${row.instanceName ?? '-'}` : '-' }}
            </template>
          </el-table-column>
          <el-table-column :label="t('unknownExecutions.closureInstanceAttribution')" width="170">
            <template #default="{ row }">{{ attributionLabel(row.instanceAttribution) }}</template>
          </el-table-column>
        </el-table>

        <el-form label-position="top">
          <el-form-item :label="t('unknownExecutions.closureOutcome')" required>
            <el-radio-group v-model="closureForm.outcome">
              <el-radio value="SUCCEEDED" :disabled="!closurePreview.adoptSuccessAllowed">
                {{ t('unknownExecutions.closureAdoptSuccess') }}
              </el-radio>
              <el-radio value="FAILED" :disabled="!closurePreview.closeAsFailedAllowed">
                {{ t('unknownExecutions.closureCloseFailed') }}
              </el-radio>
            </el-radio-group>
            <div class="attribution-hint">{{ closureOutcomeHint }}</div>
            <div v-if="closureBlockedReasons" class="attribution-hint">
              {{ t('unknownExecutions.closureBlocked') }} {{ closureBlockedReasons }}
            </div>
          </el-form-item>
          <el-form-item :label="t('unknownExecutions.closureNote')" required>
            <el-input
              v-model="closureForm.note"
              type="textarea"
              :rows="4"
              maxlength="1000"
              show-word-limit
              :placeholder="t('unknownExecutions.closureNotePlaceholder')"
            />
          </el-form-item>
        </el-form>

        <el-alert
          v-if="closureResult"
          :type="closureResult.blockingRemains ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="t('unknownExecutions.closureSuccess', { conclusion: closureResult.conclusion ?? '-' })"
          class="dialog-alert"
        >
          <div v-if="closureResult.blockingRemains">{{ t('unknownExecutions.closureBlockingRemains') }}</div>
          <div v-if="closureResult.nextStepSummary" class="route-reason">
            {{ closureResult.nextStepSummary }}
          </div>
          <div>{{ closureResult.disclaimer || closurePreview.disclaimer || '' }}</div>
        </el-alert>

        <div v-if="closureResult?.remainingRoutes?.length" class="route-list dialog-alert">
          <div class="route-title">{{ t('unknownExecutions.closureRemainingRoutes') }}</div>
          <div v-for="route in closureResult.remainingRoutes" :key="route.route" class="route-item">
            <div class="route-head">
              <el-tag :type="route.available ? 'success' : 'info'" size="small">{{ routeLabel(route) }}</el-tag>
              <span class="route-state">
                {{ route.available
                  ? t('unknownExecutions.closureRouteAvailable')
                  : t('unknownExecutions.closureRouteBlocked') }}
              </span>
            </div>
            <div v-if="route.actionPath" class="route-path">{{ route.actionPath }}</div>
            <ul v-if="route.blockingReasons?.length" class="route-reasons">
              <li v-for="(reason, index) in route.blockingReasons" :key="index">{{ reason }}</li>
            </ul>
            <div v-if="route.note" class="route-note">{{ route.note }}</div>
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="closureVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="!closurePreview || !closureForm.outcome"
          :loading="submitting"
          @click="submitClosure"
        >
          {{ t('unknownExecutions.closureConfirm') }}
        </el-button>
      </template>
    </el-dialog>

    <el-card class="table-card" v-loading="resolvedLoading">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('unknownExecutions.resolvedList') }}</span>
          <span class="card-hint">{{ t('unknownExecutions.resolvedHint') }}</span>
        </div>
      </template>

      <el-table :data="resolvedRows" style="width: 100%" empty-text="">
        <el-table-column :label="t('unknownExecutions.source')" width="120">
          <template #default="{ row }">
            <el-tag :type="row.sourceType === 'ROLLBACK' ? 'warning' : 'primary'" size="small">
              {{ row.sourceType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="serviceId" :label="t('unknownExecutions.serviceId')" width="90" align="center" />
        <el-table-column prop="envId" :label="t('unknownExecutions.envId')" width="90" align="center" />
        <el-table-column prop="releaseOrderId" :label="t('unknownExecutions.releaseOrderId')" width="110" align="center">
          <template #default="{ row }">{{ row.releaseOrderId ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="rollbackOrderId" :label="t('unknownExecutions.rollbackOrderId')" width="110" align="center">
          <template #default="{ row }">{{ row.rollbackOrderId ?? '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.resolvedOutcome')" width="140">
          <template #default="{ row }">{{ outcomeLabel(row.resolutionOutcome) }}</template>
        </el-table-column>
        <el-table-column prop="resolutionNote" :label="t('unknownExecutions.resolvedNote')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('unknownExecutions.resolvedBy')" width="100" align="center">
          <template #default="{ row }">{{ row.resolvedBy ?? '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('unknownExecutions.resolvedAt')" width="180">
          <template #default="{ row }">{{ formatDate(row.resolvedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">
              {{ t('unknownExecutions.detail') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!resolvedLoading && resolvedRows.length === 0" class="empty-hint">
        {{ t('unknownExecutions.resolvedEmpty') }}
      </div>
    </el-card>

    <el-dialog v-model="detailVisible" :title="t('unknownExecutions.detailTitle')" width="640px">
      <el-descriptions v-if="detailTarget" :column="1" border>
        <el-descriptions-item :label="t('unknownExecutions.source')">
          {{ detailTarget.sourceType }}{{ detailTarget.legacy ? ` (${t('unknownExecutions.legacyTag')})` : '' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.releaseOrderId')">
          {{ detailTarget.releaseOrderId ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.rollbackOrderId')">
          {{ detailTarget.rollbackOrderId ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.deployRecordId')">
          {{ detailTarget.deployRecordId ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.detectionSource')">
          {{ detailTarget.detectionSource }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.detectionReason')">
          {{ detailTarget.detectionReason || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.detectedAt')">
          {{ formatDate(detailTarget.detectedAt) }}
        </el-descriptions-item>
        <el-descriptions-item v-if="hasMergedFacts(detailTarget)" :label="t('unknownExecutions.mergeLedgerLabel')">
          {{ mergedFactsSummary(detailTarget) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.resolvedOutcome')">
          {{ outcomeLabel(detailTarget.resolutionOutcome) }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.resolvedNote')">
          {{ detailTarget.resolutionNote || '-' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('unknownExecutions.resolvedAt')">
          {{ formatDate(detailTarget.resolvedAt) }} / {{ detailTarget.resolvedBy ?? '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">{{ t('unknownExecutions.cancel') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import {
  closeStuckRelease,
  listResolvedRuntimeExecutionUnknowns,
  listRuntimeExecutionUnknowns,
  previewStuckReleaseClosure,
  recoverStuckExecution,
  resolveLegacyRuntimeExecutionUnknown,
  resolveRuntimeExecutionUnknown,
  type RecoverStuckExecutionPayload,
  type ResolveRuntimeExecutionUnknownPayload,
  type RuntimeExecutionUnknown,
  type RuntimeExecutionUnknownMergedFact,
  type RuntimeExecutionUnknownOutcome,
  type ReleaseClosureRouteView,
  type ReleaseExecutionRunClaim,
  type StuckReleaseClosureOutcome,
  type StuckReleaseClosurePreview,
  type StuckReleaseClosureResult,
  abandonReleaseExecutionRunClaim,
  listReleaseExecutionRunClaims,
} from '@/api/runtime'

const { t } = useI18n()
const authStore = useAuthStore()

const canAdmin = computed(() => authStore.canAccess(['SYSTEM_ADMIN']))
const loading = ref(false)
const submitting = ref(false)
const rows = ref<RuntimeExecutionUnknown[]>([])
const resolvedLoading = ref(false)
const resolvedRows = ref<RuntimeExecutionUnknown[]>([])
const detailVisible = ref(false)
const detailTarget = ref<RuntimeExecutionUnknown | null>(null)
const filter = reactive<{ serviceId: string; envId: string }>({ serviceId: '', envId: '' })

const resolveVisible = ref(false)
const resolveTarget = ref<RuntimeExecutionUnknown | null>(null)
const resolveForm = reactive<{
  outcome: RuntimeExecutionUnknownOutcome | ''
  note: string
  acknowledgeMergedFacts: boolean
}>({
  outcome: '',
  note: '',
  acknowledgeMergedFacts: false,
})

const recoverVisible = ref(false)
const recoverForm = reactive<{ releaseOrderId: string; rollbackOrderId: string; note: string }>({
  releaseOrderId: '',
  rollbackOrderId: '',
  note: '',
})

const claimsVisible = ref(false)
const claimsLoading = ref(false)
const claimsTarget = ref<RuntimeExecutionUnknown | null>(null)
const claimsRows = ref<ReleaseExecutionRunClaim[]>([])

const abandonVisible = ref(false)
const abandonTarget = ref<ReleaseExecutionRunClaim | null>(null)
const abandonForm = reactive<{ note: string }>({ note: '' })

const closureVisible = ref(false)
const closurePreviewing = ref(false)
const closurePreview = ref<StuckReleaseClosurePreview | null>(null)
const closureResult = ref<StuckReleaseClosureResult | null>(null)
const closureForm = reactive<{
  releaseOrderId: string
  outcome: StuckReleaseClosureOutcome | ''
  note: string
}>({
  releaseOrderId: '',
  outcome: '',
  note: '',
})

const closureRecordTotal = computed(() => {
  const preview = closurePreview.value
  if (!preview) return 0
  return (
    (preview.successRecordCount ?? 0) +
    (preview.failedRecordCount ?? 0) +
    (preview.unknownRecordCount ?? 0) +
    (preview.runningRecordCount ?? 0)
  )
})

/**
 * 两种结论各自被拒的理由合起来展示：只有一看就能提交的结论才不该出现“不可提交”。
 */
const closureBlockedReasons = computed(() => {
  const preview = closurePreview.value
  if (!preview) return ''
  const reasons = [
    ...(preview.adoptSuccessAllowed ? [] : preview.adoptSuccessRefusalReasons ?? []),
    ...(preview.closeAsFailedAllowed ? [] : preview.closeAsFailedRefusalReasons ?? []),
  ]
  return reasons.join('; ')
})

const closureOutcomeHint = computed(() => {
  if (closureForm.outcome === 'SUCCEEDED') return t('unknownExecutions.closureOutcomeSucceeded')
  if (closureForm.outcome === 'FAILED') return t('unknownExecutions.closureOutcomeFailed')
  return ''
})

/**
 * 缺失的库表：只有后端明确报 false 才列出。既有迁移线的旧库上这些入口按失败关闭拒绝，
 * 页面必须把它当成“现在做不到”的原因展示，不能省略成“没有影响”。
 */
const missingTables = computed(() => {
  const preview = closurePreview.value
  if (!preview) return [] as string[]
  const missing: string[] = []
  if (preview.unknownMarkerTableAvailable === false) missing.push('V31 runtime_execution_unknown')
  if (preview.claimTableAvailable === false) missing.push('V32 release_execution_run_claim')
  if (preview.instanceAttributionTableAvailable === false) missing.push('V34 runtime_deploy_record_instance')
  if (preview.mergeLedgerAvailable === false) missing.push('V34 runtime_execution_unknown_merge')
  return missing
})

/** 路径名称取自 i18n；遇到未知路径原样显示后端标签，不猜测。 */
const routeLabel = (route: ReleaseClosureRouteView) => {
  switch (route.route) {
    case 'ADOPT_SUCCESS':
      return t('unknownExecutions.closureRouteAdoptSuccess')
    case 'CLOSE_AS_FAILED':
      return t('unknownExecutions.closureRouteCloseAsFailed')
    case 'RESOLVE_AS_UNKNOWN':
      return t('unknownExecutions.closureRouteResolveAsUnknown')
    case 'RESOLVE_UNKNOWN_FACTS':
      return t('unknownExecutions.closureRouteResolveFacts')
    case 'ABANDON_CLAIM':
      return t('unknownExecutions.closureRouteAbandonClaim')
    default:
      return route.label || route.route || '-'
  }
}

const toOptionalNumber = (value: string): number | undefined => {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

const formatDate = (value?: string) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

/**
 * 失败提示只补一次。
 *
 * 响应拦截器已经用后端返回的 message（含 traceId）提示过 axios 错误，
 * 这里再弹一条泛化文案会把准确原因挤到后面；只有拦截器不会提示的错误
 * （HTTP 200 但 code != SUCCESS、没有响应的异常）才由页面负责提示。
 */
const showActionError = (error: unknown, fallbackKey: string) => {
  if ((error as { isAxiosError?: boolean })?.isAxiosError) {
    return
  }
  ElMessage.error(error instanceof Error && error.message ? error.message : t(fallbackKey))
}

const outcomeLabel = (outcome?: string) => {
  switch (outcome) {
    case 'REMOTE_EFFECT_APPLIED':
      return t('unknownExecutions.outcomeApplied')
    case 'REMOTE_EFFECT_NOT_APPLIED':
      return t('unknownExecutions.outcomeNotApplied')
    case 'REMOTE_EFFECT_PARTIAL':
      return t('unknownExecutions.outcomePartial')
    default:
      return outcome || '-'
  }
}

/**
 * 事实层级只区分“挂在订单上的兜底登记”和“挂在某条部署记录上的事实”，
 * 两者可以同时存在，必须逐条核对：解除其中一条不会让另一条消失。
 */
const factLevelLabel = (factLevel?: string) => {
  if (factLevel === 'ORDER_LEVEL') return t('unknownExecutions.factLevelOrder')
  if (factLevel === 'RECORD') return t('unknownExecutions.factLevelRecord')
  return factLevel || '-'
}

const idListText = (ids?: number[]) => (ids && ids.length ? ids.join(', ') : '-')

/** 只有后端判定的归属结论，页面照原样翻译；未知值原样显示，不猜测。 */
const attributionLabel = (attribution?: string) => {
  switch (attribution) {
    case 'ATTRIBUTED':
      return t('unknownExecutions.closureAttributionAttributed')
    case 'MISSING':
      return t('unknownExecutions.closureAttributionMissing')
    case 'AMBIGUOUS':
      return t('unknownExecutions.closureAttributionAmbiguous')
    case 'INSTANCE_REMOVED':
      return t('unknownExecutions.closureAttributionRemoved')
    case 'INSTANCE_REBOUND':
      return t('unknownExecutions.closureAttributionRebound')
    default:
      return attribution || '-'
  }
}

const mergedFactLabel = (fact: RuntimeExecutionUnknownMergedFact) =>
  t('unknownExecutions.mergeEntry', {
    batch: fact.batchId ?? t('unknownExecutions.closureNoBatch'),
    plan: fact.planId ?? t('unknownExecutions.closureNoBatch'),
    time: formatDate(fact.mergedAt),
  })

/**
 * “有合并”与“无法核实”是两件事：台账不可用时必须照“无法核实”展示，
 * 不能显示成 0，否则解除页面会把“查不到”读成“没有别的批次”。
 */
const hasMergedFacts = (row?: RuntimeExecutionUnknown | null) =>
  !!row && ((row.mergedFactCount ?? 0) > 0 || row.mergeLedgerAvailable === false)

const mergedFactsTooltip = (row: RuntimeExecutionUnknown) => {
  if (row.mergeLedgerAvailable === false) return t('unknownExecutions.mergeUnverifiableHint')
  const facts = row.mergedFacts ?? []
  return facts.length ? facts.map(mergedFactLabel).join('; ') : t('unknownExecutions.mergeUnverifiable')
}

const mergedFactsSummary = (row: RuntimeExecutionUnknown) => {
  if (row.mergeLedgerAvailable === false) return t('unknownExecutions.mergeUnverifiable')
  const facts = row.mergedFacts ?? []
  if (facts.length) return facts.map(mergedFactLabel).join('; ')
  return t('unknownExecutions.mergeCount', { count: row.mergedFactCount ?? 0 })
}

const currentFilter = () => ({
  serviceId: toOptionalNumber(filter.serviceId),
  envId: toOptionalNumber(filter.envId),
})

const loadData = async () => {
  loading.value = true
  try {
    rows.value = await listRuntimeExecutionUnknowns(currentFilter())
  } catch (error) {
    console.error('Failed to load unknown executions', error)
    showActionError(error, 'unknownExecutions.loadFailed')
  } finally {
    loading.value = false
  }
  resolvedLoading.value = true
  try {
    resolvedRows.value = await listResolvedRuntimeExecutionUnknowns(currentFilter())
  } catch (error) {
    console.error('Failed to load resolved unknown executions', error)
    showActionError(error, 'unknownExecutions.loadResolvedFailed')
  } finally {
    resolvedLoading.value = false
  }
}

const openDetail = (row: RuntimeExecutionUnknown) => {
  detailTarget.value = row
  detailVisible.value = true
}

const openResolve = (row: RuntimeExecutionUnknown) => {
  resolveTarget.value = row
  resolveForm.outcome = ''
  resolveForm.note = ''
  resolveForm.acknowledgeMergedFacts = false
  resolveVisible.value = true
}

const submitResolve = async () => {
  const target = resolveTarget.value
  if (!target) return
  if (!resolveForm.outcome) {
    ElMessage.warning(t('unknownExecutions.outcomeRequired'))
    return
  }
  if (hasMergedFacts(target) && !resolveForm.acknowledgeMergedFacts) {
    ElMessage.warning(t('unknownExecutions.mergeAcknowledgeRequired'))
    return
  }
  const note = resolveForm.note.trim()
  if (!note) {
    ElMessage.warning(t('unknownExecutions.noteRequired'))
    return
  }
  const payload: ResolveRuntimeExecutionUnknownPayload = {
    outcome: resolveForm.outcome,
    note,
    acknowledgeMergedFacts: resolveForm.acknowledgeMergedFacts,
  }
  submitting.value = true
  try {
    if (target.legacy) {
      if (!target.deployRecordId) {
        ElMessage.error(t('unknownExecutions.legacyMissingRecord'))
        return
      }
      await resolveLegacyRuntimeExecutionUnknown({ ...payload, deployRecordId: target.deployRecordId })
    } else if (target.id != null) {
      await resolveRuntimeExecutionUnknown(target.id, payload)
    } else {
      ElMessage.error(t('unknownExecutions.legacyMissingRecord'))
      return
    }
    ElMessage.success(t('unknownExecutions.resolveSuccess'))
    resolveVisible.value = false
    await loadData()
  } catch (error) {
    console.error('Failed to resolve unknown execution', error)
    showActionError(error, 'unknownExecutions.actionFailed')
  } finally {
    submitting.value = false
  }
}

const openRecover = () => {
  recoverForm.releaseOrderId = ''
  recoverForm.rollbackOrderId = ''
  recoverForm.note = ''
  recoverVisible.value = true
}

const submitRecover = async () => {
  const releaseOrderId = toOptionalNumber(recoverForm.releaseOrderId)
  const rollbackOrderId = toOptionalNumber(recoverForm.rollbackOrderId)
  if ((releaseOrderId == null) === (rollbackOrderId == null)) {
    ElMessage.warning(t('unknownExecutions.recoverOnlyOne'))
    return
  }
  const note = recoverForm.note.trim()
  if (!note) {
    ElMessage.warning(t('unknownExecutions.noteRequired'))
    return
  }
  const payload: RecoverStuckExecutionPayload = { note }
  if (releaseOrderId != null) payload.releaseOrderId = releaseOrderId
  if (rollbackOrderId != null) payload.rollbackOrderId = rollbackOrderId

  submitting.value = true
  try {
    const result = await recoverStuckExecution(payload)
    ElMessage.success(
      t('unknownExecutions.recoverSuccess', {
        recovered: result.runningRecordsRecovered,
        registered: result.unknownRecordsRegistered,
      }),
    )
    recoverVisible.value = false
    await loadData()
  } catch (error) {
    console.error('Failed to recover stuck execution', error)
    showActionError(error, 'unknownExecutions.actionFailed')
  } finally {
    submitting.value = false
  }
}

const openClaims = async (row: RuntimeExecutionUnknown) => {
  claimsTarget.value = row
  claimsRows.value = []
  claimsVisible.value = true
  if (row.releaseOrderId == null) return
  claimsLoading.value = true
  try {
    claimsRows.value = await listReleaseExecutionRunClaims(row.releaseOrderId)
  } catch (error) {
    console.error('Failed to load release execution claims', error)
    showActionError(error, 'unknownExecutions.claimsLoadFailed')
  } finally {
    claimsLoading.value = false
  }
}

const openAbandon = (claim: ReleaseExecutionRunClaim) => {
  abandonTarget.value = claim
  abandonForm.note = ''
  abandonVisible.value = true
}

/**
 * 放弃认领只是把“平台仍认为这次执行在飞”这条记录改成已释放：
 * 不连接目标主机、不重试、不回滚，也不解除任何未解除的结果未知事实。
 * 因此成功后额外提示一句，避免操作人把它读成远端已经停止。
 */
const submitAbandon = async () => {
  const target = abandonTarget.value
  if (!target) return
  const note = abandonForm.note.trim()
  if (!note) {
    ElMessage.warning(t('unknownExecutions.noteRequired'))
    return
  }
  submitting.value = true
  try {
    await abandonReleaseExecutionRunClaim(target.id, note)
    ElMessage.success(t('unknownExecutions.abandonSuccess'))
    abandonVisible.value = false
    if (claimsTarget.value) {
      await openClaims(claimsTarget.value)
    }
    await loadData()
  } catch (error) {
    console.error('Failed to abandon release execution claim', error)
    showActionError(error, 'unknownExecutions.actionFailed')
  } finally {
    submitting.value = false
  }
}

/**
 * 人工收口的是“订单、认领、流水线运行、部署记录四者口径对不上”的卡住发布单：
 * 预览只读，提交时后端会在锁内重新判定，因此这里展示的“可以提交”不是承诺。
 */
const openClosure = () => {
  closureForm.releaseOrderId = ''
  closureForm.outcome = ''
  closureForm.note = ''
  closurePreview.value = null
  closureResult.value = null
  closureVisible.value = true
}

const loadClosurePreview = async () => {
  const releaseOrderId = toOptionalNumber(closureForm.releaseOrderId)
  if (releaseOrderId == null) {
    ElMessage.warning(t('unknownExecutions.closureReleaseId'))
    return
  }
  closurePreviewing.value = true
  closureResult.value = null
  try {
    closurePreview.value = await previewStuckReleaseClosure(releaseOrderId)
    if (!closurePreview.value.adoptSuccessAllowed && closureForm.outcome === 'SUCCEEDED') {
      closureForm.outcome = ''
    }
    if (!closurePreview.value.closeAsFailedAllowed && closureForm.outcome === 'FAILED') {
      closureForm.outcome = ''
    }
  } catch (error) {
    console.error('Failed to preview stuck release closure', error)
    closurePreview.value = null
    showActionError(error, 'unknownExecutions.closurePreviewFailed')
  } finally {
    closurePreviewing.value = false
  }
}

const submitClosure = async () => {
  const releaseOrderId = toOptionalNumber(closureForm.releaseOrderId)
  if (releaseOrderId == null || !closurePreview.value) {
    ElMessage.warning(t('unknownExecutions.closureNeedPreview'))
    return
  }
  if (!closureForm.outcome) {
    ElMessage.warning(t('unknownExecutions.outcomeRequired'))
    return
  }
  const note = closureForm.note.trim()
  if (!note) {
    ElMessage.warning(t('unknownExecutions.noteRequired'))
    return
  }
  submitting.value = true
  try {
    closureResult.value = await closeStuckRelease(releaseOrderId, {
      outcome: closureForm.outcome,
      note,
    })
    if (closureResult.value.blockingRemains) {
      ElMessage.warning(t('unknownExecutions.closureBlockingRemains'))
    } else {
      ElMessage.success(
        t('unknownExecutions.closureSuccess', { conclusion: closureResult.value.conclusion ?? '-' }),
      )
    }
    await loadData()
  } catch (error) {
    console.error('Failed to close stuck release order', error)
    showActionError(error, 'unknownExecutions.actionFailed')
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.unknown-executions-view {
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

.page-subtitle {
  margin-top: 8px;
  max-width: 960px;
  color: var(--ih-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.permission-alert {
  margin-bottom: var(--space-md);
}

.table-card {
  margin-bottom: var(--space-md);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-sm);
}

.header-actions {
  display: flex;
  gap: 8px;
}

.card-title {
  font-size: var(--text-md);
  font-weight: 500;
  color: var(--color-ink);
}

.card-hint {
  flex: 1;
  margin-left: 16px;
  text-align: right;
  color: var(--ih-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.filter-bar {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}

.legacy-tag {
  margin-left: var(--space-2xs);
}

.empty-hint {
  padding: 24px 0;
  text-align: center;
  color: var(--ih-text-secondary);
}

.dialog-alert {
  margin-bottom: 12px;
}

.dialog-hint {
  margin-bottom: var(--space-md);
  color: var(--ih-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.text-secondary {
  color: var(--ih-text-secondary);
}

.closure-lookup {
  display: flex;
  gap: 8px;
  align-items: center;
}

.attribution-hint {
  margin-top: 4px;
  color: var(--ih-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.route-list {
  border: 1px solid var(--ih-border);
  border-radius: 4px;
  padding: 12px;
}

.route-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
}

.route-item {
  padding: 8px 0;
  border-top: 1px dashed var(--ih-border);
}

.route-item:first-of-type {
  border-top: none;
}

.route-head {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.route-state {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.route-flag {
  font-size: 12px;
  color: var(--ih-text-secondary);
}

.route-path {
  margin-top: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.route-reason,
.route-note {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ih-text-secondary);
}

.route-reasons {
  margin: 4px 0 0;
  padding-left: var(--space-md);
  font-size: 12px;
  line-height: 1.5;
  color: var(--ih-text-secondary);
}
</style>
