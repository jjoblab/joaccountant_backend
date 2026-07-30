package jo.accountant.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;

/**
 * V8.2 — Création d'une société (wizard étape 1 refondu).
 *
 * <p>Refonte : tous les champs d'identité sont collectés en UNE seule requête.
 * Plus de defaults factices — l'entreprise est créée avec des valeurs réelles.
 *
 * <p>Champs légaux (siret, vatNumber, nif, address) sont optionnels à ce stade
 * et éditables plus tard via PATCH /companies/{id}/legal.
 */
public record CreateCompanyRequest(
    @NotBlank @Size(min = 3, max = 200) String name,
    @NotBlank @Size(min = 2, max = 2) String country,
    @NotBlank @Size(min = 3, max = 3) String functionalCurrency,
    @NotNull OrganizationNature organizationNature,
    @NotNull LegalForm legalForm,
    String siret,
    String vatNumber,
    String nif,
    String address
) {}
