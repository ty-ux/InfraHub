ALTER TABLE sys_role ADD COLUMN app_scope VARCHAR(12) NOT NULL DEFAULT 'ALL'
    CHECK (app_scope IN ('ALL', 'SELECTED'));

CREATE TABLE sys_role_app (
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    app_id BIGINT NOT NULL REFERENCES base_app(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, app_id)
);

SELECT setval(pg_get_serial_sequence('sys_permission', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM sys_permission), nextval(pg_get_serial_sequence('sys_permission', 'id'))));
SELECT setval(pg_get_serial_sequence('sys_role', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM sys_role), nextval(pg_get_serial_sequence('sys_role', 'id'))));
SELECT setval(pg_get_serial_sequence('sys_role_permission', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM sys_role_permission), nextval(pg_get_serial_sequence('sys_role_permission', 'id'))));
SELECT setval(pg_get_serial_sequence('sys_user_role', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM sys_user_role), nextval(pg_get_serial_sequence('sys_user_role', 'id'))));

INSERT INTO sys_permission (perm_code, perm_name, resource_type, action) VALUES
    ('PIPELINE_VIEW', '查看流水线及执行记录', 'PIPELINE', 'VIEW'),
    ('RELEASE_VIEW', '查看发布与运行资源', 'RELEASE', 'VIEW'),
    ('RELEASE_CREATE', '创建发布单', 'RELEASE', 'CREATE'),
    ('RELEASE_CANCEL', '取消发布单', 'RELEASE', 'CANCEL'),
    ('ROLLBACK_VIEW', '查看回滚', 'ROLLBACK', 'VIEW'),
    ('ROLLBACK_CREATE', '创建回滚单', 'ROLLBACK', 'CREATE'),
    ('APPROVAL_VIEW', '查看审批', 'APPROVAL', 'VIEW')
ON CONFLICT (perm_code) DO NOTHING;

UPDATE sys_permission SET perm_name = CASE perm_code
    WHEN 'APP_VIEW' THEN '查看应用与服务'
    WHEN 'PIPELINE_EXECUTE' THEN '运行已配置的流水线'
    WHEN 'RELEASE_EXECUTE' THEN '执行发布'
    WHEN 'ROLLBACK_EXECUTE' THEN '执行回滚'
    WHEN 'MONITOR_VIEW' THEN '查看采集状态'
    WHEN 'SYSTEM_ADMIN' THEN '系统管理员'
    END
WHERE perm_code IN ('APP_VIEW', 'PIPELINE_EXECUTE', 'RELEASE_EXECUTE', 'ROLLBACK_EXECUTE', 'MONITOR_VIEW', 'SYSTEM_ADMIN');
