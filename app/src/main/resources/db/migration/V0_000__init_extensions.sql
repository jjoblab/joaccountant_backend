-- V0_000 — PostgreSQL extensions + UUID v7 generator.
-- UUID v7 is RFC 9562 (time-ordered, better index locality than v4).
--
-- FIX v8.2 définitif (Render / PostgreSQL 18+) :
--   - PostgreSQL 18 expose uuidv7() nativement dans pg_catalog (écrite en C, plus rapide
--     que la version PL/pgSQL ci-dessous).
--   - PostgreSQL < 18 n'a pas cette fonction → on l'implémente en PL/pgSQL.
--   - Cette migration utilise un DO block qui détecte la version PG et ne crée la fonction
--     public.uuidv7() QUE sur PG < 18. Sur PG 18+, on utilise la fonction native.
--   - Avantage : zéro conflit, zéro erreur "must be owner", zéro redondance.
--   - Les DEFAULT de colonnes utilisent `uuidv7()` (sans préfixe) → résout vers
--     pg_catalog.uuidv7() sur PG 18 (prioritaire dans search_path) ou public.uuidv7() sur PG < 18.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================================
-- Création conditionnelle de public.uuidv7() — uniquement sur PG < 18
-- ============================================================================
DO $$
DECLARE
    pg_version_num INT;
BEGIN
    -- current_setting('server_version_num') retourne la version en entier :
    --   PG 16.5 → 160005
    --   PG 17.0 → 170000
    --   PG 18.0 → 180000
    pg_version_num := current_setting('server_version_num')::INT;

    IF pg_version_num < 180000 THEN
        -- PostgreSQL < 18 : créer la fonction PL/pgSQL dans le schéma public.
        -- EXECUTE nécessaire car la fonction utilise $$
        EXECUTE $func$
            CREATE OR REPLACE FUNCTION public.uuidv7() RETURNS UUID AS $body$
            DECLARE
                unix_ts_ms BIGINT;
                ts_hex TEXT;
                rnd_hex TEXT;
                final_hex TEXT;
            BEGIN
                unix_ts_ms := FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
                -- 12 hex chars = 48 bits of unix_ts_ms, big-endian (zero-padded on the left)
                ts_hex := lpad(to_hex(unix_ts_ms), 12, '0');
                ts_hex := right(ts_hex, 12);   -- garder les 12 derniers (au cas où unix_ts_ms > 48 bits)

                -- Random UUID (v4) comme source des 80 bits de hasard restants.
                -- On récupère sa forme textuelle (36 chars avec tirets), on retire les tirets → 32 hex chars.
                rnd_hex := replace(gen_random_uuid()::text, '-', '');

                -- Layout final (32 hex chars = 128 bits) :
                --   hex 0-11  : unix_ts_ms (48 bits)
                --   hex 12    : '7' (version)
                --   hex 13-15 : 12 bits rand_a
                --   hex 16    : '8'|'9'|'a'|'b' (variant 10xx — on prend '8' et 3 bits de rand)
                --   hex 17-31 : 60 bits rand_b
                final_hex :=
                    ts_hex                                       -- 48 bits timestamp
                    || '7' || substring(rnd_hex FROM 13 FOR 3)  -- version 7 + 12 bits rand_a
                    || '8' || substring(rnd_hex FROM 16 FOR 3)  -- variant 10 + 6 bits
                    || substring(rnd_hex FROM 19 FOR 12);        -- 48 bits rand_b

                RETURN final_hex::UUID;
            END;
            $body$ LANGUAGE plpgsql VOLATILE PARALLEL SAFE
        $func$;

        -- COMMENT sur public.uuidv7() — schéma-qualifié pour éviter la résolution vers pg_catalog
        EXECUTE $cmt$
            COMMENT ON FUNCTION public.uuidv7() IS
                'Generates a UUID v7 (RFC 9562): 48-bit unix-ms timestamp + version 7 + random. '
                'Used as the default for every primary key in JOAccountant (§3.2). '
                'Custom PL/pgSQL implementation for PostgreSQL < 18 (no native uuidv7).'
        $cmt$;

        RAISE NOTICE 'V0_000: PostgreSQL % détecté — public.uuidv7() créée (implémentation PL/pgSQL)', pg_version_num;
    ELSE
        -- PostgreSQL 18+ : utiliser la fonction native pg_catalog.uuidv7()
        -- Aucune action nécessaire — la fonction est déjà disponible dans le catalogue système.
        RAISE NOTICE 'V0_000: PostgreSQL % détecté — utilisation de pg_catalog.uuidv7() native', pg_version_num;
    END IF;
END $$;
