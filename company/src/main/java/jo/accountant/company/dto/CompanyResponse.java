package jo.accountant.company.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;

/**
 * Réponse standard pour une société (restructuration 2026-07-24).
 *
 * <p>Ajoute les nouveaux champs {@code organizationNature}, {@code businessTypeCode},
 * {@code primaryActivityLabel} et {@code extraAttributes} par rapport à l'ancien contrat.
 *
 * <p><b>Audit v4.7 §4.2 (session 7)</b> : ajout des champs légaux {@code siret},
 * {@code vatNumber}, {@code nif}, {@code address} pour conformité mentions légales factures
 * (CGI art. 289) et Factur-X. Ces champs sont persistés (migration V42) mais n'étaient pas
 * exposés dans le DTO de réponse — le mobile ne pouvait ni les lire ni les afficher.
 */
public record CompanyResponse(
    UUID id,
    String name,
    LegalForm legalForm,
    String country,
    String functionalCurrency,
    Sector sector,
    OrganizationNature organizationNature,
    String businessTypeCode,
    String primaryActivityLabel,
    Map<String, Object> extraAttributes,
    UUID accountingFrameworkId,
    int fiscalYearStartMonth,
    int wizardStep,
    boolean wizardCompleted,
    // Audit v4.7 §4.2 — champs légaux pour Factur-X + mentions légales factures
    String siret,
    String vatNumber,
    String nif,
    String address,
    Instant createdAt,
    Instant updatedAt
) {}
