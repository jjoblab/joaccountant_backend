package jo.accountant.accountingengine.entity;

/**
 * Type de journal comptable (V8.2 — audit Z.ai 2026-07-31, Phase 3).
 *
 * <p>Remplace la convention implicite sur le code journal (VT=Ventes, AC=Achats, etc.) par
 * un enum explicite. Avant V8.2, le type était purement conventionnel — chaque module
 * métier (invoicing, purchasing, payroll, etc.) hardcodait son code ("VT", "AC", "PA", etc.)
 * et faisait {@code journalRepository.findByCompanyIdAndCode(companyId, "VT").orElseThrow()}.
 *
 * <p>Avec V8.2, le type est explicite sur l'entité {@link Journal} (colonne {@code type}).
 * Le service {@code AccountingEngineService.getOrCreateJournal(companyId, JournalType)} crée
 * automatiquement le journal standard s'il n'existe pas encore — fini les
 * {@code JOURNAL_*_NOT_FOUND} qui bloquaient toute opération métier si l'admin n'avait pas
 * pré-créé les journaux manuellement.
 *
 * <p>Les 8 types correspondent aux journaux standards créés par l'activation atomique du
 * wizard V8.2 (cf. {@code AccountingProvisioningPortImpl.DEFAULT_JOURNALS}).
 */
public enum JournalType {

    /** Journal des ventes — code "VT". Factures clients, avoirs clients. */
    VENTES("VT", "Journal des ventes"),

    /** Journal des achats — code "AC". Factures fournisseurs, avoirs fournisseurs. */
    ACHATS("AC", "Journal des achats"),

    /** Journal de banque — code "BQ". Mouvements bancaires, règlements. */
    BANQUE("BQ", "Journal de banque"),

    /** Journal de caisse — code "CA". Espèces, petites dépenses. */
    CAISSE("CA", "Journal de caisse"),

    /** Opérations diverses — code "OD". Écritures d'ajustement, clôture, contre-passations. */
    OD("OD", "Opérations diverses"),

    /** Journal de paie — code "PA". Bulletins de salaire, cotisations. */
    PAIE("PA", "Journal de paie"),

    /** Journal des dépenses — code "DP". Notes de frais, dépenses remboursables. */
    DEPENSES("DP", "Journal des dépenses"),

    /** Journal des opérations de change — code "FX". Gain/perte de change, conversions. */
    FX("FX", "Journal des opérations de change");

    private final String defaultCode;
    private final String defaultLabel;

    JournalType(String defaultCode, String defaultLabel) {
        this.defaultCode = defaultCode;
        this.defaultLabel = defaultLabel;
    }

    /** Code journal par défaut associé au type (ex: VENTES → "VT"). */
    public String getDefaultCode() {
        return defaultCode;
    }

    /** Libellé par défaut associé au type (ex: VENTES → "Journal des ventes"). */
    public String getDefaultLabel() {
        return defaultLabel;
    }

    /**
     * Résout un {@link JournalType} depuis un code journal.
     *
     * @param code le code journal (ex: "VT", "AC", "BQ", etc.) — insensible à la casse
     * @return le {@link JournalType} correspondant, ou {@code null} si le code ne correspond
     *         à aucun type standard (journal personnalisé)
     */
    public static JournalType fromCode(String code) {
        if (code == null) return null;
        String upper = code.trim().toUpperCase();
        for (JournalType t : values()) {
            if (t.defaultCode.equals(upper)) return t;
        }
        return null;
    }
}
