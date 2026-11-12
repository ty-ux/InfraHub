import type { PageResponse } from '@/types/api';
import { get, post, put, del } from '@/utils/http';

export interface MonitorMetricTarget {
  id: number;
  targetType: string;
  targetId: number;
  monitorKey: string;
  scrapeConfig: string | null;
  status: string;
  createdAt: string;
}

export interface MonitorSnapshot {
  id: number;
  targetType: string;
  targetId: number;
  snapshotTime: string;
  metricPayload: string;
  source: string | null;
  createdAt: string;
}

export interface MonitorAlertRule {
  id: number;
  ruleName: string;
  targetType: string;
  targetId: number | null;
  targetGroupCode: string | null;
  metricName: string;
  compareOp: string;
  thresholdValue: number;
  durationSeconds: number;
  level: string;
  notifyChannel: string | null;
  enabled: boolean;
  createdAt: string;
}

export interface MonitorAlertEvent {
  id: number;
  ruleId: number;
  targetType: string;
  targetId: number;
  alertStatus: string;
  alertValue: number | null;
  summary: string | null;
  firedAt: string;
  recoveredAt: string | null;
  handleBy: number | null;
  handleNote: string | null;
}

export interface MonitorHealthCheck {
  id: number;
  targetType: string;
  targetId: number;
  checkType: string;
  checkEndpoint: string | null;
  expectedResult: string | null;
  timeoutSeconds: number;
  enabled: boolean;
  createdAt: string;
}

export interface MonitorMetricTargetQuery {
  pageNum?: number;
  pageSize?: number;
  targetType?: string;
  targetId?: number;
  status?: string;
  keyword?: string;
}

export interface MonitorSnapshotQuery {
  pageNum?: number;
  pageSize?: number;
  snapshotId?: number;
  targetType?: string;
  targetId?: number;
  source?: string;
}

export interface MonitorAlertRuleQuery {
  pageNum?: number;
  pageSize?: number;
  targetType?: string;
  targetId?: number;
  level?: string;
  enabled?: boolean;
  keyword?: string;
}

export interface MonitorAlertEventQuery {
  pageNum?: number;
  pageSize?: number;
  eventId?: number;
  targetType?: string;
  targetId?: number;
  alertStatus?: string;
  ruleId?: number;
}

export interface MonitorHealthCheckQuery {
  pageNum?: number;
  pageSize?: number;
  targetType?: string;
  targetId?: number;
  checkType?: string;
  enabled?: boolean;
}

export const pageMonitorTargets = (params: MonitorMetricTargetQuery) =>
  get<PageResponse<MonitorMetricTarget>>('/api/monitor/targets', params);

export const pageMonitorSnapshots = (params: MonitorSnapshotQuery) =>
  get<PageResponse<MonitorSnapshot>>('/api/monitor/snapshots', params);

export const pageMonitorAlertRules = (params: MonitorAlertRuleQuery) =>
  get<PageResponse<MonitorAlertRule>>('/api/monitor/alert-rules', params);

export const pageMonitorAlertEvents = (params: MonitorAlertEventQuery) =>
  get<PageResponse<MonitorAlertEvent>>('/api/monitor/alert-events', params);

export const pageMonitorHealthChecks = (params: MonitorHealthCheckQuery) =>
  get<PageResponse<MonitorHealthCheck>>('/api/monitor/health-checks', params);

// Alert Rule Write Operations
export interface CreateAlertRuleRequest {
  ruleName: string;
  targetType: string;
  targetId?: number;
  targetGroupCode?: string;
  metricName: string;
  compareOp: string;
  thresholdValue: number;
  durationSeconds: number;
  level: string;
  notifyChannel?: string;
  enabled: boolean;
}

export interface UpdateAlertRuleRequest {
  ruleName: string;
  targetType: string;
  targetId?: number;
  targetGroupCode?: string;
  metricName: string;
  compareOp: string;
  thresholdValue: number;
  durationSeconds: number;
  level: string;
  notifyChannel?: string;
  enabled: boolean;
}

export const createAlertRule = (data: CreateAlertRuleRequest) =>
  post<number>('/api/monitor/alert-rules', data);

export const updateAlertRule = (id: number, data: UpdateAlertRuleRequest) =>
  put<void>(`/api/monitor/alert-rules/${id}`, data);

export const deleteAlertRule = (id: number) =>
  del<void>(`/api/monitor/alert-rules/${id}`);

export const toggleAlertRuleEnabled = (id: number, enabled: boolean) =>
  put<{ approvalId?: number; directExecuted?: boolean }>(`/api/monitor/alert-rules/${id}/enabled?enabled=${enabled}`);

