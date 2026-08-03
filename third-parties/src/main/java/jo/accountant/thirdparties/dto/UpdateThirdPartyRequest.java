package jo.accountant.thirdparties.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Corps de requête pour {@code PATCH .../third-parties/{thirdPartyId}}.
 *
 * <p>Sémantique <strong>PATCH</strong> : seuls les champs non-nuls sont mis à jour.
 * Les champs non fournis (ou explicitement {@code null}) dans le corps de la requête
 * sont ignorés — la valeur existante est préservée. Pour vider un champ, envoyer une
 * chaîne vide {@code ""} (la chaîne vide n'est PAS considérée comme "absente").
 *
 * <p>Le {@code type} et le {@code collectiveAccountId} ne sont PAS modifiables via ce
 * endpoint (champs structurels — la modification du compte collectif nécessite de
 * recréer le tiers avec un nouveau compte dédié). Le {@code dedicatedAccountId} est
 * également non modifiable (géré automatiquement par le service à la création).
 *
 * @param name nom du tiers (nullable = pas de modification)
 * @param email email optionnel (nullable = pas de modification)
 * @param phone téléphone optionnel (nullable = pas de modification)
 * @param address adresse optionnelle (nullable = pas de modification)
 * @param siret SIRET du tiers (nullable = pas de modification)
 * @param vatNumber TVA intracommunautaire (nullable = pas de modification)
 * @param nif NIF du tiers (nullable = pas de modification) — format Haïti : 10 chiffres + 2 lettres
 * @param active statut actif/inactif (nullable = pas de modification). Passer {@code false}
 *               pour un soft-delete manuel (à distinguer du DELETE qui refuse si écritures liées).
 *
 * @author jo@Dev


*/
public record UpdateThirdPartyRequest(
    String name,
    String email,
    String phone,
    String address,
    String siret,
    String vatNumber,
    @Pattern(regexp = "^$|^[0-9]{10}[A-Z]{2}$", message = "NIF Haïti doit être au format 10 chiffres + 2 lettres majuscules (ex: 1234567890AB)")
    String nif,
    Boolean active
) {
}
