package jo.accountant.thirdparties.dto;

import java.time.Instant;
import java.util.UUID;
import jo.accountant.thirdparties.entity.ThirdPartyType;

/**
 * Réponse d'un tiers.
 *
 * <p><b>(session 7)</b> : ajout des champs légaux {@code siret},
 * {@code vatNumber}, {@code nif} pour conformité mentions légales factures (CGI art. 289)
 * et Factur-X. Ces champs sont persistés (migration V53) mais n'étaient pas exposés.
 *
 * <p><b>(fix mobile)</b> : ajout de {@code phone} (V10_001 — phone VARCHAR(30)) pour
 * renvoyer au mobile la valeur saisie côté backend.
 *
 * @author jo@Dev


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
    String phone,
    String address,
    //— champs légaux pour Factur-X + mentions légales
    String siret,
    String vatNumber,
    String nif,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Rétro-compatibilité — ancien constructeur sans phone (pour les callers historiques
     * qui ne gèrent pas encore le champ phone). Le champ est mis à {@code null}.
     */
    public ThirdPartyResponse(
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
        String siret,
        String vatNumber,
        String nif,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, companyId, type, name, collectiveAccountId, collectiveAccountCode,
            dedicatedAccountId, dedicatedAccountCode, active, email, null, address,
            siret, vatNumber, nif, createdAt, updatedAt);
    }
}
