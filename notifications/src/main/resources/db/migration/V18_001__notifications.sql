-- V18_001 — notifications (module :notifications, §9, §13 Phase 15).


CREATE TABLE IF NOT EXISTS ntf_template (
    code          VARCHAR(50) PRIMARY KEY,
    channel       VARCHAR(10) NOT NULL,
    subject       VARCHAR(200) NOT NULL,
    body_template TEXT        NOT NULL,
    locale        VARCHAR(5)  NOT NULL DEFAULT 'fr',
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_ntf_template_channel CHECK (channel IN ('EMAIL','IN_APP'))
);

CREATE TABLE IF NOT EXISTS ntf_notification (
    id                UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id        UUID,
    recipient_user_id UUID        NOT NULL,
    type              VARCHAR(50) NOT NULL,
    payload_json      JSONB,
    channel           VARCHAR(10) NOT NULL,
    status            VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    read_at           TIMESTAMPTZ,
    CONSTRAINT chk_ntf_notif_channel CHECK (channel IN ('EMAIL','IN_APP')),
    CONSTRAINT chk_ntf_notif_status CHECK (status IN ('PENDING','SENT','FAILED','READ'))
);

CREATE INDEX IF NOT EXISTS idx_ntf_recipient ON ntf_notification (recipient_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ntf_company ON ntf_notification (company_id);

CREATE TABLE IF NOT EXISTS ntf_preference (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID        NOT NULL,
    company_id      UUID        NOT NULL,
    type            VARCHAR(50) NOT NULL,
    email_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    in_app_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT uc_ntf_pref UNIQUE (user_id, company_id, type)
);

CREATE TABLE IF NOT EXISTS ntf_alert_rule (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id      UUID        NOT NULL,
    type            VARCHAR(30) NOT NULL,
    threshold_value NUMERIC(19, 4),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_ntf_alert_type CHECK (type IN ('INVOICE_OVERDUE','FISCAL_PERIOD_PAST_DUE','GRANT_THRESHOLD_REACHED','LOW_STOCK','APPROVAL_PENDING'))
);

CREATE INDEX IF NOT EXISTS idx_ntf_alert_company ON ntf_alert_rule (company_id);

-- Seed templates de base
INSERT INTO ntf_template (code, channel, subject, body_template, locale) VALUES
  ('user-invitation', 'EMAIL', 'Invitation à rejoindre JOAccountant', 'Vous avez été invité à rejoindre l''entreprise sur JOAccountant. Rôle : {{role}}.', 'fr'),
  ('password-reset', 'EMAIL', 'Réinitialisation de votre mot de passe', 'Utilisez ce jeton pour réinitialiser votre mot de passe : {{resetToken}}. Expire dans {{expiresInMinutes}} minutes.', 'fr'),
  ('approval-requested', 'EMAIL', 'Demande d''approbation en attente', 'Action : {{actionType}}, montant : {{amount}}. Veuillez approuver ou rejeter.', 'fr'),
  ('invoice-overdue', 'EMAIL', 'Facture échue', 'La facture {{invoiceNumber}} est échue depuis le {{dueDate}}.', 'fr'),
  ('low-stock', 'IN_APP', 'Stock bas', 'L''article {{sku}} est sous son seuil de réapprovisionnement (stock : {{currentStock}}, seuil : {{reorderThreshold}}).', 'fr')
ON CONFLICT (code) DO NOTHING;
