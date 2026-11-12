-- 04-02: Add missing columns
-- Stage X P0-A: 数据库基线修复 - 既有表字段缺口（B2/B3 修复）
-- 依据：运行时实体字段定义为准（实体存在但既有 DDL 缺列）；
--       release_order / runtime_deploy_record 参照 db/flyway/examples/V3__add_release_order_operator_columns.example.sql 核对。
--       文档注记：审计第 4/13 章引用该 example 为 executor 两列，实际 example 内容为 operator_name/operator_email 两列；
--       最终列集合以实体为准（executor_type/executor_config_id 为实体声明），此处记录该文档冲突。
-- 幂等：ADD COLUMN IF NOT EXISTS；无 DROP/TRUNCATE
-- 编码：UTF-8

BEGIN;

-- B2: sys_user.last_login_at（AuthService.login 成功后 UPDATE 该列）
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
COMMENT ON COLUMN sys_user.last_login_at IS '最后登录时间（登录成功后回写）';

-- B3: release_order.executor_type / executor_config_id（ReleaseOrder 实体声明，Docker/K8s 执行链路读写）
ALTER TABLE release_order ADD COLUMN IF NOT EXISTS executor_type VARCHAR(32);
ALTER TABLE release_order ADD COLUMN IF NOT EXISTS executor_config_id BIGINT;
COMMENT ON COLUMN release_order.executor_type IS '执行器类型：SSH/DOCKER/KUBERNETES（为空表示默认 SSH 链路）';
COMMENT ON COLUMN release_order.executor_config_id IS '执行器配置 ID（release_executor_config.id，可空，应用自校验）';

-- B3: runtime_deploy_record.operation_type / rollback_order_id（RuntimeDeployRecord 实体声明，部署类型与回滚关联）
ALTER TABLE runtime_deploy_record ADD COLUMN IF NOT EXISTS operation_type VARCHAR(32);
ALTER TABLE runtime_deploy_record ADD COLUMN IF NOT EXISTS rollback_order_id BIGINT;
COMMENT ON COLUMN runtime_deploy_record.operation_type IS '部署类型：DEPLOY/ROLLBACK';
COMMENT ON COLUMN runtime_deploy_record.rollback_order_id IS '回滚关联的发布单 ID（回滚部署时回填，可空）';

COMMIT;