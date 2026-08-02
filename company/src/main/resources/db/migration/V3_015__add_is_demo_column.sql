-- V3_015 — add is demo column

ALTER TABLE companies ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;