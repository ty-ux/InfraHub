-- 04-04: Add missing reference data
-- Stage X P0-A: 数据库基线修复 - 参考数据种子（B4 修复）
-- 采纳 db/flyway/examples/V5__seed_sys_enum_monitor_types.example.sql 内容（已核对枚举值）入 bootstrap 通道。
-- 注记：MONITOR_TARGET_TYPE 当前无运行时代码消费（后端/前端均无引用），属参考数据种子，
--       与 ENV_TYPE/RELEASE_STATUS 同机制（sys_enum 管理维护，供后续监控目标配置使用）。
-- 幂等：ON CONFLICT (enum_type, enum_code) DO UPDATE；无 DROP/TRUNCATE
-- 编码：UTF-8

BEGIN;

INSERT INTO sys_enum (enum_type, enum_code, enum_name, sort_order, status, updated_at)
VALUES ('MONITOR_TARGET_TYPE', 'HOST', '主机', 1, 'ENABLED', NOW()),
       ('MONITOR_TARGET_TYPE', 'SERVICE', '服务', 2, 'ENABLED', NOW()),
       ('MONITOR_TARGET_TYPE', 'INSTANCE', '实例', 3, 'ENABLED', NOW())
ON CONFLICT (enum_type, enum_code) DO UPDATE
SET enum_name = EXCLUDED.enum_name,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = NOW();

COMMIT;