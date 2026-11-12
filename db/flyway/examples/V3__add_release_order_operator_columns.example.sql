-- Example only. Do not place this file in db/flyway/sql directly unless this is the chosen real migration.

ALTER TABLE release_order
    ADD COLUMN IF NOT EXISTS operator_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS operator_email VARCHAR(128);

COMMENT ON COLUMN release_order.operator_name IS '操作人姓名快照';
COMMENT ON COLUMN release_order.operator_email IS '操作人邮箱快照';

CREATE INDEX IF NOT EXISTS idx_release_order_operator_name
    ON release_order (operator_name);
