-- Repair PostgreSQL identity/sequence values after manual inserts with explicit IDs.
-- Run this once if schema/data were initialized manually before sequence sync was added.

DO $$
DECLARE
    rec RECORD;
    seq_name TEXT;
    max_id BIGINT;
BEGIN
    FOR rec IN
        SELECT table_schema, table_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name = 'id'
          AND data_type = 'bigint'
        ORDER BY table_name
    LOOP
        seq_name := pg_get_serial_sequence(format('%I.%I', rec.table_schema, rec.table_name), 'id');

        IF seq_name IS NOT NULL THEN
            EXECUTE format('SELECT COALESCE(MAX(id), 1) FROM %I.%I', rec.table_schema, rec.table_name)
            INTO max_id;

            EXECUTE format('SELECT setval(%L, %s, true)', seq_name, max_id);
        END IF;
    END LOOP;
END $$;

SELECT 'IDENTITY SEQUENCES REPAIRED' AS result;
