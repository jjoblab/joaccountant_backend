-- V41 — Audit v4.7 §6.3 Finding MOYENNE (suite) — Table mfa_secret pour MFA TOTP (RFC 6238).
--
-- Stockage du secret TOTP chiffré AES-256-GCM + codes de récupération hashés SHA-256.
-- La MFA est obligatoire pour les rôles OWNER et ADMIN (audit v4.7 §6.3).

CREATE TABLE IF NOT EXISTS mfa_secret (
    id                  UUID            NOT NULL PRIMARY KEY,
    user_id             UUID            NOT NULL UNIQUE,  -- un secret par utilisateur
    secret_encrypted    VARCHAR(500)    NOT NULL,         -- AES-256-GCM Base64
    issuer              VARCHAR(50)     NOT NULL DEFAULT 'JOAccountant',
    period              INTEGER         NOT NULL DEFAULT 30,
    digits              INTEGER         NOT NULL DEFAULT 6,
    algorithm           VARCHAR(20)     NOT NULL DEFAULT 'HmacSHA1',
    enabled_at          TIMESTAMP,                        -- null = setup en attente
    recovery_codes      JSONB,                            -- [{"hash":"...","usedAt":null},...]
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,

    -- Validation : period entre 10 et 300 secondes (RFC 6238 recommande 30)
    CONSTRAINT chk_mfa_period CHECK (period >= 10 AND period <= 300),
    -- Validation : digits 6 ou 8 (RFC 6238)
    CONSTRAINT chk_mfa_digits CHECK (digits IN (6, 8)),
    -- Validation : algorithm supporté
    CONSTRAINT chk_mfa_algorithm CHECK (algorithm IN ('HmacSHA1', 'HmacSHA256', 'HmacSHA512'))
);

-- Index pour findByUserId (déjà couvert par UNIQUE sur user_id, mais explicite pour clarté)
CREATE INDEX IF NOT EXISTS idx_mfa_secret_user
    ON mfa_secret (user_id);

COMMENT ON TABLE mfa_secret IS
    'V41 — Audit v4.7 §6.3 — Secret MFA TOTP (RFC 6238) chiffré AES-256-GCM + codes de récupération.';
COMMENT ON COLUMN mfa_secret.secret_encrypted IS
    'Secret TOTP Base32 chiffré AES-256-GCM (clé dans app.mfa.encryption-key, à externaliser dans Vault/KMS en prod).';
COMMENT ON COLUMN mfa_secret.recovery_codes IS
    '10 codes de récupération à usage unique, hashés SHA-256. Format: [{"hash":"base64...","usedAt":"2026-07-26T..."}].';
