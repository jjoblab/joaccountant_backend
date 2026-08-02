-- V46 — Finding #14 — WithholdingRule barème progressif (PAS FR).
--
-- Avant V46, le module :tax ne supportait que le taux unique (FLAT) sur les retenues à la
-- source. Or, le Prélèvement à la Source (PAS) français et certaines retenues OHADA utilisent
-- un barème progressif par tranches — par exemple :
--   base 0 → 50 000   : 0%
--   base 50 000 → 100 000 : 10%
--   base 100 000+      : 15%
-- Sans ce support, le calcul de la retenue était faux pour les entreprises soumises à un
-- barème progressif (appliquer un taux unique moyen au lieu de la décomposition par tranches).
--
-- V46 ajoute deux colonnes à withholding_rule :
--
-- 1. withholding_rule.bracket_type (VARCHAR(15), NOT NULL, défaut 'FLAT')
--    Type de barème. Valeurs :
--      - 'FLAT'        : taux unique appliqué à toute la base (comportement historique)
--      - 'PROGRESSIVE' : barème progressif par tranches (cf. brackets_json)
--    Contrainte CHECK pour garantir la cohérence. Le défaut 'FLAT' assure la rétro-compatibilité
--    des règles existantes.
--
-- 2. withholding_rule.brackets_json (JSONB, NULLABLE)
--    Barème progressif par tranches — utilisé uniquement quand bracket_type = 'PROGRESSIVE'.
--    Format : [{"threshold":0,"rate":0},{"threshold":50000,"rate":10},{"threshold":100000,"rate":15}]
--    Chaque entrée définit un palier : la part de la base comprise entre ce threshold et le
--    suivant est taxée au rate indiqué. Le dernier palier est ouvert (pas de plafond).
--
-- Le défaut 'FLAT' permet à toutes les règles existantes de continuer à fonctionner sans
-- modification (aucun impact tant que bracket_type n'est pas explicitement mis à 'PROGRESSIVE').

ALTER TABLE withholding_rule
    ADD COLUMN IF NOT EXISTS bracket_type VARCHAR(15) NOT NULL DEFAULT 'FLAT';

-- Backfill explicite (au cas où des lignes auraient une valeur NULL suite à un ALTER antérieur).
UPDATE withholding_rule SET bracket_type = 'FLAT' WHERE bracket_type IS NULL;

ALTER TABLE withholding_rule
    ADD CONSTRAINT chk_withholding_rule_bracket_type
    CHECK (bracket_type IN ('FLAT', 'PROGRESSIVE'));

ALTER TABLE withholding_rule
    ADD COLUMN IF NOT EXISTS brackets_json JSONB;

COMMENT ON COLUMN withholding_rule.bracket_type IS
    'V46 — Finding #14 : type de barème. FLAT = taux unique (historique), PROGRESSIVE = barème par tranches (brackets_json).';
COMMENT ON COLUMN withholding_rule.brackets_json IS
    'V46 — Finding #14 : barème progressif par tranches. Format [{threshold,rate}]. Utilisé seulement si bracket_type=PROGRESSIVE.';
