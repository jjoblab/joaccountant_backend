package jo.accountant.app.search;

import java.util.List;

/**
 * Task 16 : réponse de la recherche globale (Ctrl+K).
 *
 * <p>Renvoyée par {@code GET /api/v1/companies/{companyId}/search?q=...&limit=...}.
 * Contient le {@code query} echoé (pour debug + affichage « Aucun résultat pour « X » »)
 * et la liste des résultats fusionnés et triés par pertinence (les 5 modules
 * contribuent chacun jusqu'à 5 résultats).
 *
 * @param query texte recherché (echo du paramètre {@code q})
 * @param results liste fusionnée des résultats (max 25 = 5 × 5)
 
 *
 * @author jo@Dev


*/
public record GlobalSearchResponse(
    String query,
    List<GlobalSearchResult> results
) {}
