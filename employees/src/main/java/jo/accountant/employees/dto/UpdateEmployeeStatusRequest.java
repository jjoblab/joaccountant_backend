package jo.accountant.employees.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de requête pour {@code POST .../employees/{id}/status}.
 *
 * <p>Le mobile envoie un {@code @Body} JSON (et non plus un {@code @RequestParam}) — fix
 * du bug 2026-07-26 où le backend Spring attendait {@code ?status=TERMINATED} en query
 * string alors que le client Dart.post l'envoyait dans le body. Cela générait un 400
 * "Required request parameter 'status' is not present".
 *
 * <p>Champs :
 * <ul>
 *   <li>{@code status} — nouveau statut (ACTIVE, ON_LEAVE, TERMINATED) — obligatoire.</li>
 *   <li>{@code terminationDate} — date de fin de contrat (ISO yyyy-MM-dd) — optionnelle.
 *       Si non fournie et {@code status=TERMINATED}, la date du jour est utilisée par défaut.</li>
 *   <li>{@code terminationReason} — motif de fin de contrat (texte libre, max 500 car.) —
 *       optionnel mais recommandé pour la conformité Code du Travail Haïti art. 116.</li>
 * </ul>
 *
 * @author jo@Dev


*/
public record UpdateEmployeeStatusRequest(
    @NotBlank String status,
    String terminationDate,
    String terminationReason
) {
}
