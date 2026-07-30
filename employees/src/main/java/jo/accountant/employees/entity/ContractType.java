package jo.accountant.employees.entity;

/**
 * Type de contrat d'un employé (restructuration 2026-07-24 — module :employees).
 *
 * <p>{@code PERMANENT} — CDI (contrat à durée indéterminée).
 * {@code FIXED_TERM} — CDD (contrat à durée déterminée).
 * {@code CONSULTANT} — consultant externe (pas un salarié au sens strict, mais rattaché
 * au module pour centraliser la gestion des paiements périodiques via `:payroll`).
 */
public enum ContractType {
    PERMANENT,
    FIXED_TERM,
    CONSULTANT
}
