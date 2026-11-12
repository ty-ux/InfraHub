-- V33: fix the unresolved-fact dedup granularity of runtime_execution_unknown.
--
-- Problem (V31 defect): the partial unique index
--   uk_runtime_execution_unknown_unresolved_source
-- covers (source_type, release_order_id, rollback_order_id, deploy_record_id) only.
-- Order-level facts have deploy_record_id IS NULL, so every order-level fact of one
-- release order collapses onto a single row regardless of plan_id / batch_id.
-- Two consequences for a canary release whose batches each end in an unknown result:
--   1. the batch attribution is lost: the surviving row names the first batch, so an
--      operator cannot tell that another batch is still unaccounted for;
--   2. resolving that single row clears the block for **all** batches at once, which
--      re-opens the service/env while another batch's remote result is still unknown.
-- The insert uses ON CONFLICT DO NOTHING, so the second registration is dropped
-- silently (the application now logs and audits that merge; see
-- ACTION_DETECT_ORDER_LEVEL_MERGED).
--
-- Fix: include COALESCE(plan_id, 0) and COALESCE(batch_id, 0) in the dedup key, so the
-- granularity becomes "one unresolved order-level fact per (release order, plan, batch)".
-- The deploy claim path (no canary plan) still passes NULL/NULL and therefore still keeps
-- exactly one order-level fact per release or rollback order.
--
-- The new key is strictly longer than the old one, so it accepts every row the old index
-- accepted and cannot fail on existing data; no backfill or reconciliation is needed.
--
-- Relation to the not-yet-executed migrations:
--   * V31 created the table and the defective index. V32 created the execution claim table.
--   * Neither has been applied to any database (neither development nor the shared one).
--     If V31 is still unapplied at the moment this migration is scheduled, the index fix
--     MAY be squashed into V31 instead of shipped as V33; both must never be applied twice.
--   * This file is written but NOT executed by the delivery. Field steps that depend on it
--     stay blocked until the database owner applies V31, V32, V33 in order and restarts the
--     service.
--
-- ASCII only: the baseline V1 corruption came from a lossy charset conversion.

DROP INDEX IF EXISTS uk_runtime_execution_unknown_unresolved_source;

CREATE UNIQUE INDEX IF NOT EXISTS uk_runtime_execution_unknown_unresolved_source
    ON runtime_execution_unknown (
        source_type,
        (COALESCE(release_order_id, 0)),
        (COALESCE(rollback_order_id, 0)),
        (COALESCE(deploy_record_id, 0)),
        (COALESCE(plan_id, 0)),
        (COALESCE(batch_id, 0))
    )
    WHERE status = 'UNRESOLVED';

COMMENT ON INDEX uk_runtime_execution_unknown_unresolved_source IS
    'One UNRESOLVED fact per (source, release/rollback order, deploy record, plan, batch). '
    'Deploy-claim executions pass plan/batch NULL and keep one order-level fact per order; '
    'canary batches keep one order-level fact each, so resolving one batch never unblocks another.';
