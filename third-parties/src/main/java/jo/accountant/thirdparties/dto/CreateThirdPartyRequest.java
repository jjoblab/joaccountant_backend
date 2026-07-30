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
 * @param collectiveAccountId ID du compte collectif (doit avoir isCollective=true)
 * @param email email optionnel
 * @param address adresse optionnelle
 * @param nif NIF du tiers (Numéro d'Identification Fiscale). Format Haïti : 10 chiffres + 2 lettres {@code ^[0-9]{10}[A-Z]{2}$}.
 *            Optionnel mais recommandé pour les clients/suppliers assujettis (Code Fiscal art. 196 — mentions factures).
 *            R-F-validation (lot-G) : précédemment manquant côté API.
 */
public record CreateThirdPartyRequest(
    @NotNull ThirdPartyType type,
    @NotBlank String name,
    @NotNull UUID collectiveAccountId,
    String email,
    String address,
    @Pattern(regexp = "^$|^[0-9]{10}[A-Z]{2}$", message = "NIF Haïti doit être au format 10 chiffres + 2 lettres majuscules (ex: 1234567890AB)")
    String nif
) {
    /** Rétro-compatibilité — ancien constructeur 5-args sans nif. */
    public CreateThirdPartyRequest(
        @NotNull ThirdPartyType type,
        @NotBlank String name,
        @NotNull UUID collectiveAccountId,
        String email,
        String address
    ) {
        this(type, name, collectiveAccountId, email, address, null);
    }
}
