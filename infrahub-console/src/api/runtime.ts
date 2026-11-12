import { del, get, post } from '@/utils/http';
import type { PageResponse } from '@/types/api';

export interface RuntimeServiceInstance {
  id: number;
  serviceId: number;
  envId: number;
  hostId: number;
  versionId?: number;
  instanceName?: string;
  instanceType?: string;
  instanceStatus?: string;
  healthStatus?: string;
  startTime?: string;
  lastHeartbeat?: string;
  createdAt: string;
  updatedAt: string;
}

export interface RuntimeServiceInstanceQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  serviceId?: number;
  envId?: number;
  hostId?: number;
  instanceStatus?: string;
  healthStatus?: string;
}

export interface RuntimeServiceInstanceStatus {
  instanceId: number;
  serviceId: number;
  envId: number;
  hostId: number;
  versionId?: number;
  running: boolean;
  instanceStatus?: string;
  composeProject?: string;
  pid: string;
  portListening: boolean;
  healthStatus: string;
  healthBody: string;
  checkedAt: string;
  stdout: string;
  stderr: string;
  errorMessage: string;
}

export interface RuntimeServiceInstanceLogs {
  instanceId: number;
  serviceId: number;
  envId: number;
  hostId: number;
  versionId?: number;
  success: boolean;
  lines: number;
  logPath: string;
  content: string;
  stdout: string;
  stderr: string;
  errorMessage: string;
  fetchedAt: string;
}

export interface RuntimeServiceInstanceDeployConfig {
  instanceId: number;
  serviceId: number;
  envId: number;
  hostId: number;
  versionId?: number;
  deployRoot: string;
  deployPort: number;
  pidFile: string;
  logFile: string;
  healthUrl: string;
  source: 'CONFIG' | 'INFRAHUB_DEFAULT' | string;
}

export interface RuntimeInstanceResourceView {
  instanceId: number;
  instanceName?: string;
  instanceStatus?: string;
  healthStatus?: string;
  serviceId?: number;
  serviceName?: string;
  envId?: number;
  envName?: string;
  hostId?: number;
  hostname?: string;
  ip?: string;
  resourcePoolId?: number;
  resourcePoolName?: string;
  currentVersionId?: number;
  currentVersionNo?: string;
  latestDeployRecordId?: number;
  latestDeployStatus?: string;
  latestDeployAt?: string;
  latestMonitorSnapshotAt?: string;
  monitorStatus?: string;
}

export function listRuntimeServiceInstances(params: { serviceId: number; envId: number }) {
  return get<RuntimeServiceInstance[]>('/api/runtime/service-instances', params);
}

export function pageRuntimeServiceInstances(params: RuntimeServiceInstanceQuery) {
  return get<PageResponse<RuntimeServiceInstance>>('/api/runtime/service-instances/page', params);
}

export function getRuntimeServiceInstanceStatus(id: number | string) {
  return get<RuntimeServiceInstanceStatus>(`/api/runtime/service-instances/${id}/status`);
}

export function getRuntimeServiceInstanceLogs(id: number | string, lines = 200) {
  return get<RuntimeServiceInstanceLogs>(`/api/runtime/service-instances/${id}/logs`, { lines });
}

export function getRuntimeServiceInstanceDeployConfig(id: number | string) {
  return get<RuntimeServiceInstanceDeployConfig>(`/api/runtime/service-instances/${id}/deploy-config`);
}

export function getRuntimeServiceInstanceResourceView(id: number | string) {
  return get<RuntimeInstanceResourceView>(`/api/runtime/service-instances/${id}/resource-view`);
}

// ---- 单实例生命周期操作 ----

/**
 * 停止结果。`outcome` 是唯一可据以判断"是否已停止"的字段。
 * 当前版本后端一律返回 IDENTITY_UNCONFIRMED：没有发送任何停止信号。
 */
export type RuntimeInstanceStopOutcome =
  | 'STOPPED'
  | 'IDENTITY_UNCONFIRMED'
  | 'UNSUPPORTED'
  | 'REFUSED'
  | 'FAILED'
  | 'UNKNOWN'

export interface RuntimeInstanceStopResult {
  instanceId: number;
  instanceName?: string;
  serviceId?: number;
  envId?: number;
  hostId?: number;
  instanceType?: string;
  outcome: RuntimeInstanceStopOutcome | string;
  /** 结论的细分原因（固定枚举），不改变 outcome，也不代表已发送信号 */
  reasonCode?: string;
  message: string;
  checkedAt?: string;
}

