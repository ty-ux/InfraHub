BEGIN;

INSERT INTO sys_user (
    id,
    username,
    display_name,
    email,
    mobile,
    auth_type,
    password_hash,
    status
) VALUES (
    1,
    'admin',
    '平台管理员',
    'admin@infrahub.local',
    NULL,
    'LOCAL',
    'CHANGE_ME_HASH',
    'ENABLED'
)
ON CONFLICT (username) DO UPDATE
SET
    display_name = EXCLUDED.display_name,
    email = EXCLUDED.email,
    auth_type = EXCLUDED.auth_type,
    updated_at = NOW();

INSERT INTO sys_role (id, role_code, role_name, status)
VALUES
    (1, 'ADMIN', '平台管理员', 'ENABLED'),
    (2, 'DEVOPS', '运维工程师', 'ENABLED'),
    (3, 'DEVELOPER', '开发工程师', 'ENABLED')
ON CONFLICT (role_code) DO UPDATE
SET
    role_name = EXCLUDED.role_name,
    status = EXCLUDED.status,
    updated_at = NOW();

INSERT INTO sys_permission (id, perm_code, perm_name, resource_type, action)
VALUES
    (1, 'APP_VIEW', '查看应用', 'APP', 'VIEW'),
    (2, 'PIPELINE_EXECUTE', '执行流水线', 'PIPELINE', 'EXECUTE'),
    (3, 'RELEASE_EXECUTE', '执行发布', 'RELEASE', 'EXECUTE'),
    (4, 'ROLLBACK_EXECUTE', '执行回滚', 'ROLLBACK', 'EXECUTE'),
    (5, 'RESOURCE_ALLOCATE', '资源分配', 'RESOURCE', 'ALLOCATE'),
    (6, 'MONITOR_VIEW', '查看监控', 'MONITOR', 'VIEW'),
    (7, 'SYSTEM_ADMIN', '系统管理', 'SYSTEM', 'ADMIN')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO sys_user_role (id, user_id, role_id)
VALUES
    (1, 1, 1)
ON CONFLICT ON CONSTRAINT uk_sys_user_role DO UPDATE
SET updated_at = NOW();

INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES
    (1, 1, 1),
    (2, 1, 2),
    (3, 1, 3),
    (4, 1, 4),
    (5, 1, 5),
    (6, 1, 6),
    (7, 1, 7)
ON CONFLICT ON CONSTRAINT uk_sys_role_permission DO UPDATE
SET updated_at = NOW();

INSERT INTO sys_enum (id, enum_type, enum_code, enum_name, sort_order, status)
VALUES
    (1, 'ENV_TYPE', 'DEV', '开发环境', 1, 'ENABLED'),
    (2, 'ENV_TYPE', 'TEST', '测试环境', 2, 'ENABLED'),
    (3, 'ENV_TYPE', 'UAT', '预发布环境', 3, 'ENABLED'),
    (4, 'ENV_TYPE', 'PROD', '生产环境', 4, 'ENABLED'),
    (5, 'RELEASE_STATUS', 'CREATED', '已创建', 1, 'ENABLED'),
    (6, 'RELEASE_STATUS', 'APPROVING', '审批中', 2, 'ENABLED'),
    (7, 'RELEASE_STATUS', 'APPROVED', '已审批', 3, 'ENABLED'),
    (8, 'RELEASE_STATUS', 'REJECTED', '已拒绝', 4, 'ENABLED'),
    (9, 'RELEASE_STATUS', 'RUNNING', '执行中', 5, 'ENABLED'),
    (10, 'RELEASE_STATUS', 'SUCCESS', '成功', 6, 'ENABLED'),
    (11, 'RELEASE_STATUS', 'FAILED', '失败', 7, 'ENABLED'),
    (12, 'RELEASE_STATUS', 'ROLLED_BACK', '已回滚', 8, 'ENABLED')
ON CONFLICT (enum_type, enum_code) DO UPDATE
SET
    enum_name = EXCLUDED.enum_name,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = NOW();

INSERT INTO base_env (
    id,
    env_code,
    env_name,
    env_type,
    region,
    namespace,
    description,
    status,
    created_by,
    updated_by
) VALUES
    (1, 'dev', '开发环境', 'DEV', 'default', 'dev', '默认开发环境', 'ENABLED', 1, 1),
    (2, 'test', '测试环境', 'TEST', 'default', 'test', '默认测试环境', 'ENABLED', 1, 1),
    (3, 'uat', '预发布环境', 'UAT', 'default', 'uat', '默认预发布环境', 'ENABLED', 1, 1),
    (4, 'prod', '生产环境', 'PROD', 'default', 'prod', '默认生产环境', 'ENABLED', 1, 1)
ON CONFLICT (env_code) DO UPDATE
SET
    env_name = EXCLUDED.env_name,
    env_type = EXCLUDED.env_type,
    region = EXCLUDED.region,
    namespace = EXCLUDED.namespace,
    description = EXCLUDED.description,
    status = EXCLUDED.status,
    updated_by = EXCLUDED.updated_by,
    updated_at = NOW();

SELECT setval(pg_get_serial_sequence('sys_user', 'id'), COALESCE((SELECT MAX(id) FROM sys_user), 1), true);
SELECT setval(pg_get_serial_sequence('sys_role', 'id'), COALESCE((SELECT MAX(id) FROM sys_role), 1), true);
SELECT setval(pg_get_serial_sequence('sys_permission', 'id'), COALESCE((SELECT MAX(id) FROM sys_permission), 1), true);
SELECT setval(pg_get_serial_sequence('sys_user_role', 'id'), COALESCE((SELECT MAX(id) FROM sys_user_role), 1), true);
SELECT setval(pg_get_serial_sequence('sys_role_permission', 'id'), COALESCE((SELECT MAX(id) FROM sys_role_permission), 1), true);
SELECT setval(pg_get_serial_sequence('sys_enum', 'id'), COALESCE((SELECT MAX(id) FROM sys_enum), 1), true);
SELECT setval(pg_get_serial_sequence('base_env', 'id'), COALESCE((SELECT MAX(id) FROM base_env), 1), true);

COMMIT;
