-- InfraHub database acceptance checks - Stage X P0-A increments (04-*)
-- Run AFTER 03-db-acceptance.sql on a database initialized with 00-post/01/02-init/02-repair/04-*.
-- Purpose: verify the 04-* incremental DDL/DML (B1/B2/B3/B4 fixes) actually landed.
-- Pattern mirrors 03-db-acceptance.sql: existence checks, RAISE EXCEPTION on failure -> non-zero psql exit.
--
-- Example:
--   psql -U postgres -d infrahub -f 04-05-add-missing-tables-acceptance.sql

SELECT current_database() AS current_db, current_user AS current_user, now() AS checked_at;

DO $$
BEGIN
    -- B1: 21 张新增表（20 张实体表 + release_governance_alert_notification_record）
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'approval_policy') THEN
        RAISE EXCEPTION 'Acceptance failed: table approval_policy is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'approval_request') THEN
        RAISE EXCEPTION 'Acceptance failed: table approval_request is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'asset_host_ssh_config') THEN
        RAISE EXCEPTION 'Acceptance failed: table asset_host_ssh_config is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'resource_capacity_policy') THEN
        RAISE EXCEPTION 'Acceptance failed: table resource_capacity_policy is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_executor_config') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_executor_config is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'monitor_notification_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table monitor_notification_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_rule') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_rule is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_assignment_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_assignment_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_escalation_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_escalation_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_handling_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_handling_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_notification_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_notification_record is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_alert_notification_followup') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_alert_notification_followup is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_governance_report') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_governance_report is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_canary_plan') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_canary_plan is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_canary_batch') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_canary_batch is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_canary_gate_evaluation') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_canary_gate_evaluation is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_resource_governance_suggestion') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_resource_governance_suggestion is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_resource_governance_action_plan') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_resource_governance_action_plan is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_resource_governance_action_execution') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_resource_governance_action_execution is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_resource_governance_record') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_resource_governance_record is missing';
    END IF;
END $$;

DO $$
BEGIN
    -- B1: 编号序列
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'seq_alert_record_no' AND relkind = 'S') THEN
        RAISE EXCEPTION 'Acceptance failed: sequence seq_alert_record_no is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'seq_followup_no' AND relkind = 'S') THEN
        RAISE EXCEPTION 'Acceptance failed: sequence seq_followup_no is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'seq_notification_record_no' AND relkind = 'S') THEN
        RAISE EXCEPTION 'Acceptance failed: sequence seq_notification_record_no is missing';
    END IF;
END $$;

DO $$
BEGIN
    -- B2/B3: 既有表新增列
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'sys_user' AND column_name = 'last_login_at'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: sys_user.last_login_at is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'release_order' AND column_name = 'executor_type'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: release_order.executor_type is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'release_order' AND column_name = 'executor_config_id'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: release_order.executor_config_id is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'runtime_deploy_record' AND column_name = 'operation_type'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: runtime_deploy_record.operation_type is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'runtime_deploy_record' AND column_name = 'rollback_order_id'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: runtime_deploy_record.rollback_order_id is missing';
    END IF;
    -- 04-02 幂等标记：列存在则该步已执行（存在性即通过，不校验列类型以外的属性）
END $$;

DO $$
BEGIN
    -- B1: 唯一约束（uk_）
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'approval_policy' AND c.conname = 'uk_approval_policy_biz_action'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_approval_policy_biz_action is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_executor_config' AND c.conname = 'uk_release_executor_config_env_name'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_release_executor_config_env_name is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_governance_alert_record' AND c.conname = 'uk_governance_alert_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_governance_alert_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_governance_alert_notification_record' AND c.conname = 'uk_notification_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_notification_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_governance_alert_notification_followup' AND c.conname = 'uk_followup_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_followup_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_governance_report' AND c.conname = 'uk_governance_report_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_governance_report_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_resource_governance_suggestion' AND c.conname = 'uk_governance_suggestion_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_governance_suggestion_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_resource_governance_action_plan' AND c.conname = 'uk_governance_action_plan_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_governance_action_plan_no is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'release_resource_governance_action_execution' AND c.conname = 'uk_governance_action_execution_no'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: uk_governance_action_execution_no is missing';
    END IF;
END $$;

DO $$
BEGIN
    -- B1: 代表性索引（idx_，抽查与 04-03 声明一致的命名）
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_release_executor_config_env_enabled') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_release_executor_config_env_enabled is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_governance_alert_record_query') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_governance_alert_record_query is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_governance_alert_record_assignee') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_governance_alert_record_assignee is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_governance_alert_record_due') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_governance_alert_record_due is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_notification_record_query') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_notification_record_query is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_followup_notification') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_followup_notification is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_release_canary_batch_plan_status') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_release_canary_batch_plan_status is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_canary_gate_eval_batch') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_canary_gate_eval_batch is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_governance_record_event') THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_governance_record_event is missing';
    END IF;
END $$;

DO $$
BEGIN
    -- B4: sys_enum MONITOR_TARGET_TYPE 参考数据种子
    IF NOT EXISTS (
        SELECT 1 FROM sys_enum WHERE enum_type = 'MONITOR_TARGET_TYPE' AND enum_code = 'HOST' AND status = 'ENABLED'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: sys_enum MONITOR_TARGET_TYPE/HOST is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM sys_enum WHERE enum_type = 'MONITOR_TARGET_TYPE' AND enum_code = 'SERVICE' AND status = 'ENABLED'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: sys_enum MONITOR_TARGET_TYPE/SERVICE is missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM sys_enum WHERE enum_type = 'MONITOR_TARGET_TYPE' AND enum_code = 'INSTANCE' AND status = 'ENABLED'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: sys_enum MONITOR_TARGET_TYPE/INSTANCE is missing';
    END IF;
END $$;

-- 汇总统计：新增表总数与新增列总数
SELECT count(*) AS new_table_count
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN ('approval_policy','approval_request','asset_host_ssh_config','resource_capacity_policy',
                    'release_executor_config','monitor_notification_record','release_governance_alert_rule',
                    'release_governance_alert_record','release_governance_alert_assignment_record',
                    'release_governance_alert_escalation_record','release_governance_alert_handling_record',
                    'release_governance_alert_notification_record','release_governance_alert_notification_followup',
                    'release_governance_report','release_canary_plan','release_canary_batch',
                    'release_canary_gate_evaluation','release_resource_governance_suggestion',
                    'release_resource_governance_action_plan','release_resource_governance_action_execution',
                    'release_resource_governance_record');