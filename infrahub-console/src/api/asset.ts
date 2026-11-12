import { get, post, put } from '@/utils/http';
import type { PageResponse } from '@/types/api';

export interface ResourcePool {
  id: number;
  poolCode: string;
  poolName: string;
  poolType: string;
  region?: string;
  totalCpu: number;
  totalMemoryGb: number;
  totalDiskGb: number;
  usedCpu: number;
  usedMemoryGb: number;
  usedDiskGb: number;
  status: string;
  createdAt: string;
}

export interface ResourcePoolQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  poolType?: string;
  status?: string;
}

export interface ResourcePoolCapacity {
  resourcePoolId: number;
  resourcePoolName: string;
  poolCode: string;
  envId?: number;
  envName?: string;
  hostCount: number;
  onlineHostCount: number;
  offlineHostCount: number;
  unknownHostCount: number;
  runtimeInstanceCount: number;
  cpuTotal?: number;
  cpuUsed?: number;
  cpuUsagePercent?: number;
  memoryTotalGb?: number;
  memoryUsedGb?: number;
  memoryUsagePercent?: number;
  diskTotalGb?: number;
  diskUsedGb?: number;
  diskUsagePercent?: number;
}

export interface AssetHost {
  id: number;
  hostname: string;
  ip: string;
  osType: string;
  cpuCores: number;
  memoryGb: number;
  diskGb: number;
  hostType: string;
  hostStatus: string;
  envId?: number;
  resourcePoolId?: number;
  agentStatus?: string;
  lastHeartbeat?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AssetHostQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  hostStatus?: string;
  agentStatus?: string;
  envId?: number;
  resourcePoolId?: number;
}

export function pageResourcePools(params: ResourcePoolQuery) {
  return get<PageResponse<ResourcePool>>('/api/assets/resource-pools', params);
}

export function getResourcePoolCapacity(id: number) {
  return get<ResourcePoolCapacity>(`/api/assets/resource-pools/${id}/capacity`);
}

export function pageAssetHosts(params: AssetHostQuery) {
  return get<PageResponse<AssetHost>>('/api/assets/hosts', params);
}

export interface CreateAssetHostPayload {
  hostname: string;
  ip: string;
  envId: number;
  resourcePoolId?: number;
  osType: string;
  hostType: string;
  cpuCores?: number;
  memoryGb?: number;
  diskGb?: number;
}

/**
 * 手动登记主机：只写资产台账。
 *
 * 不连接服务器、不安装 Agent、不创建运行实例、不写心跳时间；新主机以 hostStatus=UNKNOWN /
 * agentStatus=UNKNOWN 入库，不默认声称在线或健康。CPU/内存/磁盘可不填（后端按 0 存，页面显示"未填写"）。
 */
export function createAssetHost(payload: CreateAssetHostPayload) {
  return post<AssetHost>('/api/assets/hosts', payload);
}

export interface HostProfile {
  hostId: number;
  hostname: string;
  ip: string;
  envId?: number;
  envName?: string;
  resourcePoolId?: number;
  resourcePoolName?: string;
  runtimeInstanceCount: number;
  latestDeployAt?: string;
  latestDeployStatus?: string;
  latestMonitorSnapshotAt?: string;
  healthStatus: 'ONLINE' | 'OFFLINE' | 'DEGRADED' | 'UNKNOWN';
  healthReason: string;
}

export function getHostProfile(id: number) {
  return get<HostProfile>(`/api/assets/hosts/${id}/profile`);
}

// Resource Governance Records
export interface ResourceGovernanceRecord {
  id: number;
  eventType: string;
  resourcePoolId?: number;
  resourcePoolName?: string;
  hostId?: number;
  hostname?: string;
  runtimeInstanceId?: number;
  instanceName?: string;
  releaseOrderId?: number;
  releaseOrderNo?: string;
  rollbackOrderId?: number;
  rollbackOrderNo?: string;
  executorType?: string;
  envId?: number;
  envName?: string;
  serviceId?: number;
  serviceName?: string;
  versionId?: number;
  versionNo?: string;
  subjectType?: string;
  subjectId?: number;
  actionPlanNo?: string;
  actionExecutionNo?: string;
  suggestionNo?: string;
  beforePayload?: string;
  afterPayload?: string;
  resultStatus: string;
  reason?: string;
  createdBy?: string;
  createdAt: string;
}

export interface ResourceGovernanceRecordQuery {
  pageNum?: number;
  pageSize?: number;
  eventType?: string;
  envId?: number;
  resourcePoolId?: number;
  hostId?: number;
  serviceId?: number;
  resultStatus?: string;
}

