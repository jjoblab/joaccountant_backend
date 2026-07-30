package jo.accountant.core.framework;

/**
 * Classe de reporting universelle — SEULE classification consommée par les états financiers (§4).
 *
 * <p><b>R-42 (lot-F1-code-arch)</b> : ajout de {@link #OTHER} pour les comptes spécifiques au
 * PCN Haïtien (classe 8 = Comptes spéciaux — engagements hors bilan et comptes de régularisation).
 * Ces comptes ne sont ni HAO (vs SYSCOHADA) ni OPERATING — ils ne doivent pas remonter dans le
 * compte de résultat (sinon ils pollueraient le résultat net) ni dans le bilan standard (ce sont
 * des engagements hors bilan). {@code FinancialStatementsService} ignore nativement les comptes
 * dont la reportingClass ne matche ni ACTIF/PASSIF/CAPITAUX_PROPRES (bilan) ni PRODUITS/CHARGES
 * (compte de résultat) — ils sont donc exclus automatiquement.
 */
public enum ReportingClass {
    ACTIF,
    PASSIF,
    CAPITAUX_PROPRES,
    PRODUITS,
    CHARGES,
    /** R-42 — Comptes spéciaux PCN Haïti (classe 8 : engagements hors bilan, régularisation). */
    OTHER
}

