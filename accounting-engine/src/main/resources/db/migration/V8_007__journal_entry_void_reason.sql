-- V8_007 — journal entry void reason
-- V61 — (lot-F1-code-arch) — journal_entry.void_reason column for rich aggregate method
-- voidEntry(String).
-- Avant , l'annulation d'une écriture POSTED (contre-passation) ne stockait pas la raison
-- d'annulation — l'originale passait simplement à status=VOIDED, et la nouvelle écriture de
-- contre-passation pointait vers elle via reversal_of_entry_id. Aucune trace de la RAISON de
-- l'annulation (erreur de saisie, doublon, fraude suspectée, etc.) n'était conservée.
-- encapsule l'invariant (status POSTED requis, raison obligatoire) et persiste la raison dans
-- la nouvelle colonne `void_reason`. La colonne est nullable (les écritures non VOIDED n'ont
-- pas de raison) et limitée à 500 caractères (cohérent avec `description`).
-- Idempotent : ADD COLUMN IF NOT EXISTS (les envs déjà migrés ne sont pas impactés).


ALTER TABLE journal_entry
    ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);