export interface RuntimeInstanceRemovalResult {
  instanceId: number;
  instanceName?: string;
  serviceId?: number;
  envId?: number;
  hostId?: number;
  removed: boolean;
  /** 恒为 false：移除登记不等于停止进程 */
  processStopped: boolean;
  lastRecordedInstanceStatus?: string;
  lastRecordedHealthStatus?: string;
  collectionStateRemoved: boolean;
  message: string;
  removedAt?: string;
}

export function stopRuntimeServiceInstance(id: number | string) {
  return post<RuntimeInstanceStopResult>(`/api/runtime/service-instances/${id}/stop`);
}

export function deregisterRuntimeServiceInstance(id: number | string) {
  return del<RuntimeInstanceRemovalResult>(`/api/runtime/service-instances/${id}`);
}

// ---- 结果未知执行：阻断、核对与人工解除 ----

/**
 * 一条“远端结果无法证明”的执行事实。
 *
 * `legacy=true` 表示它来自历史 UNKNOWN 部署记录、尚无登记行，解除时必须按 `deployRecordId` 操作。
 * 平台不会自动连接目标主机验证，`resolution*` 只由 SYSTEM_ADMIN 的人工核对写入。
 */
export interface RuntimeExecutionUnknown {
  id?: number;
  serviceId: number;
  envId: number;
  sourceType: 'RELEASE' | 'ROLLBACK' | string;
  releaseOrderId?: number;
  rollbackOrderId?: number;
  deployRecordId?: number;
  planId?: number;
  batchId?: number;
  status: string;
  detectionSource: string;
  detectionReason: string;
  detectedAt?: string;
  resolutionOutcome?: string;
  resolutionNote?: string;
  resolvedBy?: number;
  resolvedAt?: string;
  legacy: boolean;
  deployStatus?: string;
  /**
   * 事实层级：ORDER_LEVEL 表示没有部署记录、只挂在订单上的兜底登记，
   * RECORD 表示挂在某条具体部署记录上。同一发布单可以同时有两条，必须逐条核对、逐条解除：
   * 解除其中一条不会让另一条消失。
   */
  factLevel?: string;
  /**
   * 这条订单级事实**吸收**了多少条别的批次登记（去重粒度导致：同一发布单下不同批次的订单级
   * 登记可能落到同一行）。解除这一条会把它们一并放行，因此解除前必须看到数量与明细。
   */
  mergedFactCount?: number;
  mergedFacts?: RuntimeExecutionUnknownMergedFact[];
  /**
   * 合并台账是否可用（V34 是否已应用）。为 false 时 `mergedFactCount` / `mergedFacts` 不是
   * “没有合并”，而是“无法核实”，页面必须照此表述。
   */
  mergeLedgerAvailable?: boolean;
}

/** 被合并的登记：哪一次登记（来源、批次）没有独立留存。 */
export interface RuntimeExecutionUnknownMergedFact {
  unknownId?: number;
  sourceType?: string;
  releaseOrderId?: number;
  rollbackOrderId?: number;
  planId?: number;
  batchId?: number;
  detectionSource?: string;
  mergedAt?: string;
}

export type RuntimeExecutionUnknownOutcome =
  | 'REMOTE_EFFECT_APPLIED'
  | 'REMOTE_EFFECT_NOT_APPLIED'
  | 'REMOTE_EFFECT_PARTIAL'

export interface ResolveRuntimeExecutionUnknownPayload {
  outcome: RuntimeExecutionUnknownOutcome;
  note: string;
  /**
   * 显式确认“解除会同时放行这条事实名下被合并的其他批次登记”。
   * 有已留证的合并、或合并台账不可核实时必须为 true，否则后端拒绝解除。
   */
  acknowledgeMergedFacts?: boolean;
}

export interface ResolveLegacyRuntimeExecutionUnknownPayload
  extends ResolveRuntimeExecutionUnknownPayload {
  deployRecordId: number;
}

export interface RecoverStuckExecutionPayload {
  releaseOrderId?: number;
  rollbackOrderId?: number;
  note: string;
}

export interface RecoverStuckExecutionResult {
  targetType: 'RELEASE' | 'ROLLBACK' | string;
  targetId: number;
  runningRecordsRecovered: number;
  unknownRecordsRegistered: number;
  message: string;
}

export function listRuntimeExecutionUnknowns(params: {
  serviceId?: number;
  envId?: number;
}) {
  return get<RuntimeExecutionUnknown[]>('/api/runtime/unknown-executions', params);
}

/**
 * 已解除的结果未知执行（只读历史）。
 * `resolutionOutcome` 是当时操作人给出的人工结论，不是平台对远端状态的验证结果。
 */
