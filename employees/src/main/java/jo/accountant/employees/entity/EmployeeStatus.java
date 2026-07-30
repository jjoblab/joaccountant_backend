package jo.accountant.employees.entity;

/**
 * Statut d'un employé (restructuration 2026-07-24 — module :employees).
 *
 * <p>{@code ACTIVE} — employé actif, à payer sur une période de paie.
 * {@code ON_LEAVE} — en congé (peut être à payer selon la politique de l'entreprise).
 * {@code TERMINATED} — contrat rompu, ne plus payer.
 *
 * <p>Le filtre `status=ACTIVE` est utilisé par `:payroll` pour lister les salariés à
 * payer sur une période (voir `PayrollService.calculate`).
 */
public enum EmployeeStatus {
    ACTIVE,
    ON_LEAVE,
    TERMINATED
}
