import { createCrudApi, createReadonlyApi } from './crud';
import { get, post, postForm, put } from '@/utils/http';
import { isJsonObject } from '@/utils/executorConfigPayload';

export const releaseApi = createCrudApi<Record<string, unknown>>('/api/releases');
export const rollbackApi = createCrudApi<Record<string, unknown>>('/api/rollbacks');
export const versionApi = createReadonlyApi<Record<string, unknown>>('/api/versions');
export const artifactApi = createReadonlyApi<Record<string, unknown>>('/api/artifacts');
export const currentVersionApi = createReadonlyApi<Record<string, unknown>>('/api/current-versions');

export interface DatabaseSequenceHealth {
  tableName: string;
  sequenceName: string;
  maxId: number;
  sequenceValue: number;
  healthy: boolean;
  suggestedFixSql?: string;
}

export interface UploadArtifactResponse {
  artifactId: number;
  artifactName: string;
  artifactUri: string;
  checksum: string;
  sizeBytes: number;
  repositoryType: string;
}

export interface CreateReleaseVersionPayload {
  appId: number;
  versionNo: string;
  artifactId: number;
  gitCommit?: string;
  branchName?: string;
  buildNo?: string;
}

export function uploadArtifact(payload: { runtimeServiceInstanceId: number; file: File; artifactName?: string }) {
  const formData = new FormData();
  formData.append('runtimeServiceInstanceId', String(payload.runtimeServiceInstanceId));
  formData.append('file', payload.file);
  if (payload.artifactName) {
    formData.append('artifactName', payload.artifactName);
  }
  return postForm<UploadArtifactResponse>('/api/artifacts/upload', formData);
}

export function createReleaseVersion(payload: CreateReleaseVersionPayload) {
  return post<Record<string, unknown>>('/api/versions', payload);
}

export function getReleaseSequenceHealth() {
  return get<DatabaseSequenceHealth[]>('/api/release-diagnostics/sequences');
}

export interface ExecuteResult {
  approvalId?: number;
  directExecuted?: boolean;
}

export function executeRelease(id: number | string) {
  return post<ExecuteResult>(`/api/releases/${id}/execute`);
}

export interface ReleasePrecheckInstanceResult {
  success: boolean;
  hostId: number;
  instanceId: number;
  artifactUri: string;
  stdout: string;
  stderr: string;
  errorMessage: string;
}

export interface ExecuteReleasePipelinePayload {
  pipelineId: number;
  envId?: number;
  versionId?: number;
}

export interface ExecuteReleasePipelineResponse {
  pipelineRunId: number;
  runStatus: string;
}

export function executeReleasePipeline(id: number | string, payload: ExecuteReleasePipelinePayload) {
  return post<ExecuteReleasePipelineResponse>(`/api/releases/${id}/execute-pipeline`, payload);
}

export function cancelRelease(id: number | string) {
  return post<boolean>(`/api/releases/${id}/cancel`);
}

export function precheckRelease(id: number | string) {
  return post<ReleasePrecheckInstanceResult[]>(`/api/releases/${id}/precheck`);
}

export function getReleaseDeployRecords(id: number | string) {
  return get<Record<string, unknown>[]>(`/api/releases/${id}/deploy-records`);
}

export function executeRollback(id: number | string) {
  return post<ExecuteResult>(`/api/rollbacks/${id}/execute`);
}

export interface ApplicationRollbackPayload {
  appId: number;
  envId: number;
  serviceId: number;
  versionId: number;
  instanceIds: number[];
}

export interface ApplicationRollbackResult {
  rollbackId: number;
  status: string;
  approvalId?: number;
  prechecks: ReleasePrecheckInstanceResult[];
}

export function rollbackApplication(payload: ApplicationRollbackPayload) {
  return post<ApplicationRollbackResult>('/api/rollbacks/application', payload, { timeout: 0 });
}

export interface RollbackChoice {
  id: number;
  name: string;
  appId?: number;
  serviceId?: number;
}

export function getApplicationRollbackOptions() {
  return get<{ apps: RollbackChoice[]; envs: RollbackChoice[]; services: RollbackChoice[]; versions: RollbackChoice[] }>('/api/rollbacks/application/options');
}