export function listResolvedRuntimeExecutionUnknowns(params: {
  serviceId?: number;
  envId?: number;
}) {
  return get<RuntimeExecutionUnknown[]>('/api/runtime/unknown-executions/resolved', params);
}

export function getRuntimeExecutionUnknown(id: number | string) {
  return get<RuntimeExecutionUnknown>(`/api/runtime/unknown-executions/${id}`);
}

export function resolveRuntimeExecutionUnknown(
  id: number | string,
  payload: ResolveRuntimeExecutionUnknownPayload,
) {
  return post<RuntimeExecutionUnknown>(`/api/runtime/unknown-executions/${id}/resolve`, payload);
}

export function resolveLegacyRuntimeExecutionUnknown(
  payload: ResolveLegacyRuntimeExecutionUnknownPayload,
) {
  return post<RuntimeExecutionUnknown>('/api/runtime/unknown-executions/legacy/resolve', payload);
}

export function recoverStuckExecution(payload: RecoverStuckExecutionPayload) {
  return post<RecoverStuckExecutionResult>('/api/runtime/unknown-executions/recover', payload);
}

/**
 * 收口前对账（只读）：卡住的发布单在订单 / 认领 / 流水线运行 / 部署记录 / 未知事实各是什么状态，
 * 以及“人工声明成功”与“人工声明失败”会不会被接受、被什么挡住。
 *
 * 预览不加锁，只是核对材料：收口时后端会在服务行锁与订单行锁内重新判定，
 * 因此预览结论与最终结果可能不同。平台不连接远端、不重试、不回滚，也不从超时推断远端已停止。
 */
export interface StuckReleaseClosurePreview {
  releaseOrderId?: number;
  orderStatus?: string;
  serviceId?: number;
  envId?: number;
  versionId?: number;
  claim?: StuckReleaseClosureClaimView | null;
  pipelineRun?: StuckReleaseClosureRunView | null;
  deployRecords?: StuckReleaseClosureRecordView[];
  runningRecordCount?: number;
  successRecordCount?: number;
  failedRecordCount?: number;
  unknownRecordCount?: number;
  recordCountWithoutInstanceAttribution?: number;
  provenInstanceIds?: number[];
  currentInstanceIdsNotDeployed?: number[];
  instanceAttributionProvable?: boolean;
  instanceAttributionRejectionReason?: string | null;
  unresolvedFactCountForOrder?: number;
  unresolvedFactCountForServiceEnv?: number;
  heldClaimOnServiceEnv?: boolean;
  activeCanaryPlan?: boolean;
  adoptSuccessAllowed?: boolean;
  adoptSuccessRefusalReasons?: string[];
  closeAsFailedAllowed?: boolean;
  closeAsFailedRefusalReasons?: string[];
  /** 各条可选路径的准入判定，含“现在能不能走”与逐条阻碍原因。 */
  routes?: ReleaseClosureRouteView[];
  /** 是否存在现在就能提交的路径。 */
  anyRouteAvailable?: boolean;
  /** 一条可执行路径都没有时的明确说明：为什么没有、订单会怎样。有路径时为空。 */
  noRouteReason?: string | null;
  /** 一次性执行认领表是否可用（V32）。false 时不存在认领行，也没有“放弃认领”这个动作。 */
  claimTableAvailable?: boolean;
  /** 结果未知登记表是否可用（V31）。false 时登记/恢复/解除都按失败关闭拒绝。 */
  unknownMarkerTableAvailable?: boolean;
  /** 实例归属表是否可用（V34）。false 时任何成功结论都无法证明落在哪些实例上。 */
  instanceAttributionTableAvailable?: boolean;
  /** 未知事实合并台账是否可用（V34）。false 时无法核实订单级事实是否吸收了其他批次。 */
  mergeLedgerAvailable?: boolean;
  disclaimer?: string;
}

/** 卡住发布单的一条可选路径：能不能走、被什么挡住、入口在哪。 */
export interface ReleaseClosureRouteView {
  /** ADOPT_SUCCESS / CLOSE_AS_FAILED / RESOLVE_AS_UNKNOWN / RESOLVE_UNKNOWN_FACTS / ABANDON_CLAIM。 */
  route?: string;
  label?: string;
  available?: boolean;
  blockingReasons?: string[];
  /** 平台入口；不可用时为 null（后端不给不可执行的引导）。 */
  actionPath?: string | null;
  /** 该路径的正确性依赖操作人在平台之外核对远端事实。 */
  requiresRemoteManualCheck?: boolean;
  /** 现在不可用，但条件会自行变化（等执行时间窗过去即可重试）。 */
  retryableLater?: boolean;
  note?: string | null;
}

