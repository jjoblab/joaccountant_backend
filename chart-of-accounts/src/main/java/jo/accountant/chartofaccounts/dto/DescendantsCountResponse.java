package jo.accountant.chartofaccounts.dto;

/**
 * Réponse simple pour {@code GET .../{accountId}/descendants-count}.
 *
 * @param count nombre de descendants directs + indirects du compte
 
 *
 * @author jo@Dev


*/
public record DescendantsCountResponse(long count) {}
