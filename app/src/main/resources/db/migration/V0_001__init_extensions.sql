-- V0_001 — PostgreSQL extensions + UUID v7 generator.
-- UUID v7 is RFC 9562 (time-ordered, better index locality than v4).
-- Comportement par version de PostgreSQL :
-- - PG < 18 (tests Zonky, dev local) : public.uuidv7() est créée ci-dessous et utilisée
-- par les DEFAULT de colonnes (pas de uuidv7() natif dans pg_catalog).
-- - PG 18+ (Render, prod) : pg_catalog.uuidv7() native existe. public.uuidv7() est quand
-- même créée (inoffensive) MAIS les DEFAULT uuidv7() résolvent vers pg_catalog.uuidv7()
-- car pg_catalog est prioritaire dans search_path. La fonction native (C) est plus rapide.
-- FIX v8.2 définitif (Render / PostgreSQL 18+) :
-- - Le `COMMENT ON FUNCTION` est schéma-qualifié (`public.uuidv7()`) pour éviter la
-- résolution vers pg_catalog.uuidv7() qui déclencherait "must be owner of function".
-- - On n'utilise PAS de bloc DO $$ conditionnel car Flyway ne parse pas correctement
-- les dollar-quotes imbriquées ($func$, $body$) → syntax error sur EXECUTE.


CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- uuidv7(): 48-bit unix-ms timestamp | 4-bit version (7) | 12-bit rand_a | 2-bit variant | 62-bit rand_b
-- Implementation: take a v4 UUID from gen_random_uuid() as randomness source, then overwrite
-- the first 6 bytes (48-bit timestamp) and the version bits. Simpler and more robust than
-- byte-by-byte concatenation.
CREATE OR REPLACE FUNCTION public.uuidv7() RETURNS UUID AS $$
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
$$ LANGUAGE plpgsql VOLATILE PARALLEL SAFE;

-- FIX v8.2 : schéma-qualifier pour éviter la résolution vers pg_catalog.uuidv7() sur PG 18+
-- (sinon erreur 42501 "must be owner of function uuidv7" car l'user Render n'est pas propriétaire
-- de la fonction native pg_catalog).
COMMENT ON FUNCTION public.uuidv7() IS
    'Generates a UUID v7 (RFC 9562): 48-bit unix-ms timestamp + version 7 + random. '
    'Used as the default for every primary key in JOAccountant (§3.2). '
    'On PostgreSQL 18+, pg_catalog.uuidv7() (native) is used instead via search_path.';