export interface StuckReleaseClosureClaimView {
  claimId?: number;
  status?: string;
  releaseOutcome?: string;
  pipelineRunId?: number;
  pipelineRunStatus?: string;
  claimedAt?: string;
  releasedAt?: string;
}

export interface StuckReleaseClosureRunView {
  pipelineRunId?: number;
  runStatus?: string;
  startTime?: string;
  endTime?: string;
}

export interface StuckReleaseClosureRecordView {
  deployRecordId?: number;
  deployStatus?: string;
  deployBatch?: number;
  startedAt?: string;
  endedAt?: string;
  instanceId?: number;
  instanceName?: string;
  /** ATTRIBUTED / MISSING / AMBIGUOUS / INSTANCE_REMOVED / INSTANCE_REBOUND。 */
  instanceAttribution?: string;
}

export type StuckReleaseClosureOutcome = 'SUCCEEDED' | 'FAILED';

export interface StuckReleaseClosurePayload {
  outcome: StuckReleaseClosureOutcome;
  /** 人工核对结论与依据（核对了什么、依据来自哪里）；不保存凭据或远端输出。 */
  note: string;
}

export interface StuckReleaseClosureResult {
  target?: string;
  releaseOrderId?: number;
  outcome?: string;
  orderStatus?: string;
  claimId?: number;
  claimStatus?: string;
  claimReleaseOutcome?: string;
  pipelineRunId?: number;
  pipelineRunStatus?: string;
  updatedInstanceIds?: number[];
  currentInstanceIdsNotDeployed?: number[];
  deployRecordCount?: number;
  unresolvedFactCountForOrder?: number;
  unresolvedFactCountForServiceEnv?: number;
  /** true 表示未解除的未知事实仍在，服务/环境并没有因为收口而被放行。 */
  blockingRemains?: boolean;
  /** 收口之后仍然可执行的路径（订单已终态，通常只剩“逐条解除未知事实”）。 */
  remainingRoutes?: ReleaseClosureRouteView[];
  anyRemainingRouteAvailable?: boolean;
  /** 收口之后的下一步说明：还有什么必须做、订单现在是什么状态。 */
  nextStepSummary?: string | null;
  conclusion?: string;
  disclaimer?: string;
}

export function previewStuckReleaseClosure(releaseOrderId: number | string) {
  return get<StuckReleaseClosurePreview>(
    `/api/runtime/unknown-executions/stuck-release/${releaseOrderId}/closure-preview`,
  );
}

/**
 * 受控人工收口一个卡住的发布单。
 *
 * `outcome` 是操作人的结论：`SUCCEEDED` 只在本平台能证明“这次执行产出的部署记录全部成功、
 * 实例归属可验证”时被接受；`FAILED` 不解除任何未知事实（结果里的 `blockingRemains` 说明阻断是否仍在）。
 */
export function closeStuckRelease(
  releaseOrderId: number | string,
  payload: StuckReleaseClosurePayload,
) {
  return post<StuckReleaseClosureResult>(
    `/api/runtime/unknown-executions/stuck-release/${releaseOrderId}/closure`,
    payload,
  );
}

/**
 * 发布执行认领（一次性执行凭证）。
 *
 * `status === 'HELD'` 表示这次执行仍由平台持有执行权，该发布单的后续部署会被它挡住；
 * `releaseOutcome === 'ABANDONED'` 是操作人放弃认领的结果，不代表远端已停止，
 * 也不代表执行结果已经确定。
 */
export interface ReleaseExecutionRunClaim {
  id: number;
  releaseOrderId: number;
  serviceId: number;
  envId?: number;
  claimPurpose: string;
  holderKind: string;
  pipelineRunId?: number;
  pipelineStepRunId?: number;
  rollbackOrderId?: number;
  status: string;
  releaseOutcome?: string;
  releaseReason?: string;
  releasedBy?: number;
  claimedAt?: string;
  releasedAt?: string;
  blocking: boolean;
}

export function listReleaseExecutionRunClaims(releaseOrderId: number | string) {
  return get<ReleaseExecutionRunClaim[]>('/api/release-execution-runs/claims', { releaseOrderId });
}

/**
 * 放弃一条仍为 HELD 的认领。
 *
 * 只表达“平台不再认为这次执行在飞”：它不连接目标主机、不重试、不回滚，
 * 也不解除任何未解除的结果未知事实。远端是否已停止仍需人工核对。
 */
export function abandonReleaseExecutionRunClaim(id: number | string, note: string) {
  return post<ReleaseExecutionRunClaim>(`/api/release-execution-runs/claims/${id}/abandon`, { note });
}
