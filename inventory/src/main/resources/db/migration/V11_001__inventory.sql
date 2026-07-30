-- V11_001 — inventory (module :inventory, §13 Phase 9).
--
-- Tables : warehouse, item, stock_move, stock_valuation_layer.
-- LIFO n'est PAS implémenté — pas de flag exposé (IFRS l'interdit).

CREATE TABLE IF NOT EXISTS warehouse (
    id          UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id  UUID        NOT NULL,
    label       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_wh_company_label UNIQUE (company_id, label)
);

CREATE INDEX IF NOT EXISTS idx_wh_company ON warehouse (company_id);

CREATE TABLE IF NOT EXISTS item (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id            UUID        NOT NULL,
    sku                   VARCHAR(50) NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    unit_of_measure       VARCHAR(20) NOT NULL,
    costing_method        VARCHAR(20) NOT NULL DEFAULT 'FIFO',
    reorder_threshold     NUMERIC(19, 4),
    inventory_account_id  UUID        NOT NULL,
    cogs_account_id       UUID        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uc_item_company_sku UNIQUE (company_id, sku),
    -- LIFO n'est pas autorisé — CHECK constraint qui l'exclut explicitement
    CONSTRAINT chk_item_costing_method CHECK (costing_method IN ('FIFO','WEIGHTED_AVERAGE'))
);

CREATE INDEX IF NOT EXISTS idx_item_company ON item (company_id);

CREATE TABLE IF NOT EXISTS stock_move (
    id                UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id        UUID        NOT NULL,
    item_id           UUID        NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    warehouse_id      UUID        NOT NULL REFERENCES warehouse(id),
    to_warehouse_id   UUID,
    move_date         DATE        NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    quantity          NUMERIC(19, 4) NOT NULL,
    unit_cost         NUMERIC(19, 4) NOT NULL,
    total_cost        NUMERIC(19, 4) NOT NULL,
    source_document   VARCHAR(100),
    journal_entry_id  UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_sm_direction CHECK (direction IN ('IN','OUT','TRANSFER')),
    CONSTRAINT chk_sm_quantity CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_sm_item ON stock_move (item_id);
CREATE INDEX IF NOT EXISTS idx_sm_warehouse ON stock_move (warehouse_id);
CREATE INDEX IF NOT EXISTS idx_sm_company_date ON stock_move (company_id, move_date);

CREATE TABLE IF NOT EXISTS stock_valuation_layer (
    id                    UUID        PRIMARY KEY DEFAULT uuidv7(),
    company_id            UUID        NOT NULL,
    item_id               UUID        NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    warehouse_id          UUID        NOT NULL REFERENCES warehouse(id),
    quantity_received     NUMERIC(19, 4) NOT NULL,
    quantity_remaining    NUMERIC(19, 4) NOT NULL,
    unit_cost             NUMERIC(19, 4) NOT NULL,
    receipt_date          DATE        NOT NULL,
    source_stock_move_id  UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_svl_qty_received CHECK (quantity_received > 0),
    CONSTRAINT chk_svl_qty_remaining CHECK (quantity_remaining >= 0)
);

CREATE INDEX IF NOT EXISTS idx_svl_item_warehouse ON stock_valuation_layer (item_id, warehouse_id);
CREATE INDEX IF NOT EXISTS idx_svl_company_item ON stock_valuation_layer (company_id, item_id);
