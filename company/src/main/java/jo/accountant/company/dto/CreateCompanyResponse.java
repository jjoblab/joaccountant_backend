package jo.accountant.company.dto;

/**
 * Réponse de POST /api/v1/companies (création d'entreprise).
 *
 * <p>Contient la {@link CompanyResponse} + un nouveau JWT fraîchement émis avec le claim
 * {@code companies} mis à jour (incluant la nouvelle company).
 *
 * <p><b>Pourquoi un nouveau JWT ?</b> Le JWT émis au login contient un claim {@code companies}
 * qui liste les companies auxquelles l'utilisateur a accès. Quand l'utilisateur crée une
 * nouvelle company APRÈS son login, ce claim est obsolète — la nouvelle company n'y figure pas.
 *
 * <p>Le {@code TenantClaimFilter} et le {@code RoleChecker} vérifient ce claim pour autoriser
 * l'accès aux endpoints company-scoped. Sans refresh du JWT, les requêtes vers
 * {@code /companies/{id}/wizard/*} seraient rejetées (404/403).
 *
 * <p><b>Solution définitive</b> : le backend émet un nouveau JWT dans la réponse de
 * {@code POST /companies}, avec le claim {@code companies} à jour. Le client mobile stocke
 * ce nouveau JWT et l'utilise pour les requêtes suivantes. Pas de fall-back DB, pas de
 * re-login — le JWT est toujours à jour.
 *
 * @param company la company créée
 * @param accessToken nouveau JWT avec claim companies mis à jour
 * @param refreshToken nouveau refresh token (rotation)
 * @param expiresIn durée de validité du access token (secondes)
 
 *
 * @author jo@Dev


*/
public record CreateCompanyResponse(
    CompanyResponse company,
    String accessToken,
    String refreshToken,
    long expiresIn
) {}
