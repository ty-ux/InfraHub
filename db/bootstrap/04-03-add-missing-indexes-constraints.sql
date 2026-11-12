-- 04-03: Add missing indexes
-- Stage X P0-A: 数据库基线修复 - 增量索引
-- 依据：运行时 Mapper @Select 的 WHERE/ORDER BY/LIMIT 列（字段序 = 查询条件序），与既有 idx_ 命名约定一致
-- 幂等：全部 CREATE INDEX IF NOT EXISTS；无 DROP/TRUNCATE
-- 编码：UTF-8

BEGIN;

-- ========== 审批（V1 C） ==========
-- ApprovalRequestService 分页查询：bizType/bizId/status/applicantId 等值过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_approval_request_biz ON approval_request (biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_approval_request_status ON approval_request (status);
CREATE INDEX IF NOT EXISTS idx_approval_request_applicant ON approval_request (applicant_id);
CREATE INDEX IF NOT EXISTS idx_approval_request_created ON approval_request (created_at DESC);

-- ========== 主机 SSH 配置（V2 G） ==========
-- AssetHostSshConfigMapper 按主机查询
CREATE INDEX IF NOT EXISTS idx_asset_host_ssh_config_host ON asset_host_ssh_config (host_id);

-- ========== 容量策略（V2 H） ==========
-- CapacityPolicyService / DefaultCapacityThresholdPolicyService：resourcePoolId/envId/metricType/enabled 过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_capacity_policy_pool_metric ON resource_capacity_policy (resource_pool_id, metric_type);
CREATE INDEX IF NOT EXISTS idx_capacity_policy_env_metric ON resource_capacity_policy (env_id, metric_type);
CREATE INDEX IF NOT EXISTS idx_capacity_policy_enabled ON resource_capacity_policy (enabled);
CREATE INDEX IF NOT EXISTS idx_capacity_policy_created ON resource_capacity_policy (created_at DESC);

-- ========== 执行器配置（V1 F） ==========
-- ReleaseExecutorConfigMapper：envId+enabled 过滤按 id 排序；envId+executorName 等值（uk 已覆盖）；id+enabled
CREATE INDEX IF NOT EXISTS idx_release_executor_config_env_enabled ON release_executor_config (env_id, enabled);

-- ========== 监控通知记录（V1 E3.2 / V2 Q） ==========
-- MonitorNotificationRecordMapper：alertEventId/alertRuleId/channelType/notificationStatus 过滤 + LIMIT
CREATE INDEX IF NOT EXISTS idx_monitor_notification_record_event ON monitor_notification_record (alert_event_id);
CREATE INDEX IF NOT EXISTS idx_monitor_notification_record_rule ON monitor_notification_record (alert_rule_id);
CREATE INDEX IF NOT EXISTS idx_monitor_notification_record_channel ON monitor_notification_record (channel_type);
CREATE INDEX IF NOT EXISTS idx_monitor_notification_record_status ON monitor_notification_record (notification_status);
CREATE INDEX IF NOT EXISTS idx_monitor_notification_record_created ON monitor_notification_record (created_at DESC);

-- ========== 治理告警规则（V2 M） ==========
-- GovernanceAlertRuleMapper：enabled 过滤 + 维度 matcher（envId/resourcePoolId/serviceId 为空分支）+ metricType
CREATE INDEX IF NOT EXISTS idx_governance_alert_rule_enabled ON release_governance_alert_rule (enabled);
CREATE INDEX IF NOT EXISTS idx_governance_alert_rule_metric ON release_governance_alert_rule (metric_type);
CREATE INDEX IF NOT EXISTS idx_governance_alert_rule_dim ON release_governance_alert_rule (env_id, resource_pool_id, service_id);

-- ========== 治理告警记录（V2 M/N/O/P） ==========
-- GovernanceAlertRecordMapper：metricType/alertLevel/alertStatus/envId/resourcePoolId/serviceId 过滤 + createdAt 倒序；
-- assigneeId 待办 + assignedAt 倒序；逾期分派（assignment_due_at < NOW() 且未解决）；已升级查询
CREATE INDEX IF NOT EXISTS idx_governance_alert_record_query ON release_governance_alert_record (metric_type, alert_level, alert_status, env_id, resource_pool_id, service_id);
CREATE INDEX IF NOT EXISTS idx_governance_alert_record_created ON release_governance_alert_record (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_alert_record_assignee ON release_governance_alert_record (assignee_id, assigned_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_alert_record_due ON release_governance_alert_record (assignment_due_at) WHERE alert_status <> 'RESOLVED';
CREATE INDEX IF NOT EXISTS idx_governance_alert_record_escalated ON release_governance_alert_record (escalated) WHERE escalated = TRUE;

-- ========== 告警分派/升级/处理记录（V2 N/O） ==========
-- 按告警反查历史 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_governance_assignment_alert ON release_governance_alert_assignment_record (alert_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_escalation_alert ON release_governance_alert_escalation_record (alert_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_handling_alert ON release_governance_alert_handling_record (alert_id, created_at DESC);

-- ========== 告警通知记录（V2 Q） ==========
-- GovernanceAlertNotificationRecordMapper：deleted=0 + alertRecordId/channelType/notificationStatus/targetUserId 过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_notification_record_query ON release_governance_alert_notification_record (deleted, alert_record_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_channel ON release_governance_alert_notification_record (channel_type);
CREATE INDEX IF NOT EXISTS idx_notification_record_status ON release_governance_alert_notification_record (notification_status);
CREATE INDEX IF NOT EXISTS idx_notification_record_target ON release_governance_alert_notification_record (target_user_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_created ON release_governance_alert_notification_record (created_at DESC);

-- ========== 通知跟进/回执（V2 R） ==========
-- GovernanceAlertNotificationFollowupMapper：deleted=0 + notificationRecordId/followUpType/receiptStatus/followUpStatus/alertRecordId 过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_followup_notification ON release_governance_alert_notification_followup (deleted, notification_record_id);
CREATE INDEX IF NOT EXISTS idx_followup_type ON release_governance_alert_notification_followup (follow_up_type);
CREATE INDEX IF NOT EXISTS idx_followup_receipt ON release_governance_alert_notification_followup (receipt_status);
CREATE INDEX IF NOT EXISTS idx_followup_status ON release_governance_alert_notification_followup (follow_up_status);
CREATE INDEX IF NOT EXISTS idx_followup_alert ON release_governance_alert_notification_followup (alert_record_id);
CREATE INDEX IF NOT EXISTS idx_followup_created ON release_governance_alert_notification_followup (created_at DESC);

-- ========== 治理运营报告（V2 M/L） ==========
-- GovernanceReportMapper：1=1 动态过滤（类型/环境/时间范围）+ generatedAt 倒序；reportNo 唯一（uk 已覆盖）
CREATE INDEX IF NOT EXISTS idx_governance_report_type ON release_governance_report (report_type);
CREATE INDEX IF NOT EXISTS idx_governance_report_dim ON release_governance_report (env_id, resource_pool_id, service_id);
CREATE INDEX IF NOT EXISTS idx_governance_report_generated ON release_governance_report (generated_at DESC);

-- ========== 灰度发布（V1 E） ==========
-- ReleaseCanaryPlanMapper.findByReleaseOrderId
CREATE INDEX IF NOT EXISTS idx_release_canary_plan_order ON release_canary_plan (release_order_id);
-- ReleaseCanaryBatchMapper：planId + status
CREATE INDEX IF NOT EXISTS idx_release_canary_batch_plan ON release_canary_batch (plan_id);
CREATE INDEX IF NOT EXISTS idx_release_canary_batch_plan_status ON release_canary_batch (plan_id, status);
-- ReleaseCanaryGateEvaluationMapper：batchId + evaluatedAt 倒序
CREATE INDEX IF NOT EXISTS idx_canary_gate_eval_batch ON release_canary_gate_evaluation (batch_id, evaluated_at DESC);

-- ========== 资源治理建议（V2 H+1） ==========
-- ResourceGovernanceSuggestionMapper：type/source/riskLevel/status/维度 多条件过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_type ON release_resource_governance_suggestion (suggestion_type);
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_source ON release_resource_governance_suggestion (source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_risk ON release_resource_governance_suggestion (risk_level);
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_status ON release_resource_governance_suggestion (status);
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_dim ON release_resource_governance_suggestion (resource_pool_id, env_id);
CREATE INDEX IF NOT EXISTS idx_governance_suggestion_created ON release_resource_governance_suggestion (created_at DESC);

-- ========== 治理动作计划（V2 I） ==========
-- ActionPlanMapper：suggestionId/status/维度 过滤 + createdAt 倒序
CREATE INDEX IF NOT EXISTS idx_governance_action_plan_suggestion ON release_resource_governance_action_plan (suggestion_id);
CREATE INDEX IF NOT EXISTS idx_governance_action_plan_status ON release_resource_governance_action_plan (status);
CREATE INDEX IF NOT EXISTS idx_governance_action_plan_dim ON release_resource_governance_action_plan (resource_pool_id, env_id);
CREATE INDEX IF NOT EXISTS idx_governance_action_plan_created ON release_resource_governance_action_plan (created_at DESC);

-- ========== 治理动作执行（V2 J） ==========
-- ActionExecutionMapper：planId/status/actionType 过滤 + createdAt 倒序；执行编号唯一（uk 已覆盖）
CREATE INDEX IF NOT EXISTS idx_governance_action_exec_plan ON release_resource_governance_action_execution (plan_id);
CREATE INDEX IF NOT EXISTS idx_governance_action_exec_status ON release_resource_governance_action_execution (status);
CREATE INDEX IF NOT EXISTS idx_governance_action_exec_type ON release_resource_governance_action_execution (action_type);
CREATE INDEX IF NOT EXISTS idx_governance_action_exec_created ON release_resource_governance_action_execution (created_at DESC);

-- ========== 治理记录（V2 G/K，时间线聚合） ==========
-- ResourceGovernanceRecordMapper：事件类型/维度/编号过滤 + createdAt 倒序（queryTimelineByActionPlanNo/ActionExecutionNo）
CREATE INDEX IF NOT EXISTS idx_governance_record_event ON release_resource_governance_record (event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_governance_record_action_plan ON release_resource_governance_record (action_plan_no);
CREATE INDEX IF NOT EXISTS idx_governance_record_action_exec ON release_resource_governance_record (action_execution_no);
CREATE INDEX IF NOT EXISTS idx_governance_record_suggestion ON release_resource_governance_record (suggestion_no);
CREATE INDEX IF NOT EXISTS idx_governance_record_subject ON release_resource_governance_record (subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_governance_record_created ON release_resource_governance_record (created_at DESC);

COMMIT;