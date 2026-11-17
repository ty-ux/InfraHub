ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 0;

SELECT setval(pg_get_serial_sequence('sys_user', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM sys_user), nextval(pg_get_serial_sequence('sys_user', 'id'))));

UPDATE sys_user SET credential_version = credential_version + 1
WHERE password_hash = 'CHANGE_ME_HASH';