export interface ResourceGovernanceRecordPageResponse {
  records: ResourceGovernanceRecord[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export function listResourceGovernanceRecords(params: ResourceGovernanceRecordQuery) {
  return get<ResourceGovernanceRecordPageResponse>('/api/assets/resource-governance/records', params);
}

// Resource Pool Risk Assessment (H-D)
export type RiskLevel = 'CRITICAL' | 'WARNING' | 'NORMAL' | 'UNKNOWN';

export interface ResourcePoolRisk {
  resourcePoolId: number;
  resourcePoolName: string;
  poolCode: string;
  envId?: number;
  envName?: string;
  riskLevel: RiskLevel;
  riskScore?: number;
  riskReasons: string[];
  cpuUsagePercent?: number;
  memoryUsagePercent?: number;
  diskUsagePercent?: number;
  onlineHostCount: number;
  offlineHostCount: number;
  unknownHostCount: number;
  evaluatedAt?: string;
}

export interface ResourcePoolRiskQuery {
  envId?: number;
  riskLevel?: RiskLevel;
  resourcePoolId?: number;
  pageNum?: number;
  pageSize?: number;
}

export interface ResourcePoolRiskPageResponse {
  records: ResourcePoolRisk[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export function pageResourcePoolRisks(params: ResourcePoolRiskQuery) {
  return get<ResourcePoolRiskPageResponse>('/api/assets/resource-pools/risks', params);
}

export function getResourcePoolRisk(id: number) {
  return get<ResourcePoolRisk>(`/api/assets/resource-pools/${id}/risk`);
}

// Capacity Policy
export type CapacityMetricType = 'CPU' | 'MEMORY' | 'DISK' | 'HOST_OFFLINE' | 'UNKNOWN_HOST';

export interface CapacityPolicy {
  id: number;
  policyName: string;
  envId?: number;
  resourcePoolId?: number;
  metricType: CapacityMetricType;
  warningThreshold: number;
  criticalThreshold: number;
  enabled: boolean;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CapacityPolicyPayload {
  policyName: string;
  envId?: number;
  resourcePoolId?: number;
  metricType: CapacityMetricType;
  warningThreshold: number;
  criticalThreshold: number;
  enabled?: boolean;
  description?: string;
}

export interface CapacityPolicyQuery {
  pageNum?: number;
  pageSize?: number;
  policyName?: string;
  envId?: number;
  resourcePoolId?: number;
  metricType?: CapacityMetricType;
  enabled?: boolean;
}

export function listCapacityPolicies(params: CapacityPolicyQuery) {
  return get<PageResponse<CapacityPolicy>>('/api/assets/capacity-policies', params);
}

export function getCapacityPolicy(id: number) {
  return get<CapacityPolicy>(`/api/assets/capacity-policies/${id}`);
}

export function createCapacityPolicy(data: CapacityPolicyPayload) {
  return post<CapacityPolicy>('/api/assets/capacity-policies', data);
}

export function updateCapacityPolicy(id: number, data: CapacityPolicyPayload) {
  return put<CapacityPolicy>(`/api/assets/capacity-policies/${id}`, data);
}

export function toggleCapacityPolicy(id: number, enabled: boolean) {
  return post<CapacityPolicy>(`/api/assets/capacity-policies/${id}/toggle?enabled=${enabled}`);
}

// Governance Suggestions (H+1-C)
export type SuggestionStatus = 'OPEN' | 'ACKED' | 'IGNORED' | 'RESOLVED';
export type SuggestionType = 'SCALE_OUT' | 'CHECK_HOST' | 'CLEANUP_INSTANCE' | 'INVESTIGATE_CAPACITY';
export type SourceType = 'RESOURCE_POOL_RISK';

export interface GovernanceSuggestion {
  id: number;
  suggestionNo: string;
  suggestionType: SuggestionType;
  sourceType: SourceType;
  sourceId?: number;
  resourcePoolId: number;
  resourcePoolName: string;
  envId?: number;
  envName?: string;
  riskLevel: RiskLevel;
  title: string;
  description?: string;
  status: SuggestionStatus;
  reason?: string;
  createdBy?: string;
  createdAt: string;
  handledBy?: string;
  handledAt?: string;
  handleNote?: string;
  existingPlan?: GovernanceActionPlan;
}

export interface GovernanceSuggestionQuery {
  resourcePoolId?: number;
  envId?: number;
  riskLevel?: RiskLevel;
  suggestionType?: SuggestionType;
  status?: SuggestionStatus;
  pageNum?: number;
  pageSize?: number;
}

export interface GovernanceSuggestionPageResponse {
  records: GovernanceSuggestion[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface HandleSuggestionPayload {
  handleNote: string;
}

export function pageGovernanceSuggestions(params: GovernanceSuggestionQuery) {
  return get<GovernanceSuggestionPageResponse>('/api/assets/governance-suggestions', params);
}

export function getGovernanceSuggestion(id: number) {
  return get<GovernanceSuggestion>(`/api/assets/governance-suggestions/${id}`);
}

export function generateSuggestions(resourcePoolId: number) {
  return post<GovernanceSuggestion[]>(`/api/assets/resource-pools/${resourcePoolId}/suggestions/generate`);
}

export function ackGovernanceSuggestion(id: number) {
  return post<GovernanceSuggestion>(`/api/assets/governance-suggestions/${id}/ack`);
}

export function ignoreGovernanceSuggestion(id: number, data: HandleSuggestionPayload) {
  return post<GovernanceSuggestion>(`/api/assets/governance-suggestions/${id}/ignore`, data);
}

export function resolveGovernanceSuggestion(id: number, data: HandleSuggestionPayload) {
  return post<GovernanceSuggestion>(`/api/assets/governance-suggestions/${id}/resolve`, data);
}

// Governance Action Plans (I-C)
export type ActionPlanStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED' | 'EXECUTED' | 'FAILED';
export type ActionType = 'SCALE_OUT_PLAN' | 'CHECK_HOST_PLAN' | 'INVESTIGATE_CAPACITY_PLAN' | 'CLEANUP_INSTANCE_PLAN';
export type TargetType = 'RESOURCE_POOL' | 'HOST' | 'RUNTIME_INSTANCE' | 'ENVIRONMENT' | 'NONE';

export interface GovernanceActionPlan {
  id: number;
  planNo: string;
  suggestionId: number;
  suggestionNo?: string;
  actionType: ActionType;
  targetType: TargetType;
  status: ActionPlanStatus;
  riskLevel: RiskLevel;
  resourcePoolId: number;
  resourcePoolName: string;
  envId?: number;
  envName?: string;
  planPayload: string | Record<string, unknown>;
  cancelReason?: string;
  createdBy?: string;
  createdAt: string;
  confirmedBy?: string;
  confirmedAt?: string;
  cancelledBy?: string;
  cancelledAt?: string;
}

export interface GovernanceActionPlanQuery {
  suggestionId?: number;
  resourcePoolId?: number;
  envId?: number;
  status?: ActionPlanStatus;
  actionType?: ActionType;
  pageNum?: number;
  pageSize?: number;
}

export interface GovernanceActionPlanPageResponse {
  records: GovernanceActionPlan[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CancelActionPlanPayload {
  cancelReason: string;
}

export function pageGovernanceActionPlans(params: GovernanceActionPlanQuery) {
  return get<GovernanceActionPlanPageResponse>('/api/assets/governance-action-plans', params);
}

export function getGovernanceActionPlan(id: number) {
  return get<GovernanceActionPlan>(`/api/assets/governance-action-plans/${id}`);
}

export function createActionPlanFromSuggestion(suggestionId: number) {
  return post<GovernanceActionPlan>(`/api/assets/governance-suggestions/${suggestionId}/action-plan`);
}

export function confirmGovernanceActionPlan(id: number) {
  return post<GovernanceActionPlan>(`/api/assets/governance-action-plans/${id}/confirm`);
}

export function cancelGovernanceActionPlan(id: number, data: CancelActionPlanPayload) {
  return post<GovernanceActionPlan>(`/api/assets/governance-action-plans/${id}/cancel`, data);
}

// Governance Action Executions (J-C)
export type ActionExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

export interface GovernanceActionExecution {
  id: number;
  executionNo: string;
  planId: number;
  planNo?: string;
  status: ActionExecutionStatus;
  executionMode: 'MANUAL_ONLY';
  operator?: string;
  startedAt?: string;
  finishedAt?: string;
  resultSummary?: string;
  failureReason?: string;
  evidencePayload?: string | Record<string, unknown>;
  createdBy?: string;
  createdAt: string;
}

export interface GovernanceActionExecutionQuery {
  pageNum?: number;
  pageSize?: number;
  planId?: number;
  status?: ActionExecutionStatus;
}

export interface GovernanceActionExecutionPageResponse {
  records: GovernanceActionExecution[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CompleteActionExecutionPayload {
  resultSummary: string;
  evidencePayload?: Record<string, unknown>;
}

export interface FailActionExecutionPayload {
  failureReason: string;
  evidencePayload?: Record<string, unknown>;
}

export interface CancelActionExecutionPayload {
  cancelReason: string;
}

export function listActionExecutions(planId: number, params?: GovernanceActionExecutionQuery) {
  return get<GovernanceActionExecutionPageResponse>(`/api/assets/governance-action-plans/${planId}/executions`, params);
}

export function startActionExecution(planId: number) {
  return post<GovernanceActionExecution>(`/api/assets/governance-action-plans/${planId}/executions/start`);
}

export function getActionExecution(id: number) {
  return get<GovernanceActionExecution>(`/api/assets/governance-action-executions/${id}`);
}

export function completeActionExecution(id: number, data: CompleteActionExecutionPayload) {
  return post<GovernanceActionExecution>(`/api/assets/governance-action-executions/${id}/complete`, data);
}

export function failActionExecution(id: number, data: FailActionExecutionPayload) {
  return post<GovernanceActionExecution>(`/api/assets/governance-action-executions/${id}/fail`, data);
}

export function cancelActionExecution(id: number, data: CancelActionExecutionPayload) {
  return post<GovernanceActionExecution>(`/api/assets/governance-action-executions/${id}/cancel`, data);
}

// Timeline Types (K-C)
export type ImpactType = 'RESOURCE_POOL' | 'HOST' | 'RUNTIME_INSTANCE' | 'ENVIRONMENT' | 'NONE';
export type TimelineSubjectType = 'ACTION_PLAN' | 'ACTION_EXECUTION' | 'SUGGESTION' | 'RESOURCE_POOL' | 'HOST' | 'RUNTIME_INSTANCE';
export type TimelineEventType =
  | 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED'
  | 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED'
  | 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_FAILED'
  | 'RESOURCE_GOVERNANCE_ACTION_EXECUTION_CANCELLED'
  | 'RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED';
export type TimelineNodeStatus = 'pending' | 'running' | 'success' | 'failed';

export interface TimelineNodeDto {
  key: string;
  title: string;
  status: TimelineNodeStatus;
  time?: string;
  summary?: string;
  actionType?: string;
  // IMPACT_RECORDED event fields (per Contract 3.1)
  beforePayload?: Record<string, unknown>;
  afterPayload?: Record<string, unknown>;
  evidencePayload?: Record<string, unknown>;
  impactPayload?: Record<string, unknown>;
  impactType?: ImpactType;
  impactSummary?: string;
  resultStatus?: string;
}

export interface TimelineResponse {
  subjectType: TimelineSubjectType;
  subjectId: number;
  subjectNo: string;
  nodes: TimelineNodeDto[];
  totalCount: number;
}

export interface TimelineQueryParams {
  subjectType?: TimelineSubjectType;
  subjectId?: number;
  actionPlanId?: number;
  actionExecutionId?: number;
  suggestionId?: number;
  eventType?: TimelineEventType;
  startTime?: string;
  endTime?: string;
  pageNum?: number;
  pageSize?: number;
}

export interface GovernanceRecordSummaryResponse {
  resourcePoolId: number;
  resourcePoolName: string;
  totalExecutions: number;
  successExecutions: number;
  failedExecutions: number;
  cancelledExecutions: number;
  avgDurationMs: number;
  lastExecutionAt?: string;
  impactByType: Record<string, number>;
}

export interface ImpactRecord {
  id: number;
  actionExecutionId: number;
  actionExecutionNo: string;
  actionPlanId: number;
  actionPlanNo: string;
  suggestionId?: number;
  suggestionNo?: string;
  impactType: ImpactType;
  impactSummary: string;
  impactPayload?: Record<string, unknown>;
  evidencePayload?: Record<string, unknown>;
  resultStatus: string;
  operatorId?: number;
  operatorName?: string;
  recordedAt: string;
}

// Timeline API functions
export function getActionPlanTimeline(id: number) {
  return get<TimelineResponse>(`/api/assets/governance-action-plans/${id}/timeline`);
}

export function getActionExecutionTimeline(id: number) {
  return get<TimelineResponse>(`/api/assets/governance-action-executions/${id}/timeline`);
}

export function listGovernanceRecordTimeline(params?: TimelineQueryParams) {
  return get<PageResponse<TimelineResponse>>('/api/assets/resource-governance/records/timeline', params);
}

// Governance Ops API (L-C) - Following Contract v2-stage-l-contract.md

// OpsInterval enum (Contract 5.1)
export type OpsInterval = 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';

// ReviewItemType enum (Contract 5.2)
export type ReviewItemType = 'RISK' | 'SUGGESTION' | 'ACTION_PLAN' | 'EXECUTION' | 'IMPACT';

// GovernanceOpsOverviewResponse (Contract 4.1)
export interface GovernanceOpsOverviewResponse {
  totalRiskCount: number;
  criticalRiskCount: number;
  warningRiskCount: number;
  openSuggestionCount: number;
  confirmedPlanCount: number;
  runningExecutionCount: number;
  completedExecutionCount: number;
  failedExecutionCount: number;
  impactRecordedCount: number;
  avgCloseDurationMs: number;
  manualOnly: boolean;
}

// GovernanceTrendPoint (Contract 4.2)
export interface GovernanceTrendPoint {
  timeBucket: string;
  riskCount: number;
  suggestionCount: number;
  actionPlanCount: number;
  executionCount: number;
  impactCount: number;
  successCount: number;
  failedCount: number;
}

// GovernanceSlaResponse (Contract 4.3)
export interface GovernanceSlaResponse {
  avgSuggestionAckDurationMs: number;
  avgPlanConfirmDurationMs: number;
  avgExecutionCompleteDurationMs: number;
  overdueSuggestionCount: number;
  overduePlanCount: number;
  overdueExecutionCount: number;
  slaTargetMs: number;
  slaHitRate: number;
}

// GovernanceEffectivenessResponse (Contract 4.4)
export interface GovernanceEffectivenessResponse {
  impactType: string;
  beforeRiskLevel: string;
  afterRiskLevel: string;
  improvedCount: number;
  unchangedCount: number;
  worsenedCount: number;
  improvementRate: number;
  sampleSize: number;
}

// GovernanceReviewItemResponse (Contract 4.5)
export interface GovernanceReviewItemResponse {
  itemType: ReviewItemType;
  itemId: number;
  itemNo: string;
  title: string;
  riskLevel: string;
  status: string;
  reason?: string;
  ownerName?: string;
  createdAt: string;
  dueAt?: string;
  relatedActionPlanNo?: string;
  relatedExecutionNo?: string;
  relatedSuggestionNo?: string;
}

// GovernanceOpsQueryParams (Contract 3.1)
export interface GovernanceOpsQueryParams {
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  startTime?: string;
  endTime?: string;
  interval?: OpsInterval;
  riskLevel?: RiskLevel;
  suggestionStatus?: SuggestionStatus;
  actionPlanStatus?: ActionPlanStatus;
  executionStatus?: ActionExecutionStatus;
  impactType?: ImpactType;
  pageNum?: number;
  pageSize?: number;
}

// API functions (Contract 2.1)
export function getGovernanceOpsOverview(params?: GovernanceOpsQueryParams) {
  return get<GovernanceOpsOverviewResponse>('/api/assets/governance-ops/overview', params);
}

export function getGovernanceOpsTrends(params?: GovernanceOpsQueryParams) {
  return get<GovernanceTrendPoint[]>('/api/assets/governance-ops/trends', params);
}

export function getGovernanceOpsSla(params?: GovernanceOpsQueryParams) {
  return get<GovernanceSlaResponse>('/api/assets/governance-ops/sla', params);
}

export function getGovernanceOpsEffectiveness(params?: GovernanceOpsQueryParams) {
  return get<GovernanceEffectivenessResponse[]>('/api/assets/governance-ops/effectiveness', params);
}

export interface GovernanceReviewItemPageResponse {
  records: GovernanceReviewItemResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export function listGovernanceOpsReviewItems(params?: GovernanceOpsQueryParams) {
  return get<GovernanceReviewItemPageResponse>('/api/assets/governance-ops/review-items', params);
}

// ========== M Stage: Alert Rules, Alert Records, Reports ==========

// GovernanceOpsMetricType enum (Contract 5.1)
export type GovernanceOpsMetricType =
  | 'CRITICAL_RISK_COUNT'
  | 'WARNING_RISK_COUNT'
  | 'OPEN_SUGGESTION_COUNT'
  | 'FAILED_EXECUTION_COUNT'
  | 'SLA_HIT_RATE'
  | 'OVERDUE_REVIEW_ITEM_COUNT'
  | 'IMPACT_RECORDED_COUNT';

// AlertOperator enum (Contract 5.2)
export type AlertOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ';

// GovernanceAlertLevel enum (Contract 5.3)
export type GovernanceAlertLevel = 'INFO' | 'WARNING' | 'CRITICAL';

// GovernanceAlertStatus enum (Contract 5.4)
export type GovernanceAlertStatus = 'OPEN' | 'ACKED' | 'RESOLVED';

// GovernanceReportType enum (Contract 5.5)
export type GovernanceReportType = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'CUSTOM';

// GovernanceAlertRuleRequest (Contract 4.1)
export interface GovernanceAlertRuleRequest {
  ruleName: string;
  metricType: GovernanceOpsMetricType;
  operator: AlertOperator;
  thresholdValue: number;
  alertLevel: GovernanceAlertLevel;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  enabled: boolean;
  description?: string;
}

// GovernanceAlertRuleResponse (Contract 4.2)
export interface GovernanceAlertRuleResponse {
  id: number;
  ruleName: string;
  metricType: GovernanceOpsMetricType;
  operator: AlertOperator;
  thresholdValue: number;
  alertLevel: GovernanceAlertLevel;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  enabled: boolean;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

// GovernanceAlertEvaluationResponse (Contract 4.3)
export interface GovernanceAlertEvaluationResponse {
  evaluatedAt: string;
  evaluatedRuleCount: number;
  triggeredAlertCount: number;
  alertIds: number[];
  manualOnly: boolean;
}

// GovernanceAlertRecordResponse (Contract 3.2 - N Stage enhanced, O Stage additional fields)
export interface GovernanceAlertRecordResponse {
  id: number;
  alertNo: string;
  ruleId: number;
  ruleName: string;
  metricType: GovernanceOpsMetricType;
  currentValue: number;
  thresholdValue: number;
  operator: AlertOperator;
  alertLevel: GovernanceAlertLevel;
  alertStatus: GovernanceAlertStatus;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  reason?: string;
  createdAt: string;
  acknowledgedAt?: string;
  resolvedAt?: string;
  // N Stage new fields
  acknowledgedBy?: number;
  acknowledgedByName?: string;
  resolvedBy?: number;
  resolvedByName?: string;
  handleNote?: string;
  evidencePayload?: Record<string, unknown>;
  // O Stage new fields (Contract 3.9)
  assigneeId?: number;
  assigneeName?: string;
  assignedAt?: string;
  assignmentDueAt?: string;
  escalated?: boolean;
  escalationLevel?: string;
  escalatedAt?: string;
}

// GovernanceAlertHandleRequest (Contract 3.1)
export interface GovernanceAlertHandleRequest {
  handleNote?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
}

// GovernanceAlertHandleAction enum (Contract 4.2)
export type GovernanceAlertHandleAction = 'ACK' | 'RESOLVE' | 'REOPEN';

// GovernanceAlertHandlingRecordResponse (Contract 3.3)
export interface GovernanceAlertHandlingRecordResponse {
  id: number;
  alertId: number;
  alertNo: string;
  fromStatus: GovernanceAlertStatus;
  toStatus: GovernanceAlertStatus;
  action: GovernanceAlertHandleAction;
  handleNote?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
  createdAt: string;
}

// GovernanceAlertHandlingRecordPageResponse (Contract 3.4)
export interface GovernanceAlertHandlingRecordPageResponse {
  records: GovernanceAlertHandlingRecordResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// GovernanceReportGenerateRequest (Contract 4.5)
export interface GovernanceReportGenerateRequest {
  reportType: GovernanceReportType;
  startTime: string;
  endTime: string;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  title?: string;
}

// GovernanceReportResponse (Contract 4.6)
export interface GovernanceReportResponse {
  id: number;
  reportNo: string;
  reportType: GovernanceReportType;
  title: string;
  startTime: string;
  endTime: string;
  summaryPayload: Record<string, unknown>;
  overviewPayload: Record<string, unknown>;
  trendPayload: Record<string, unknown>;
  slaPayload: Record<string, unknown>;
  effectivenessPayload: Record<string, unknown>;
  reviewItemPayload: Record<string, unknown>;
  alertPayload: Record<string, unknown>;
  generatedAt: string;
  generatedBy?: number;
}

// Alert Rule Query Params
export interface GovernanceAlertRuleQueryParams {
  pageNum?: number;
  pageSize?: number;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  metricType?: GovernanceOpsMetricType;
  alertLevel?: GovernanceAlertLevel;
  enabled?: boolean;
}

// Alert Record Query Params
export interface GovernanceAlertRecordQueryParams {
  pageNum?: number;
  pageSize?: number;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  metricType?: GovernanceOpsMetricType;
  alertLevel?: GovernanceAlertLevel;
  alertStatus?: GovernanceAlertStatus;
  startTime?: string;
  endTime?: string;
}

// Report Query Params
export interface GovernanceReportQueryParams {
  pageNum?: number;
  pageSize?: number;
  envId?: number;
  resourcePoolId?: number;
  serviceId?: number;
  reportType?: GovernanceReportType;
  startTime?: string;
  endTime?: string;
}

// Page Response Types
export interface GovernanceAlertRulePageResponse {
  records: GovernanceAlertRuleResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface GovernanceAlertRecordPageResponse {
  records: GovernanceAlertRecordResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface GovernanceReportPageResponse {
  records: GovernanceReportResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// ========== Alert Rule APIs (Contract 2.1) ==========

export function listGovernanceAlertRules(params?: GovernanceAlertRuleQueryParams) {
  return get<GovernanceAlertRulePageResponse>('/api/assets/governance-ops/alert-rules', params);
}

export function createGovernanceAlertRule(data: GovernanceAlertRuleRequest) {
  return post<GovernanceAlertRuleResponse>('/api/assets/governance-ops/alert-rules', data);
}

export function updateGovernanceAlertRule(id: number, data: GovernanceAlertRuleRequest) {
  return put<GovernanceAlertRuleResponse>(`/api/assets/governance-ops/alert-rules/${id}`, data);
}

export function toggleGovernanceAlertRule(id: number, enabled: boolean) {
  return post<GovernanceAlertRuleResponse>(`/api/assets/governance-ops/alert-rules/${id}/toggle?enabled=${enabled}`);
}

// ========== Alert Evaluation & Record APIs (Contract 2.2) ==========

export function evaluateGovernanceAlerts() {
  return post<GovernanceAlertEvaluationResponse>('/api/assets/governance-ops/alerts/evaluate');
}

export function listGovernanceAlertRecords(params?: GovernanceAlertRecordQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alerts', params);
}

// ========== Report APIs (Contract 2.3) ==========

export function listGovernanceReports(params?: GovernanceReportQueryParams) {
  return get<GovernanceReportPageResponse>('/api/assets/governance-ops/reports', params);
}

export function getGovernanceReport(id: number) {
  return get<GovernanceReportResponse>(`/api/assets/governance-ops/reports/${id}`);
}

export function generateGovernanceReport(data: GovernanceReportGenerateRequest) {
  return post<GovernanceReportResponse>('/api/assets/governance-ops/reports/generate', data);
}

// ========== Alert Handling APIs (Contract 2.1 - N Stage) ==========

export function ackGovernanceAlert(id: number, data?: GovernanceAlertHandleRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/ack`, data || {});
}

export function resolveGovernanceAlert(id: number, data?: GovernanceAlertHandleRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/resolve`, data || {});
}

export function reopenGovernanceAlert(id: number, data?: GovernanceAlertHandleRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/reopen`, data || {});
}

export function getGovernanceAlert(id: number) {
  return get<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}`);
}

export interface GovernanceAlertHandlingRecordQueryParams {
  pageNum?: number;
  pageSize?: number;
}

export function listGovernanceAlertHandlingRecords(id: number, params?: GovernanceAlertHandlingRecordQueryParams) {
  return get<GovernanceAlertHandlingRecordPageResponse>(`/api/assets/governance-ops/alerts/${id}/handling-records`, params);
}

// ========== O Stage: Alert Assignment & Escalation ==========

// O Stage Enums (Contract 4.1, 4.2)
export type GovernanceAlertAssignmentAction = 'ASSIGN' | 'REASSIGN' | 'UNASSIGN';
export type GovernanceAlertEscalationLevel = 'L1' | 'L2' | 'L3';

// O Stage Request DTOs (Contract 3.1 - 3.4)
export interface GovernanceAlertAssignRequest {
  assigneeId: number;
  assigneeName: string;
  assignReason?: string;
  dueAt?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
}

export interface GovernanceAlertReassignRequest {
  fromAssigneeId?: number;
  toAssigneeId: number;
  toAssigneeName: string;
  reassignReason?: string;
  dueAt?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
}

export interface GovernanceAlertUnassignRequest {
  unassignReason?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
}

export interface GovernanceAlertEscalateRequest {
  escalationLevel: GovernanceAlertEscalationLevel;
  escalatedToId: number;
  escalatedToName: string;
  escalateReason?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
}

// O Stage Response DTOs (Contract 3.5 - 3.8)
export interface GovernanceAlertAssignmentRecordResponse {
  id: number;
  alertId: number;
  alertNo: string;
  action: GovernanceAlertAssignmentAction;
  fromAssigneeId?: number;
  fromAssigneeName?: string;
  toAssigneeId?: number;
  toAssigneeName?: string;
  reason?: string;
  dueAt?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
  createdAt: string;
}

export interface GovernanceAlertEscalationRecordResponse {
  id: number;
  alertId: number;
  alertNo: string;
  escalationLevel: GovernanceAlertEscalationLevel;
  escalatedToId: number;
  escalatedToName: string;
  escalateReason?: string;
  evidencePayload?: Record<string, unknown>;
  operatorId?: number;
  operatorName?: string;
  createdAt: string;
}

export interface GovernanceAlertAssignmentRecordPageResponse {
  records: GovernanceAlertAssignmentRecordResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface GovernanceAlertEscalationRecordPageResponse {
  records: GovernanceAlertEscalationRecordResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// O Stage Assignment APIs (Contract 2.1)
export function assignGovernanceAlert(id: number, data: GovernanceAlertAssignRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/assign`, data);
}

export function reassignGovernanceAlert(id: number, data: GovernanceAlertReassignRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/reassign`, data);
}

export function unassignGovernanceAlert(id: number, data: GovernanceAlertUnassignRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/unassign`, data);
}

// O Stage Escalation APIs (Contract 2.2)
export function escalateGovernanceAlert(id: number, data: GovernanceAlertEscalateRequest) {
  return post<GovernanceAlertRecordResponse>(`/api/assets/governance-ops/alerts/${id}/escalate`, data);
}

// O Stage History Query APIs (Contract 2.3)
export function listGovernanceAlertAssignmentRecords(id: number, params?: { pageNum?: number; pageSize?: number }) {
  return get<GovernanceAlertAssignmentRecordPageResponse>(`/api/assets/governance-ops/alerts/${id}/assignment-records`, params);
}

export function listGovernanceAlertEscalationRecords(id: number, params?: { pageNum?: number; pageSize?: number }) {
  return get<GovernanceAlertEscalationRecordPageResponse>(`/api/assets/governance-ops/alerts/${id}/escalation-records`, params);
}

// O Stage Todo View APIs (Contract 2.4)
export function listMyAssignedGovernanceAlerts(params?: GovernanceAlertRecordQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alerts/my-assigned', params);
}

export function listEscalatedGovernanceAlerts(params?: GovernanceAlertRecordQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alerts/escalated', params);
}

// ========== P Stage: Alert Workbench APIs ==========

// P Stage: GovernanceAlertWorkbenchSummaryResponse (Contract 3.1)
export interface GovernanceAlertWorkbenchSummaryResponse {
  openCount: number;
  ackedCount: number;
  resolvedCount: number;
  assignedToMeCount: number;
  overdueCount: number;
  escalatedCount: number;
  criticalCount: number;
  updatedAt: string;
}

// P Stage: Overdue query params (Contract 4.1)
export interface GovernanceAlertOverdueQueryParams {
  pageNum?: number;
  pageSize?: number;
  assigneeId?: number;
  level?: GovernanceAlertLevel;
  escalated?: boolean;
}

// P Stage: Escalated query params (Contract 4.2)
export interface GovernanceAlertEscalatedQueryParams {
  pageNum?: number;
  pageSize?: number;
  escalationLevel?: GovernanceAlertEscalationLevel;
  status?: GovernanceAlertStatus;
}

// P Stage: My assigned query params (Contract 4.3)
export interface GovernanceAlertMyAssignedQueryParams {
  pageNum?: number;
  pageSize?: number;
  status?: GovernanceAlertStatus;
  level?: GovernanceAlertLevel;
  overdueOnly?: boolean;
}

// P Stage: Workbench Summary API (Contract 2.1)
export function getGovernanceAlertWorkbenchSummary() {
  return get<GovernanceAlertWorkbenchSummaryResponse>('/api/assets/governance-ops/alert-workbench/summary');
}

// P Stage: Overdue Alerts API (Contract 2.2)
export function listOverdueGovernanceAlerts(params?: GovernanceAlertOverdueQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alert-workbench/overdue', params);
}

// P Stage: Escalated Alerts API (Contract 2.3)
export function listWorkbenchEscalatedGovernanceAlerts(params?: GovernanceAlertEscalatedQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alert-workbench/escalated', params);
}

// P Stage: My Assigned Alerts API (Contract 2.4)
export function listWorkbenchMyAssignedGovernanceAlerts(params?: GovernanceAlertMyAssignedQueryParams) {
  return get<GovernanceAlertRecordPageResponse>('/api/assets/governance-ops/alert-workbench/my-assigned', params);
}

// ========== Q Stage: Alert Notification APIs ==========

// Q Stage Enums (Contract 5.1, 5.2)
export type GovernanceAlertNotificationChannelType = 'INTERNAL' | 'EMAIL_DRAFT' | 'WEBHOOK_DRAFT';
export type GovernanceAlertNotificationStatus = 'DRAFTED' | 'MANUALLY_CONFIRMED' | 'CANCELLED';

// Q Stage: Notification Draft Response (Contract 3.1)
export interface GovernanceAlertNotificationDraftResponse {
  alertRecordId: number;
  alertTitle: string;
  alertLevel: GovernanceAlertLevel;
  alertStatus: GovernanceAlertStatus;
  assignedTo?: number;
  assignedToName?: string;
  escalatedTo?: number;
  escalatedToName?: string;
  channelType: GovernanceAlertNotificationChannelType;
  targetUserId?: number;
  targetUserName?: string;
  subject: string;
  content: string;
  suggestedAction?: string;
  generatedAt: string;
}

// Q Stage: Manual Notification Request (Contract 3.2)
export interface GovernanceAlertManualNotificationRequest {
  alertRecordId: number;
  channelType: GovernanceAlertNotificationChannelType;
  targetUserId: number;
  targetUserName: string;
  subject: string;
  content: string;
  notifyReason: string;
  evidencePayload?: string;
}

// Q Stage: Notification Record Response (Contract 3.3)
export interface GovernanceAlertNotificationRecordResponse {
  id: number;
  notificationNo: string;
  alertRecordId: number;
  alertNo: string;
  alertTitle: string;
  channelType: GovernanceAlertNotificationChannelType;
  notificationStatus: GovernanceAlertNotificationStatus;
  targetUserId: number;
  targetUserName: string;
  subject: string;
  content: string;
  notifyReason: string;
  evidencePayload?: string;
  createdBy: number;
  createdByName: string;
  createdAt: string;
}

// Q Stage: Notification Record Page Response
export interface GovernanceAlertNotificationRecordPageResponse {
  records: GovernanceAlertNotificationRecordResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// Q Stage: Draft Query Params (Contract 4.1)
export interface GovernanceAlertNotificationDraftQueryParams {
  alertRecordId: number;
  channelType?: GovernanceAlertNotificationChannelType;
  targetUserId?: number;
  targetUserName?: string;
}

// Q Stage: Record Query Params (Contract 4.2)
export interface GovernanceAlertNotificationRecordQueryParams {
  alertRecordId?: number;
  channelType?: GovernanceAlertNotificationChannelType;
  notificationStatus?: GovernanceAlertNotificationStatus;
  targetUserId?: number;
  pageNum?: number;
  pageSize?: number;
}

// Q Stage: Generate Notification Draft API (Contract 2.1)
export function getGovernanceAlertNotificationDraft(params: GovernanceAlertNotificationDraftQueryParams) {
  return get<GovernanceAlertNotificationDraftResponse>('/api/assets/governance-ops/alert-notifications/drafts', params);
}

// Q Stage: Manual Confirm Notification API (Contract 2.2)
export function confirmGovernanceAlertNotification(data: GovernanceAlertManualNotificationRequest) {
  return post<GovernanceAlertNotificationRecordResponse>('/api/assets/governance-ops/alert-notifications/manual-confirm', data);
}

// Q Stage: List Notification Records API (Contract 2.3)
export function listGovernanceAlertNotificationRecords(params?: GovernanceAlertNotificationRecordQueryParams) {
  return get<GovernanceAlertNotificationRecordPageResponse>('/api/assets/governance-ops/alert-notifications/records', params);
}

// Q Stage: Get Single Notification Record API (Contract 2.3)
export function getGovernanceAlertNotificationRecord(id: number) {
  return get<GovernanceAlertNotificationRecordResponse>(`/api/assets/governance-ops/alert-notifications/records/${id}`);
}

// ========== R Stage: Alert Notification Followup APIs ==========

// R Stage Enums (Contract 5.1, 5.2, 5.3)
export type GovernanceAlertNotificationFollowupType = 'FOLLOW_UP' | 'REPLY_RECEIVED' | 'CALL_MADE' | 'VISIT_MADE' | 'OTHER';
export type GovernanceAlertNotificationFollowupStatus = 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type GovernanceAlertReceiptStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'NO_RESPONSE';

// R Stage: Followup Request (Contract 3.1)
export interface GovernanceAlertNotificationFollowupRequest {
  notificationRecordId: number;
  followUpType: GovernanceAlertNotificationFollowupType;
  followUpContent: string;
  followUpResult?: string;
  receiptStatus?: GovernanceAlertReceiptStatus;
  evidencePayload?: string;
}

// R Stage: Receipt Confirm Request (Contract 3.2)
export interface GovernanceAlertReceiptConfirmRequest {
  receiptStatus: 'CONFIRMED' | 'FAILED';
  receiptNote?: string;
  evidencePayload?: string;
}

// R Stage: Followup Response (Contract 3.3)
export interface GovernanceAlertNotificationFollowupResponse {
  id: number;
  followUpNo: string;
  notificationRecordId: number;
  notificationNo: string;
  alertRecordId: number;
  alertNo: string;
  followUpType: GovernanceAlertNotificationFollowupType;
  followUpContent: string;
  followUpResult?: string;
  followUpStatus: GovernanceAlertNotificationFollowupStatus;
  receiptStatus: GovernanceAlertReceiptStatus;
  receiptNote?: string;
  evidencePayload?: string;
  createdBy: number;
  createdByName: string;
  createdAt: string;
  confirmedBy?: number;
  confirmedByName?: string;
  confirmedAt?: string;
}

// R Stage: Followup Page Response (Contract 3.4)
export interface GovernanceAlertNotificationFollowupPageResponse {
  records: GovernanceAlertNotificationFollowupResponse[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// R Stage: Followup Query Params (Contract 4.1)
export interface GovernanceAlertNotificationFollowupQueryParams {
  notificationRecordId?: number;
  followUpType?: GovernanceAlertNotificationFollowupType;
  receiptStatus?: GovernanceAlertReceiptStatus;
  followUpStatus?: GovernanceAlertNotificationFollowupStatus;
  alertRecordId?: number;
  pageNum?: number;
  pageSize?: number;
}

// R Stage: Create Followup API (Contract 2.1)
export function createGovernanceAlertNotificationFollowup(data: GovernanceAlertNotificationFollowupRequest) {
  return post<GovernanceAlertNotificationFollowupResponse>('/api/assets/governance-ops/alert-notification-followups', data);
}

// R Stage: Confirm Receipt API (Contract 2.2)
export function confirmGovernanceAlertReceipt(id: number, data: GovernanceAlertReceiptConfirmRequest) {
  return post<GovernanceAlertNotificationFollowupResponse>(`/api/assets/governance-ops/alert-notification-followups/${id}/confirm-receipt`, data);
}

// R Stage: List Followups API (Contract 2.3)
export function listGovernanceAlertNotificationFollowups(params?: GovernanceAlertNotificationFollowupQueryParams) {
  return get<GovernanceAlertNotificationFollowupPageResponse>('/api/assets/governance-ops/alert-notification-followups', params);
}

// R Stage: Get Single Followup API (Contract 2.3)
export function getGovernanceAlertNotificationFollowup(id: number) {
  return get<GovernanceAlertNotificationFollowupResponse>(`/api/assets/governance-ops/alert-notification-followups/${id}`);
}

// R Stage: List Followups by Notification API (Contract 2.4)
export function listFollowupsByNotification(notificationId: number, params?: { pageNum?: number; pageSize?: number }) {
  return get<GovernanceAlertNotificationFollowupPageResponse>(`/api/assets/governance-ops/alert-notification-followups/by-notification/${notificationId}`, params);
}

// ========== S Stage: Alert Notification Followup Review APIs ==========

// S Stage: Review Overview Response (Contract 3.1)
export interface GovernanceAlertNotificationFollowupReviewOverviewResponse {
  totalNotifications: number;
  followedUpNotifications: number;
  pendingFollowupNotifications: number;
  totalFollowupRecords: number;
  completedFollowupRecords: number;
  inProgressFollowupRecords: number;
  cancelledFollowupRecords: number;
  pendingReceiptCount: number;
  confirmedReceiptCount: number;
  failedReceiptCount: number;
  noResponseCount: number;
  followupCompletionRate: number;
  receiptConfirmationRate: number;
  averageFirstFollowupMinutes: number;
  overdueFollowupCount: number;
  updatedAt: string;
}

// S Stage: Receipt Stats By Channel Item (Contract 3.2.1)
export interface GovernanceAlertReceiptStatsByChannelItem {
  channelType: string;
  totalCount: number;
  confirmedCount: number;
  failedCount: number;
  noResponseCount: number;
  confirmationRate: number;
}

// S Stage: Receipt Stats By User Item (Contract 3.2.2)
export interface GovernanceAlertReceiptStatsByUserItem {
  targetUserId: number;
  targetUserName: string;
  totalCount: number;
  confirmedCount: number;
  failedCount: number;
  noResponseCount: number;
  confirmationRate: number;
}

// S Stage: Receipt Stats Overall (Contract 3.2.3)
export interface GovernanceAlertReceiptStatsOverall {
  totalCount: number;
  confirmedCount: number;
  failedCount: number;
  noResponseCount: number;
  confirmationRate: number;
}

// S Stage: Receipt Stats Response (Contract 3.2)
export interface GovernanceAlertReceiptStatsResponse {
  byChannelType: GovernanceAlertReceiptStatsByChannelItem[];
  byTargetUser: GovernanceAlertReceiptStatsByUserItem[];
  overall: GovernanceAlertReceiptStatsOverall;
}

// S Stage: Followup Status Item (Contract 3.3.1)
export interface GovernanceAlertFollowupStatusItem {
  followUpStatus: string;
  count: number;
  percentage: number;
}

// S Stage: Receipt Status Item (Contract 3.3.2)
export interface GovernanceAlertReceiptStatusItem {
  receiptStatus: string;
  count: number;
  percentage: number;
}

// S Stage: Followup Type Item (Contract 3.3.3)
export interface GovernanceAlertFollowupTypeItem {
  followUpType: string;
  count: number;
  percentage: number;
}

// S Stage: Followup Status Distribution Response (Contract 3.3)
export interface GovernanceAlertFollowupStatusDistributionResponse {
  byFollowUpStatus: GovernanceAlertFollowupStatusItem[];
  byReceiptStatus: GovernanceAlertReceiptStatusItem[];
  byFollowUpType: GovernanceAlertFollowupTypeItem[];
}

// S Stage: Followup By Handler Item (Contract 3.4.1)
export interface GovernanceAlertFollowupByHandlerItem {
  handlerId: number;
  handlerName: string;
  totalFollowupCount: number;
  completedCount: number;
  inProgressCount: number;
  cancelledCount: number;
  completionRate: number;
  averageFollowupMinutes: number;
  firstFollowupCount: number;
  averageFirstFollowupMinutes: number;
}

// S Stage: Followup By Handler Page Response (Contract 3.4)
export interface GovernanceAlertFollowupByHandlerPageResponse {
  records: GovernanceAlertFollowupByHandlerItem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// S Stage: Followup By Alert Item (Contract 3.5.1)
export interface GovernanceAlertFollowupByAlertItem {
  alertRecordId: number;
  alertNo: string;
  alertTitle: string;
  alertLevel: string;
  alertStatus: string;
  notificationCount: number;
  followupCount: number;
  uniqueHandlerCount: number;
  confirmedReceiptCount: number;
  pendingReceiptCount: number;
  closedLoopRate: number;
  latestFollowupAt: string;
  createdAt: string;
}

// S Stage: Followup By Alert Page Response (Contract 3.5)
export interface GovernanceAlertFollowupByAlertPageResponse {
  records: GovernanceAlertFollowupByAlertItem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// S Stage: Followup By Notification Item (Contract 3.6.1)
export interface GovernanceAlertFollowupByNotificationItem {
  notificationRecordId: number;
  notificationNo: string;
  alertRecordId: number;
  alertNo: string;
  alertTitle: string;
  channelType: string;
  notificationStatus: string;
  targetUserId: number;
  targetUserName: string;
  followupCount: number;
  latestFollowupStatus: string;
  latestReceiptStatus: string;
  firstFollowupAt: string;
  latestFollowupAt: string;
  createdAt: string;
}

// S Stage: Followup By Notification Page Response (Contract 3.6)
export interface GovernanceAlertFollowupByNotificationPageResponse {
  records: GovernanceAlertFollowupByNotificationItem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// S Stage: Review Detail Item (Contract 3.7.1)
export interface GovernanceAlertFollowupReviewDetailItem {
  followupId: number;
  followUpNo: string;
  notificationRecordId: number;
  notificationNo: string;
  alertRecordId: number;
  alertNo: string;
  alertTitle: string;
  alertLevel: string;
  alertStatus: string;
  channelType: string;
  targetUserId: number;
  targetUserName: string;
  followUpType: string;
  followUpContent: string;
  followUpResult: string;
  followUpStatus: string;
  receiptStatus: string;
  receiptNote: string;
  createdBy: number;
  createdByName: string;
  createdAt: string;
  confirmedBy: number;
  confirmedByName: string;
  confirmedAt: string;
  firstFollowupForNotification: boolean;
  minutesSinceNotification: number;
}

// S Stage: Review Detail Page Response (Contract 3.7)
export interface GovernanceAlertFollowupReviewDetailPageResponse {
  records: GovernanceAlertFollowupReviewDetailItem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// S Stage: Overview Query Params (Contract 4.1)
export interface GovernanceAlertReviewOverviewQueryParams {
  startTime?: string;
  endTime?: string;
}

// S Stage: Receipt Stats Query Params (Contract 4.2)
export interface GovernanceAlertReceiptStatsQueryParams {
  startTime?: string;
  endTime?: string;
  channelType?: GovernanceAlertNotificationChannelType;
  targetUserId?: number;
}

// S Stage: Status Distribution Query Params (Contract 4.3)
export interface GovernanceAlertStatusDistributionQueryParams {
  startTime?: string;
  endTime?: string;
  alertRecordId?: number;
  notificationRecordId?: number;
}

// S Stage: By Handler Query Params (Contract 4.4)
export interface GovernanceAlertByHandlerQueryParams {
  startTime?: string;
  endTime?: string;
  handlerId?: number;
  pageNum?: number;
  pageSize?: number;
}

// S Stage: By Alert Query Params (Contract 4.5)
export interface GovernanceAlertByAlertQueryParams {
  startTime?: string;
  endTime?: string;
  alertRecordId?: number;
  alertLevel?: GovernanceAlertLevel;
  alertStatus?: GovernanceAlertStatus;
  pageNum?: number;
  pageSize?: number;
}

// S Stage: By Notification Query Params (Contract 4.6)
export interface GovernanceAlertByNotificationQueryParams {
  startTime?: string;
  endTime?: string;
  notificationRecordId?: number;
  alertRecordId?: number;
  targetUserId?: number;
  channelType?: GovernanceAlertNotificationChannelType;
  followUpStatus?: GovernanceAlertNotificationFollowupStatus;
  receiptStatus?: GovernanceAlertReceiptStatus;
  pageNum?: number;
  pageSize?: number;
}

// S Stage: Detail Query Params (Contract 4.7)
export interface GovernanceAlertReviewDetailQueryParams {
  startTime?: string;
  endTime?: string;
  alertRecordId?: number;
  notificationRecordId?: number;
  targetUserId?: number;
  createdBy?: number;
  followUpType?: GovernanceAlertNotificationFollowupType;
  followUpStatus?: GovernanceAlertNotificationFollowupStatus;
  receiptStatus?: GovernanceAlertReceiptStatus;
  pageNum?: number;
  pageSize?: number;
}

// S Stage: Review Overview API (Contract 2.1)
export function getGovernanceAlertNotificationFollowupReviewOverview(params?: GovernanceAlertReviewOverviewQueryParams) {
  return get<GovernanceAlertNotificationFollowupReviewOverviewResponse>('/api/assets/governance-ops/alert-notification-followup-review/overview', params);
}

// S Stage: Receipt Stats API (Contract 2.2)
export function getGovernanceAlertReceiptStats(params?: GovernanceAlertReceiptStatsQueryParams) {
  return get<GovernanceAlertReceiptStatsResponse>('/api/assets/governance-ops/alert-notification-followup-review/receipt-stats', params);
}

// S Stage: Followup Status Distribution API (Contract 2.3)
export function getGovernanceAlertFollowupStatusDistribution(params?: GovernanceAlertStatusDistributionQueryParams) {
  return get<GovernanceAlertFollowupStatusDistributionResponse>('/api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution', params);
}

// S Stage: By Handler API (Contract 2.4)
export function listGovernanceAlertFollowupByHandler(params?: GovernanceAlertByHandlerQueryParams) {
  return get<GovernanceAlertFollowupByHandlerPageResponse>('/api/assets/governance-ops/alert-notification-followup-review/by-handler', params);
}

// S Stage: By Alert API (Contract 2.5)
export function listGovernanceAlertFollowupByAlert(params?: GovernanceAlertByAlertQueryParams) {
  return get<GovernanceAlertFollowupByAlertPageResponse>('/api/assets/governance-ops/alert-notification-followup-review/by-alert', params);
}

// S Stage: By Notification API (Contract 2.6)
export function listGovernanceAlertFollowupByNotification(params?: GovernanceAlertByNotificationQueryParams) {
  return get<GovernanceAlertFollowupByNotificationPageResponse>('/api/assets/governance-ops/alert-notification-followup-review/by-notification', params);
}

// S Stage: Detail List API (Contract 2.7)
export function listGovernanceAlertFollowupReviewDetails(params?: GovernanceAlertReviewDetailQueryParams) {
  return get<GovernanceAlertFollowupReviewDetailPageResponse>('/api/assets/governance-ops/alert-notification-followup-review/details', params);
}