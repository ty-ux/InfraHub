-- Example only. Do not place this file in db/flyway/sql directly unless this is the chosen real migration.

INSERT INTO sys_enum (enum_type, enum_code, enum_name, sort_order, status)
VALUES
    ('MONITOR_TARGET_TYPE', 'HOST', '主机', 1, 'ENABLED'),
    ('MONITOR_TARGET_TYPE', 'SERVICE', '服务', 2, 'ENABLED'),
    ('MONITOR_TARGET_TYPE', 'INSTANCE', '实例', 3, 'ENABLED')
ON CONFLICT (enum_type, enum_code) DO UPDATE
SET
    enum_name = EXCLUDED.enum_name,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = NOW();
