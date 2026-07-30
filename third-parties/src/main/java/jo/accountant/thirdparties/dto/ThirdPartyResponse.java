package jo.accountant.thirdparties.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.thirdparties.entity.ThirdPartyType;

/**
 * Réponse d'un tiers.
 *
 * <p><b>Audit v4.7 §4.2 (session 7)</b> : ajout des champs légaux {@code siret},
 * {@code vatNumber}, {@code nif} pour conformité mentions légales factures (CGI art. 289)
 * et Factur-X. Ces champs sont persistés (migration V42) mais n'étaient pas exposés.
 */
public record ThirdPartyResponse(
    UUID id,
    UUID companyId,
    ThirdPartyType type,
    String name,
    UUID collectiveAccountId,
    String collectiveAccountCode,
    UUID dedicatedAccountId,
    String dedicatedAccountCode,
    boolean active,
    String email,
    String address,
    // Audit v4.7 §4.2 — champs légaux pour Factur-X + mentions légales
    String siret,
    String vatNumber,
    String nif,
    Instant createdAt,
    Instant updatedAt
) {}
