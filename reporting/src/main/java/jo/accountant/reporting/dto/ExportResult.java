package jo.accountant.reporting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Réponse d'export — {@code GET .../reporting/exports/{statement}?format=pdf|xlsx}.
 *
 * <p>Format PDF : délègue à :document-generation (Phase 11).
 * Format Excel/CSV : généré directement par :reporting (grand livre, balance).
 */
public record ExportResult(
    UUID companyId,
    String statement,
    String format,
    byte[] content,
    String contentType,
    String filename
) {}
