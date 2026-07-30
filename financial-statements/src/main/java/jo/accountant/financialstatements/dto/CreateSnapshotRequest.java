package jo.accountant.financialstatements.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.financialstatements.entity.FinancialStatementType;

/**
 * Corps de requête pour {@code POST .../financial-statements/snapshots}.
 *
 * <p>Figé le contenu d'un état financier pour une période donnée. Une fois figé, le
 * snapshot est immuable — cohérent avec l'immuabilité des écritures POSTED (Phase 5) et
 * des factures ISSUED (Phase 12).
 *
 * @param type type d'état (BALANCE_SHEET ou INCOME_STATEMENT)
 * @param periodId période fiscale concernée
 * @param asOf date « as of » pour un bilan (date à laquelle le bilan est calculé)
 * @param from date de début pour un compte de résultat
 * @param to date de fin pour un compte de résultat
 */
public record CreateSnapshotRequest(
    @NotNull FinancialStatementType type,
    @NotNull UUID periodId,
    LocalDate asOf,
    LocalDate from,
    LocalDate to
) {}