// Health Check Write Operations
export interface CreateHealthCheckRequest {
  targetType: string;
  targetId: number;
  checkType: string;
  checkEndpoint?: string;
  expectedResult?: string;
  timeoutSeconds: number;
  enabled: boolean;
}

export interface UpdateHealthCheckRequest {
  targetType: string;
  targetId: number;
  checkType: string;
  checkEndpoint?: string;
  expectedResult?: string;
  timeoutSeconds: number;
  enabled: boolean;
}

export const createHealthCheck = (data: CreateHealthCheckRequest) =>
  post<number>('/api/monitor/health-checks', data);

export const updateHealthCheck = (id: number, data: UpdateHealthCheckRequest) =>
  put<void>(`/api/monitor/health-checks/${id}`, data);

export const deleteHealthCheck = (id: number) =>
  del<void>(`/api/monitor/health-checks/${id}`);

export const toggleHealthCheckEnabled = (id: number, enabled: boolean) =>
  put<{ approvalId?: number; directExecuted?: boolean }>(`/api/monitor/health-checks/${id}/enabled?enabled=${enabled}`);

// Manual Snapshot Operations
export interface CreateManualSnapshotRequest {
  targetType: string;
  targetId: number;
  metricPayload: string;
  source?: string;
}

export const createManualSnapshot = (data: CreateManualSnapshotRequest) =>
  post<number>('/api/monitor/snapshots/manual', data);

// Rule Evaluation Operations
export interface EvaluateMonitorRulesRequest {
  targetType: string;
  targetId: number;
  metricPayload: string;
}

export interface HitRuleSummary {
  ruleId: number;
  ruleName: string;
  metricName: string;
  compareOp: string;
  thresholdValue: number;
  actualValue: number;
}

export interface MonitorEvaluationResult {
  targetType: string;
  targetId: number;
  hitCount: number;
  eventIds: number[];
  hitRules: HitRuleSummary[];
  recoveredCount: number;
  recoveredEventIds: number[];
}

export const evaluateRules = (data: EvaluateMonitorRulesRequest) =>
  post<MonitorEvaluationResult>('/api/monitor/evaluations', data);

// Collection Operations
export interface CollectMonitorTargetResponse {
  snapshotId: number | null;
  targetType: string;
  targetId: number;
  source: string;
  success: boolean;
  message: string;
}

export const collectMonitorTarget = (targetId: number) =>
  post<CollectMonitorTargetResponse>(`/api/monitor/targets/${targetId}/collect`);

// Run-Once Batch Collection Operations
export interface RunOnceMonitorCollectionRequest {
  targetType?: string;
}

export interface MonitorCollectionItemResult {
  monitorTargetId: number;
  targetType: string;
  targetId: number;
  success: boolean;
  snapshotId: number | null;
  message: string;
}

export interface RunOnceMonitorCollectionResponse {
  totalCount: number;
  successCount: number;
  failureCount: number;
  results: MonitorCollectionItemResult[];
}

export const runOnceMonitorCollection = (data?: RunOnceMonitorCollectionRequest) =>
  post<RunOnceMonitorCollectionResponse>('/api/monitor/collections/run-once', data ?? {});

// Alert Event Resolve Operations
export interface ResolveAlertEventRequest {
  handleNote?: string;
}

export interface ResolveAlertEventResponse {
  eventId: number;
  alertStatus: string;
  recoveredAt: string | null;
  handleBy: number | null;
  handleNote: string | null;
}

export const resolveAlertEvent = (id: number, data?: ResolveAlertEventRequest) =>
  post<ResolveAlertEventResponse>(`/api/monitor/alert-events/${id}/resolve`, data ?? {});

// Scheduled Collection Status
export interface MonitorCollectionScheduleStatus {
  enabled: boolean;
  fixedDelayMs: number;
  lastRunAt: string | null;
  lastTotalCount: number;
  lastSuccessCount: number;
  lastFailureCount: number;
  lastMessage: string;
}

export const getScheduleCollectionStatus = () =>
  get<MonitorCollectionScheduleStatus>('/api/monitor/collections/schedule/status');

// ============ E3.2: 通知记录 API ============

export interface MonitorNotificationRecord {
  id: number;
  alertEventId: number;
  alertRuleId: number;
  channelType: string;
  channelTypeLabel: string;
  channelTarget: string;
  notificationStatus: string;
  notificationStatusLabel: string;
  responseStatus?: number;
  errorMessage?: string;
  sentAt?: string;
  createdAt: string;
}

export function getEventNotifications(eventId: number | string) {
  return get<MonitorNotificationRecord[]>(`/api/monitor/alert-events/${eventId}/notifications`);
}

export function getRuleNotifications(ruleId: number | string, limit = 20) {
  return get<MonitorNotificationRecord[]>(`/api/monitor/alert-rules/${ruleId}/notifications?limit=${limit}`);
}
