-- V2_001 — users, refresh_token, password_reset_token (module :auth, §13 Phase 1).


CREATE TABLE IF NOT EXISTS users (
    id                       UUID        PRIMARY KEY DEFAULT uuidv7(),
    email                    VARCHAR(255) NOT NULL,
    password_hash            TEXT        NOT NULL,
    full_name                VARCHAR(255) NOT NULL,
    locale                   VARCHAR(10) NOT NULL DEFAULT 'fr',
    active                   BOOLEAN     NOT NULL DEFAULT TRUE,
    max_companies_override   INT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                  BIGINT      NOT NULL DEFAULT 0
);

-- Case-insensitive unique email — via functional UNIQUE INDEX (cannot use expression in a constraint).
CREATE UNIQUE INDEX IF NOT EXISTS uc_users_email_lower ON users (lower(email));
CREATE INDEX IF NOT EXISTS idx_users_email ON users (lower(email));

CREATE TABLE IF NOT EXISTS refresh_token (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    CHAR(64)    NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user_id ON refresh_token (user_id);

CREATE TABLE IF NOT EXISTS password_reset_token (
    id            UUID        PRIMARY KEY DEFAULT uuidv7(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    CHAR(64)    NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_password_reset_token_user_id ON password_reset_token (user_id);
