package jo.accountant.company.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Mise à jour partielle des champs légaux d'une Company—.
 *
 * <p>Endpoint : {@code PATCH /api/v1/companies/{companyId}/legal}. Sémantique de mise à jour
 * partielle : seuls les champs non-nuls fournis dans le payload sont écrasés. Une chaîne vide
 * ou blank est traitée comme {@code null} côté service (effacement du champ).
 *
 * <p>Les 4 champs sont validés via {@code @Pattern} (le pattern accepte la chaîne vide pour
 * permettre l'effacement) :
 * <ul>
 * <li><strong>siret</strong> — 14 chiffres (SIRET français). Null si non applicable
 * (ex: entreprise haïtienne — utiliser {@code nif} à la place).</li>
 * <li><strong>vatNumber</strong> — 2 lettres (code pays ISO 3166-1 alpha-2) suivi de
 * caractères alphanumériques (ex: {@code FR12345678901}). Null si non assujetti.</li>
 * <li><strong>nif</strong> — identifiant fiscal alphanumérique (Numéro d'Identification
 * Fiscale) utilisé hors France (Haïti, OHADA).</li>
 * <li><strong>address</strong> — adresse postale libre (une ligne), texte libre sans
 * pattern mais limité à 500 caractères.</li>
 * </ul>
 *
 * <p>Ces champs sont requis pour les mentions légales des factures (CGI art. 289) et pour le
 * Factur-X. Ils sont éditables post-wizard via cet endpoint.
 
 *
 * @author jo@Dev


*/
public record UpdateCompanyLegalFieldsRequest(
    @Pattern(regexp = "^$|^[0-9]{14}$",
        message = "Le SIRET doit contenir exactement 14 chiffres")
    String siret,

    @Pattern(regexp = "^$|^[A-Za-z]{2}[A-Za-z0-9]{1,18}$",
        message = "Le numéro de TVA doit commencer par 2 lettres (code pays) suivi de caractères alphanumériques")
    String vatNumber,

    @Pattern(regexp = "^$|^[A-Za-z0-9 -]{1,30}$",
        message = "Le NIF doit être alphanumérique (max 30 caractères)")
    String nif,

    @Size(max = 500, message = "L'adresse ne peut pas dépasser 500 caractères")
    String address,

    // Fix Dim 2 C2 (audit v9.4) — Permet de positionner taxExemptionStatus et isFreeZone
    // post-wizard via PATCH /companies/{id}/legal. Avant ce fix, aucun endpoint API ne
    // permettait de le faire — seul le module :demo-data (NgoHumanitarianSeeder) ou une
    // intervention SQL directe pouvait positionner ces flags. Une entreprise en Zone Franche
    // ou une ONG mal catégorisée lors du wizard n'avait aucun moyen de corriger son statut
    // fiscal → IS calculé à 30% au lieu de 15% (ZF) ou 0% (ONG), CF art. 195.
    /** Statut d'exonération fiscale (STANDARD, FREE_ZONE, NGO_EXEMPT). Null = pas de mise à jour. */
    String taxExemptionStatus,

    /** Positionne le flag is_free_zone. Null = pas de mise à jour. */
    Boolean isFreeZone
) {}
