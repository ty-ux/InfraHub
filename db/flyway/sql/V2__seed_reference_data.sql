
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
    '骞冲彴绠＄悊鍛?,
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
    (1, 'ADMIN', '骞冲彴绠＄悊鍛?, 'ENABLED'),
    (2, 'DEVOPS', '杩愮淮宸ョ▼甯?, 'ENABLED'),
    (3, 'DEVELOPER', '寮€鍙戝伐绋嬪笀', 'ENABLED')
ON CONFLICT (role_code) DO UPDATE
SET
    role_name = EXCLUDED.role_name,
    status = EXCLUDED.status,
    updated_at = NOW();

INSERT INTO sys_permission (id, perm_code, perm_name, resource_type, action)
VALUES
    (1, 'APP_VIEW', '鏌ョ湅搴旂敤', 'APP', 'VIEW'),
    (2, 'PIPELINE_EXECUTE', '鎵ц娴佹按绾?, 'PIPELINE', 'EXECUTE'),
    (3, 'RELEASE_EXECUTE', '鎵ц鍙戝竷', 'RELEASE', 'EXECUTE'),
    (4, 'ROLLBACK_EXECUTE', '鎵ц鍥炴粴', 'ROLLBACK', 'EXECUTE'),
    (5, 'RESOURCE_ALLOCATE', '璧勬簮鍒嗛厤', 'RESOURCE', 'ALLOCATE'),
    (6, 'MONITOR_VIEW', '鏌ョ湅鐩戞帶', 'MONITOR', 'VIEW'),
    (7, 'SYSTEM_ADMIN', '绯荤粺绠＄悊', 'SYSTEM', 'ADMIN')
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
    (1, 'ENV_TYPE', 'DEV', '寮€鍙戠幆澧?, 1, 'ENABLED'),
    (2, 'ENV_TYPE', 'TEST', '娴嬭瘯鐜', 2, 'ENABLED'),
    (3, 'ENV_TYPE', 'UAT', '棰勫彂甯冪幆澧?, 3, 'ENABLED'),
    (4, 'ENV_TYPE', 'PROD', '鐢熶骇鐜', 4, 'ENABLED'),
    (5, 'RELEASE_STATUS', 'CREATED', '宸插垱寤?, 1, 'ENABLED'),
    (6, 'RELEASE_STATUS', 'APPROVING', '瀹℃壒涓?, 2, 'ENABLED'),
    (7, 'RELEASE_STATUS', 'APPROVED', '宸插鎵?, 3, 'ENABLED'),
    (8, 'RELEASE_STATUS', 'REJECTED', '宸叉嫆缁?, 4, 'ENABLED'),
    (9, 'RELEASE_STATUS', 'RUNNING', '鎵ц涓?, 5, 'ENABLED'),
    (10, 'RELEASE_STATUS', 'SUCCESS', '鎴愬姛', 6, 'ENABLED'),
    (11, 'RELEASE_STATUS', 'FAILED', '澶辫触', 7, 'ENABLED'),
    (12, 'RELEASE_STATUS', 'ROLLED_BACK', '宸插洖婊?, 8, 'ENABLED')
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
    (1, 'dev', '寮€鍙戠幆澧?, 'DEV', 'default', 'dev', '榛樿寮€鍙戠幆澧?, 'ENABLED', 1, 1),
    (2, 'test', '娴嬭瘯鐜', 'TEST', 'default', 'test', '榛樿娴嬭瘯鐜', 'ENABLED', 1, 1),
    (3, 'uat', '棰勫彂甯冪幆澧?, 'UAT', 'default', 'uat', '榛樿棰勫彂甯冪幆澧?, 'ENABLED', 1, 1),
    (4, 'prod', '鐢熶骇鐜', 'PROD', 'default', 'prod', '榛樿鐢熶骇鐜', 'ENABLED', 1, 1)
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

