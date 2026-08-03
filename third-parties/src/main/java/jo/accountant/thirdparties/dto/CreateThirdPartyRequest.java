package jo.accountant.thirdparties.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import jo.accountant.thirdparties.entity.ThirdPartyType;

/**
 * Corps de requête pour {@code POST .../third-parties}.
 *
 * @param type type de tiers (CLIENT, SUPPLIER, DONOR, EMPLOYEE, OTHER)
 * @param name nom du tiers (ex. "Boutique Pétion-Ville", "Fournisseur XYZ")
 * @param collectiveAccountId ID du compte collectif (doit avoir isCollective=true).
 * <p>désormais <em>nullable</em>. Si {@code null}, le service
 * recherche un compte collectif par défaut selon le {@code type} (CLIENT →
 * compte collectif dont le code commence par {@code 411}, SUPPLIER →
 * {@code 401}, DONOR → {@code 470}, EMPLOYEE → {@code 421}). Si aucun
 * compte collectif n'existe pour ce type, une erreur 422
 * {@code COLLECTIVE_ACCOUNT_REQUIRED} est renvoyée.
 *
 * <p>Motivation : le formulaire mobile ne permettait pas la sélection
 * du compte collectif, envoyait {@code null}, et le backend rejetait
 * la requête en 422 (cf. logs fff.txt — ThirdPartyEditorFragment).
 * Plutôt que de forcer l'UX mobile à charger la liste des comptes
 * collectifs (charge réseau supplémentaire + complexité UI), le
 * backend auto-résout un défaut basé sur le type — comportement
 * aligné avec la convention SYSCOHADA.
 * @param email email optionnel
 * @param phone téléphone optionnel — envoyé par le mobile (V10_001 — phone VARCHAR(30))
 * @param address adresse optionnelle
 * @param nif NIF du tiers (Numéro d'Identification Fiscale). Format Haïti : 10 chiffres + 2 lettres {@code ^[0-9]{10}[A-Z]{2}$}.
 * Optionnel mais recommandé pour les clients/suppliers assujettis (Code Fiscal art. 196 — mentions factures).
 * R-F-validationprécédemment manquant côté API.
 *
 * @author jo@Dev


*/
public record CreateThirdPartyRequest(
    @NotNull ThirdPartyType type,
    @NotBlank String name,
    UUID collectiveAccountId,
    String email,
    String phone,
    String address,
    @Pattern(regexp = "^$|^[0-9]{10}[A-Z]{2}$", message = "NIF Haïti doit être au format 10 chiffres + 2 lettres majuscules (ex: 1234567890AB)")
    String nif
) {
    /** Rétro-compatibilité — ancien constructeur 5-args sans nif ni phone. */
    public CreateThirdPartyRequest(
        @NotNull ThirdPartyType type,
        @NotBlank String name,
        UUID collectiveAccountId,
        String email,
        String address
    ) {
        this(type, name, collectiveAccountId, email, null, address, null);
    }

    /** Rétro-compatibilité — ancien constructeur 6-args avec nif mais sans phone. */
    public CreateThirdPartyRequest(
        @NotNull ThirdPartyType type,
        @NotBlank String name,
        UUID collectiveAccountId,
        String email,
        String address,
        String nif
    ) {
        this(type, name, collectiveAccountId, email, null, address, nif);
    }
}
