-- InfraHub database acceptance checks
-- Run after schema and init data are applied.
--
-- Example:
--   psql -U postgres -d infrahub -f 03-db-acceptance.sql

SELECT current_database() AS current_db, current_user AS current_user, now() AS checked_at;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'sys_user') THEN
        RAISE EXCEPTION 'Acceptance failed: table sys_user is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'pipeline_run') THEN
        RAISE EXCEPTION 'Acceptance failed: table pipeline_run is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_order') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_order is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'release_current_version') THEN
        RAISE EXCEPTION 'Acceptance failed: table release_current_version is missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'monitor_snapshot') THEN
        RAISE EXCEPTION 'Acceptance failed: table monitor_snapshot is missing';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'pipeline_run'
          AND column_name = 'updated_at'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: pipeline_run.updated_at is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'release_order'
          AND column_name = 'rollback_status'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: release_order.rollback_status is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'monitor_alert_rule'
          AND column_name = 'target_group_code'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: monitor_alert_rule.target_group_code is missing';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_release_version_build_run'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_release_version_build_run is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'idx_pipeline_step_run_step'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: index idx_pipeline_step_run_step is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname = 'uk_config_env_config_key'
    ) THEN
        RAISE EXCEPTION 'Acceptance failed: partial unique index uk_config_env_config_key is missing';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin') THEN
        RAISE EXCEPTION 'Acceptance failed: admin user is missing';
    END IF;

    IF (SELECT COUNT(*) FROM sys_role WHERE role_code IN ('ADMIN', 'DEVOPS', 'DEVELOPER')) < 3 THEN
        RAISE EXCEPTION 'Acceptance failed: default roles are incomplete';
    END IF;

    IF (SELECT COUNT(*) FROM base_env WHERE env_code IN ('dev', 'test', 'uat', 'prod')) < 4 THEN
        RAISE EXCEPTION 'Acceptance failed: default environments are incomplete';
    END IF;
END $$;

SELECT 'table_count' AS check_name, COUNT(*)::TEXT AS check_value
FROM pg_tables
WHERE schemaname = 'public'
UNION ALL
SELECT 'admin_user_count', COUNT(*)::TEXT
FROM sys_user
WHERE username = 'admin'
UNION ALL
SELECT 'default_role_count', COUNT(*)::TEXT
FROM sys_role
WHERE role_code IN ('ADMIN', 'DEVOPS', 'DEVELOPER')
UNION ALL
SELECT 'default_env_count', COUNT(*)::TEXT
FROM base_env
WHERE env_code IN ('dev', 'test', 'uat', 'prod');

BEGIN;

WITH new_role AS (
    INSERT INTO sys_role (id, role_code, role_name, status)
    SELECT
        COALESCE(MAX(id), 0) + 1,
        'ACCEPT_ROLE_' || substr(md5(clock_timestamp()::TEXT), 1, 8),
        'Acceptance Temp Role',
        'ENABLED'
    FROM sys_role
    RETURNING id, role_code
)
SELECT id, role_code
FROM new_role;

ROLLBACK;

SELECT 'ACCEPTANCE PASSED' AS result;
