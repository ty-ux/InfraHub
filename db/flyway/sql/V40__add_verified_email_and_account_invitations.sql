ALTER TABLE sys_user
    ALTER COLUMN email TYPE VARCHAR(254),
    ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uk_sys_user_verified_email ON sys_user (email)
    WHERE email_verified_at IS NOT NULL;

CREATE TABLE sys_auth_email_code (
    purpose VARCHAR(12) NOT NULL,
    target VARCHAR(300) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    PRIMARY KEY (purpose, target)
);

CREATE TABLE sys_auth_rate_limit (
    bucket VARCHAR(340) PRIMARY KEY,
    hits INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE sys_auth_invitation (
    email VARCHAR(254) PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE TABLE sys_auth_invitation_role (
    email VARCHAR(254) NOT NULL REFERENCES sys_auth_invitation(email) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    PRIMARY KEY (email, role_id)
);