export function getApplicationRollbackInstances(serviceId: number, envId: number) {
  return get<import('./runtime').RuntimeServiceInstance[]>('/api/rollbacks/application/instances', { serviceId, envId });
}

export function precheckRollback(id: number | string) {
  return post<ReleasePrecheckInstanceResult[]>(`/api/rollbacks/${id}/precheck`);
}

export function getRollbackDeployRecords(id: number | string) {
  return get<Record<string, unknown>[]>(`/api/rollbacks/${id}/deploy-records`);
}

export function getLatestDeployRecords(currentVersionId: number | string) {
  return get<Record<string, unknown>[]>(`/api/current-versions/${currentVersionId}/latest-deploy-records`);
}

export function getReleaseDetail(id: number | string) {
  return get<Record<string, unknown>>(`/api/releases/${id}/detail`);
}

export function getRollbackDetail(id: number | string) {
  return get<Record<string, unknown>>(`/api/rollbacks/${id}/detail`);
}

// ============ Canary Release APIs ============

export interface CanaryBatch {
  id: number;
  planId: number;
  batchNo: number;
  instanceIds: number[];
  status: string;
  startedAt?: string;
  endedAt?: string;
  failureReason?: string;
  // 执行结果类别：NOT_STARTED / SUCCEEDED / FAILED / PARTIAL / UNKNOWN；null 表示没有可信类别
  executionOutcome?: string | null;
  // E2: 质量门禁字段
  qualityGateStatus?: string;
  qualityGateReason?: string;
  qualityGateEvaluatedAt?: string;
  monitorSnapshotId?: number;
  monitorAlertEventIds?: number[];
  qualityMetricsPayload?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface CanaryPlan {
  id: number;
  releaseOrderId: number;
  batchCount: number;
  batchIntervalSeconds: number;
  autoRollbackEnabled: boolean;
  status: string;
  rollbackOrderId?: number;
  failureReason?: string;
  executionOutcome?: string | null;
  // 计划配置了自动回滚，不代表现在会真的执行；这里才是服务端给出的“当前是否可用”
  autoRollbackAvailable?: boolean;
  batches: CanaryBatch[];
  // E2: 质量门禁配置
  qualityGateEnabled?: boolean;
  maxFiringAlerts?: number;
  gateFailureAction?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateCanaryPlanPayload {
  batchCount: number;
  batchIntervalSeconds?: number;
  autoRollbackEnabled?: boolean;
  // E2: 质量门禁配置
  qualityGateEnabled?: boolean;
  maxFiringAlerts?: number;
  gateFailureAction?: string;
}

export function getCanaryPlan(releaseOrderId: number | string) {
  return get<CanaryPlan | null>(`/api/releases/${releaseOrderId}/canary-plan`);
}

export function createCanaryPlan(releaseOrderId: number | string, payload: CreateCanaryPlanPayload) {
  return post<CanaryPlan>(`/api/releases/${releaseOrderId}/canary-plan`, payload);
}

export function startCanary(releaseOrderId: number | string) {
  return post<CanaryPlan>(`/api/releases/${releaseOrderId}/canary/start`);
}

export function continueCanary(releaseOrderId: number | string) {
  return post<CanaryPlan>(`/api/releases/${releaseOrderId}/canary/continue`);
}

export function pauseCanary(releaseOrderId: number | string) {
  return post<CanaryPlan>(`/api/releases/${releaseOrderId}/canary/pause`);
}

// E2: 手动重评估质量门禁
export function evaluateBatchGate(releaseOrderId: number | string, batchId: number | string) {
  return post<CanaryPlan>(`/api/releases/${releaseOrderId}/canary/batches/${batchId}/evaluate-gate`);
}

// E3.1: 门禁评估历史
export interface GateEvaluationHistory {
  id: number;
  batchId: number;
  evaluationType: string;
  evaluationTypeLabel: string;
  gateResult: string;
  gateResultLabel: string;
  hitCount: number;
  threshold: number;
  monitorSnapshotId?: number;
  monitorAlertEventIds?: number[];
  metricsPayload?: Record<string, unknown>;
  reason?: string;
  evaluatedBy?: number;
  evaluatedByName?: string;
  evaluatedAt: string;
}

export function getBatchGateEvaluations(releaseOrderId: number | string, batchId: number | string) {
  return get<GateEvaluationHistory[]>(`/api/releases/${releaseOrderId}/canary/batches/${batchId}/gate-evaluations`);
}

// ============ F-D: Executor Config APIs ============

export type ExecutorType = 'SSH' | 'DOCKER' | 'KUBERNETES';

export interface ReleaseExecutorConfig {
  id: number;
  executorName: string;
  executorType: ExecutorType;
  envId: number;
  envName?: string;
  // Backend stores as JSON string, frontend normalizes to object for display
  configPayload: string | Record<string, unknown>;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

// Normalized type for frontend display (configPayload always parsed to object)
export interface NormalizedExecutorConfig extends Omit<ReleaseExecutorConfig, 'configPayload'> {
  configPayload: Record<string, unknown>;
  // Whether the stored payload read back as a JSON object
  configPayloadValid: boolean;
  // The stored payload text, kept verbatim when it did not read back as an object
  configPayloadRaw?: string;
}

// Read a stored payload back into an object. Text that is not a JSON object is not repaired and not
// dropped: it is kept as it was stored, next to the flag that says it still needs correcting.
function normalizeConfigPayload(payload: string | Record<string, unknown>): {
  configPayload: Record<string, unknown>;
  configPayloadValid: boolean;
  configPayloadRaw?: string;
} {
  if (typeof payload !== 'string') {
    return isJsonObject(payload)
      ? { configPayload: payload, configPayloadValid: true }
      : { configPayload: {}, configPayloadValid: false, configPayloadRaw: JSON.stringify(payload) };
  }
  try {
    const parsed: unknown = JSON.parse(payload);
    return isJsonObject(parsed)
      ? { configPayload: parsed, configPayloadValid: true }
      : { configPayload: {}, configPayloadValid: false, configPayloadRaw: payload };
  } catch {
    return { configPayload: {}, configPayloadValid: false, configPayloadRaw: payload };
  }
}

// Normalize single executor config
export function normalizeExecutorConfig(config: ReleaseExecutorConfig): NormalizedExecutorConfig {
  const { configPayload, ...rest } = config;
  return {
    ...rest,
    ...normalizeConfigPayload(configPayload),
  };
}

// Normalize array of executor configs
export function normalizeExecutorConfigs(configs: ReleaseExecutorConfig[]): NormalizedExecutorConfig[] {
  return configs.map(normalizeExecutorConfig);
}

export interface CreateExecutorConfigPayload {
  executorName: string;
  executorType: ExecutorType;
  envId: number;
  // Accept object or string in frontend, will be serialized to JSON string
  configPayload: Record<string, unknown> | string;
  enabled?: boolean;
}

export interface UpdateExecutorConfigPayload {
  id: number;
  executorName?: string;
  executorType?: ExecutorType;
  envId?: number;
  configPayload?: Record<string, unknown> | string;
  enabled?: boolean;
}

// Backend API base path
const EXECUTOR_CONFIG_BASE = '/api/releases/executor-configs';

export async function listExecutorConfigs(): Promise<NormalizedExecutorConfig[]> {
  const res = await get<ReleaseExecutorConfig[]>(EXECUTOR_CONFIG_BASE);
  return normalizeExecutorConfigs(res as ReleaseExecutorConfig[]);
}

export async function getExecutorConfig(id: number | string): Promise<NormalizedExecutorConfig> {
  const res = await get<ReleaseExecutorConfig>(`${EXECUTOR_CONFIG_BASE}/${id}`);
  return normalizeExecutorConfig(res as ReleaseExecutorConfig);
}

export async function createExecutorConfig(payload: CreateExecutorConfigPayload): Promise<NormalizedExecutorConfig> {
  // Serialize configPayload to JSON string for backend
  const serializedPayload = {
    ...payload,
    configPayload: typeof payload.configPayload === 'string'
      ? payload.configPayload
      : JSON.stringify(payload.configPayload),
  };
  const res = await post<ReleaseExecutorConfig>(EXECUTOR_CONFIG_BASE, serializedPayload);
  return normalizeExecutorConfig(res as ReleaseExecutorConfig);
}

export async function updateExecutorConfig(id: number | string, payload: UpdateExecutorConfigPayload): Promise<NormalizedExecutorConfig> {
  // Serialize configPayload to JSON string for backend
  const serializedPayload: Record<string, unknown> = { id: payload.id };
  if (payload.executorName !== undefined) serializedPayload.executorName = payload.executorName;
  if (payload.executorType !== undefined) serializedPayload.executorType = payload.executorType;
  if (payload.envId !== undefined) serializedPayload.envId = payload.envId;
  if (payload.configPayload !== undefined) {
    serializedPayload.configPayload = typeof payload.configPayload === 'string'
      ? payload.configPayload
      : JSON.stringify(payload.configPayload);
  }
  if (payload.enabled !== undefined) serializedPayload.enabled = payload.enabled;

  const res = await put<ReleaseExecutorConfig>(`${EXECUTOR_CONFIG_BASE}/${id}`, serializedPayload);
  return normalizeExecutorConfig(res as ReleaseExecutorConfig);
}

// Toggle enabled/disabled state - backend returns ApiResponse<Boolean>
export async function toggleExecutorConfig(id: number | string, enabled: boolean): Promise<boolean> {
  return post<boolean>(`${EXECUTOR_CONFIG_BASE}/${id}/toggle?enabled=${enabled}`);
}

export async function listExecutorConfigsByEnv(envId: number | string): Promise<NormalizedExecutorConfig[]> {
  const res = await get<ReleaseExecutorConfig[]>(`${EXECUTOR_CONFIG_BASE}?envId=${envId}`);
  return normalizeExecutorConfigs(res as ReleaseExecutorConfig[]);
}

// ============ V-B: Precheck Summary & Execution Context APIs (Contract 3.2) ============

export interface PrecheckHostSummaryItem {
  hostId?: number;
  hostName?: string;
  instanceId?: number;
  instanceName?: string;
  status: 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'UNKNOWN';
  successSteps: number;
  failedSteps: number;
  lastErrorMessage?: string;
}

export interface PrecheckFailureCategoryItem {
  category: 'CONFIG' | 'CONNECTIVITY' | 'PERMISSION' | 'RESOURCE' | 'DEPENDENCY' | 'UNKNOWN';
  count: number;
  message?: string;
}

export interface PrecheckSuggestionItem {
  suggestionType: 'CONFIG_EDIT' | 'RETRY_PRECHECK' | 'VIEW_LOG' | 'CONTACT_OWNER' | 'CHECK_INSTANCE';
  title: string;
  description?: string;
  targetRoute?: string;
  targetParams?: Record<string, unknown>;
}

export interface PrecheckSummaryResponse {
  orderId: number;
  orderType: 'RELEASE' | 'ROLLBACK';
  overallStatus: 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'UNKNOWN';
  totalHosts: number;
  successHosts: number;
  failedHosts: number;
  totalSteps: number;
  failedSteps: number;
  hostSummaries: PrecheckHostSummaryItem[];
  failureCategories: PrecheckFailureCategoryItem[];
  suggestions: PrecheckSuggestionItem[];
  generatedAt: string;
}

export interface ExecutionRiskItem {
  riskType: 'CONFIG_MISSING' | 'PRECHECK_FAILED' | 'VERSION_MISMATCH' | 'INSTANCE_UNHEALTHY' | 'UNKNOWN';
  level: 'LOW' | 'MEDIUM' | 'HIGH';
  message: string;
  suggestion?: string;
}

export interface VersionCompareItem {
  fieldName: string;
  currentValue?: string;
  targetValue?: string;
  changed: boolean;
}

export interface ExecutionContextResponse {
  orderId: number;
  orderType: 'RELEASE' | 'ROLLBACK';
  appId: number;
  appName?: string;
  envId: number;
  envName?: string;
  serviceId?: number;
  serviceName?: string;
  currentVersionId?: number;
  currentVersionNo?: string;
  targetVersionId?: number;
  targetVersionNo?: string;
  deployConfigComplete: boolean;
  precheckStatus: 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'UNKNOWN';
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'UNKNOWN';
  risks: ExecutionRiskItem[];
  versionCompare: VersionCompareItem[];
  generatedAt: string;
}

export function getPrecheckSummary(id: number | string): Promise<PrecheckSummaryResponse> {
  return get<PrecheckSummaryResponse>(`/api/releases/${id}/precheck-summary`);
}

export function getExecutionContext(id: number | string): Promise<ExecutionContextResponse> {
  return get<ExecutionContextResponse>(`/api/releases/${id}/execution-context`);
}

// ============ V-C: Rollback-side Precheck Summary & Execution Context (Contract 3.2) ============

export function getRollbackPrecheckSummary(id: number | string): Promise<PrecheckSummaryResponse> {
  return get<PrecheckSummaryResponse>(`/api/rollbacks/${id}/precheck-summary`);
}

export function getRollbackExecutionContext(id: number | string): Promise<ExecutionContextResponse> {
  return get<ExecutionContextResponse>(`/api/rollbacks/${id}/execution-context`);
}

// ============ T-C: Deploy Config Check API (Contract 2.1, 3.1-3.3) ============

export interface DeployConfigItem {
  configKey: string;
  configValue?: string;
  valueType: 'PLAIN' | 'SECRET_REF';
  required: boolean;
  hasValue: boolean;
  source?: 'APP' | 'ENV' | 'SERVICE';
  /** 同键多条时运行期取值不确定，不计为已配置 */
  ambiguous?: boolean;
  duplicateCount?: number;
}

export interface DeployConfigServiceCheckItem {
  serviceId: number;
  serviceName: string;
  complete: boolean;
  missingKeys?: string[];
  warnings?: string[];
  configItems?: DeployConfigItem[];
}

export interface DeployConfigCheckResponse {
  appId: number;
  appName: string;
  envId: number;
  envName: string;
  overallComplete: boolean;
  services: DeployConfigServiceCheckItem[];
  missingKeys?: string[];
  warnings?: string[];
  checkedAt: string;
}

export interface DeployConfigCheckParams {
  appId: number;
  envId: number;
  serviceId?: number;
}

export function checkDeployConfig(params: DeployConfigCheckParams): Promise<DeployConfigCheckResponse> {
  const query = new URLSearchParams();
  query.set('appId', String(params.appId));
  query.set('envId', String(params.envId));
  if (params.serviceId !== undefined) {
    query.set('serviceId', String(params.serviceId));
  }
  return get<DeployConfigCheckResponse>(`/api/releases/deploy-config/check?${query.toString()}`);
}

// ============ T-D: Rollback Candidate APIs ============

export interface RollbackCandidateItem {
  versionId: number;
  versionNo: string;
  deployedAt?: string;
  rollbackCount: number;
  isCurrent: boolean;
  recommendationScore: number;
  recommendationReason?: string;
}

export interface RollbackCandidatePageResponse {
  records: RollbackCandidateItem[];
  total: number;
  pageNum: number;
  pageSize: number;
  currentVersionId: number;
  currentVersionNo: string;
}

export function getRollbackCandidates(
  releaseOrderId: number | string,
  params?: { pageNum?: number; pageSize?: number }
): Promise<RollbackCandidatePageResponse> {
  const query = new URLSearchParams();
  if (params?.pageNum) query.set('pageNum', String(params.pageNum));
  if (params?.pageSize) query.set('pageSize', String(params.pageSize));
  const queryStr = query.toString();
  const path = `/api/releases/${releaseOrderId}/rollback-candidates${queryStr ? `?${queryStr}` : ''}`;
  return get<RollbackCandidatePageResponse>(path);
}

// ============ T-D: Rollback Target Update API ============
// 保存回滚目标只使用回滚单 ID；发布单 ID 仅用于查询候选版本，两者不得混用。

export interface UpdateRollbackTargetPayload {
  toVersionId: number;
}

export function updateRollbackTarget(
  rollbackOrderId: number | string,
  payload: UpdateRollbackTargetPayload
): Promise<boolean> {
  return put<boolean>(`/api/rollbacks/${rollbackOrderId}`, payload);
}

// ============ 回滚执行器支持边界（回滚单列表 VO 字段） ============
// 回滚目前仅支持 SSH 路径；后端按原发布单的执行器类型判定并下发以下字段。
export interface RollbackOrderExecutorSupport {
  /** 原发布单的执行器类型；无法判定时为空 */
  executorType?: string | null;
  /** 该回滚单能否按既有 SSH 回滚路径执行；缺省/为空表示无法判定，由后端门禁最终裁定 */
  rollbackSupported?: boolean | null;
  /** rollbackSupported=false 时的后端拒绝原因 */
  rollbackUnsupportedReason?: string | null;
}
