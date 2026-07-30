-- V44 — Finding #6 — TVA sur encaissement (régime des encaissements, art. 289 II CGI).
--
-- Avant V44, le module :tax ne supportait que le régime des débits (TVA exigible à l'émission
-- de la facture). Pour les prestataires de services et les PME qui optent pour le régime des
-- encaissements, la TVA n'est exigible qu'au paiement effectif par le client. Sans ce support,
-- la déclaration TVA de ces entreprises était fausse (TVA déclarée avant encaissement = trésorerie
-- impactée à tort).
--
-- V44 ajoute deux colonnes :
--
-- 1. tax_rule.vat_mode (VARCHAR(15), NOT NULL, défaut 'DEBIT')
--    Mode d'exigibilité de la règle de TVA. Valeurs :
--      - 'DEBIT'        : TVA sur débit (régime par défaut, exigible à l'émission)
--      - 'ENCAISSEMENT' : TVA sur encaissement (exigible au paiement)
--    Contrainte CHECK pour garantir la cohérence. Le défaut 'DEBIT' assure la rétro-compatibilité
--    des règles existantes (aucun comportement modifié pour les entreprises en régime des débits).
--
-- 2. sales_invoice.vat_deferred_amount (NUMERIC(19,4), NULLABLE, défaut 0)
--    Montant de TVA encore « différée » (en compte 4438 « TVA sur factures émises non
--    encaissées ») pour les factures émises sous une règle ENCAISSEMENT. En mode DEBIT, toujours
--    0. En mode ENCAISSEMENT, initialisé au taxAmount à l'émission puis décrémenté à chaque
--    règlement (recordPayment) jusqu'à 0 (facture entièrement payée).
--
-- 3. sales_invoice.vat_settlement_entry_id (UUID, NULLABLE)
--    ID de la dernière écriture comptable de bascule 4438 → 443 générée au règlement (pour audit).
--    Null en mode DEBIT ou tant qu'aucun règlement n'a été enregistré.
--
-- Note : la colonne vat_mode est backfillée à 'DEBIT' pour toutes les règles existantes (DEFAULT
-- 'DEBIT' + NOT NULL suffit — toutes les lignes existantes obtiennent automatiquement la valeur
-- par défaut au moment de l'ALTER TABLE).

ALTER TABLE tax_rule
    ADD COLUMN IF NOT EXISTS vat_mode VARCHAR(15) NOT NULL DEFAULT 'DEBIT';

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE tax_rule SET vat_mode = 'DEBIT' WHERE vat_mode IS NULL;

ALTER TABLE tax_rule
    ADD CONSTRAINT chk_tax_rule_vat_mode CHECK (vat_mode IN ('DEBIT', 'ENCAISSEMENT'));

ALTER TABLE sales_invoice
    ADD COLUMN IF NOT EXISTS vat_deferred_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE sales_invoice
    ADD COLUMN IF NOT EXISTS vat_settlement_entry_id UUID;

-- Backfill : toutes les factures existantes ont été émises en régime des débits (comportement
-- historique avant V44) — leur vat_deferred_amount reste à 0 (valeur par défaut).
-- Pas de backfill nécessaire pour vat_settlement_entry_id (NULL = aucune bascule, correct).

COMMENT ON COLUMN tax_rule.vat_mode IS
    'V44 — Finding #6 : mode d''exigibilité TVA. DEBIT = à l''émission (défaut), ENCAISSEMENT = au paiement.';
COMMENT ON COLUMN sales_invoice.vat_deferred_amount IS
    'V44 — Finding #6 : TVA encore différée en 4438 (mode ENCAISSEMENT). 0 en mode DEBIT ou une fois la facture entièrement payée.';
COMMENT ON COLUMN sales_invoice.vat_settlement_entry_id IS
    'V44 — Finding #6 : ID de la dernière écriture de bascule 4438 → 443 générée au règlement (audit).';
