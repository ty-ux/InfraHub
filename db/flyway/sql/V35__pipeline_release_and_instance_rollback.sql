ALTER TABLE pipeline_run ADD COLUMN service_id BIGINT REFERENCES base_service(id);

ALTER TABLE release_rollback_order
    ALTER COLUMN release_order_id DROP NOT NULL,
    ALTER COLUMN from_version_id DROP NOT NULL,
    ADD COLUMN service_id BIGINT REFERENCES base_service(id),
    ADD COLUMN env_id BIGINT REFERENCES base_env(id),
    ADD COLUMN target_instance_ids TEXT,
    ADD CONSTRAINT chk_rollback_target_scope CHECK (
        (release_order_id IS NOT NULL AND from_version_id IS NOT NULL AND service_id IS NULL AND env_id IS NULL AND target_instance_ids IS NULL)
        OR (release_order_id IS NULL AND service_id IS NOT NULL AND env_id IS NOT NULL AND target_instance_ids IS NOT NULL)
    );
CREATE INDEX idx_rollback_direct_scope ON release_rollback_order (service_id, env_id, status);

ALTER TABLE runtime_deploy_record ALTER COLUMN release_order_id DROP NOT NULL;
ALTER TABLE runtime_deploy_record ADD CONSTRAINT chk_deploy_record_operation_owner
    CHECK (release_order_id IS NOT NULL OR rollback_order_id IS NOT NULL);
