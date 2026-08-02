-- V26_001 — purchase orders
-- V48 — — Module :purchase-orders — commandes fournisseur + 3-way match.
-- :
-- - Aucune commande fournisseur n'était persistée. Les factures d'achat (:purchasing) étaient
-- enregistrées sans rapprochement avec une commande préalable. Or, le contrôle interne
-- standard (3-way match : commande ↔ réception ↔ facture) permet de détecter :
-- * les factures sans commande sous-jacente (engagement non autorisé),
-- * les sur-facturations (quantité facturée > quantité commandée),
-- * les écarts de prix (prix facturé ≠ prix commandé).
-- Sans ce contrôle, l'entreprise paie des factures qui n'ont pas fait l'objet d'un
-- engagement formel — risque de fraude et de sur-paiement.
-- V48 crée :
-- 1. Table purchase_order — entête de commande (fournisseur, numéro, date, statut, total).
-- 2. Table purchase_order_line — lignes de commande (article, description, qté, prix, qté reçue).
-- Le module ne génère PAS d'écriture comptable au MVP (l'écriture est générée à la facture
-- dans :purchasing). Les commandes servent uniquement de référence au 3-way match implémenté
-- dans ThreeWayMatchService.


CREATE TABLE IF NOT EXISTS purchase_order (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    supplier_id         UUID        NOT NULL,
    order_number        VARCHAR(50) NOT NULL,
    order_date          DATE        NOT NULL,
    status              VARCHAR(15) NOT NULL DEFAULT 'DRAFT',
    currency            CHAR(3)     NOT NULL DEFAULT 'HTG',
    total_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_po_status CHECK (status IN ('DRAFT','SUBMITTED','RECEIVED','CLOSED')),
    CONSTRAINT chk_po_total CHECK (total_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_po_company ON purchase_order (company_id);
CREATE INDEX IF NOT EXISTS idx_po_company_supplier ON purchase_order (company_id, supplier_id);
CREATE INDEX IF NOT EXISTS idx_po_supplier ON purchase_order (supplier_id);
CREATE UNIQUE INDEX IF NOT EXISTS uc_po_company_number ON purchase_order (company_id, order_number);

CREATE TABLE IF NOT EXISTS purchase_order_line (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id          UUID        NOT NULL,
    po_id               UUID        NOT NULL,
    item_id             UUID,
    description         VARCHAR(500) NOT NULL,
    quantity            NUMERIC(19, 4) NOT NULL,
    unit_price          NUMERIC(19, 4) NOT NULL,
    received_quantity   NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_pol_quantity CHECK (quantity > 0),
    CONSTRAINT chk_pol_price CHECK (unit_price >= 0),
    CONSTRAINT chk_pol_received CHECK (received_quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_pol_po ON purchase_order_line (po_id);
CREATE INDEX IF NOT EXISTS idx_pol_company ON purchase_order_line (company_id);
CREATE INDEX IF NOT EXISTS idx_pol_item ON purchase_order_line (item_id) WHERE item_id IS NOT NULL;

COMMENT ON TABLE purchase_order IS
    'V48 — Finding #10 : commandes fournisseurs (Purchase Orders). Sert de référence au 3-way match.';
COMMENT ON TABLE purchase_order_line IS
    'V48 — Finding #10 : lignes de commande. received_quantity incrémenté à la réception.';
COMMENT ON COLUMN purchase_order_line.received_quantity IS
    'V48 — quantité déjà reçue (incrémentée via :inventory StockMove IN).';
