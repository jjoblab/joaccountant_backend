package jo.accountant.core.tax;

/**
 * Mode d'exigibilité de la TVA (TVA sur encaissement).
 *
 * <p>Deux régimes fiscaux coexistent pour la TVA collectée :
 * <ul>
 * <li>{@link #DEBIT} — <b>TVA sur débit</b> (régime par défaut, régime des débits en France /
 * régime normal SYSCOHADA). La TVA devient exigible dès l'émission de la facture,
 * c'est-à-dire au moment du débit du compte client. La TVA collectée est créditée au
 * compte 443 (TVA collectée) à l'émission.</li>
 * <li>{@link #ENCAISSEMENT} — <b>TVA sur encaissement</b> (régime des encaissements, art. 289
 * II du CGI / option pour les prestataires de services et les PME). La TVA n'est exigible
 * qu'au paiement effectif de la facture par le client. À l'émission, la TVA est stockée
 * dans un compte d'attente 4438 « TVA sur factures émises non encaissées ». Au
 * règlement, elle bascule du 4438 vers le 443 (TVA collectée).</li>
 * </ul>
 *
 * <p>Cette énumération est définie dans {@code :core} (et non dans {@code :tax}) car elle est
 * référencée à la fois par {@code :tax} (sur l'entité {@code TaxRule}) et par
 * {@code :invoicing} (via {@link jo.accountant.core.port.TaxRulePort}) — sans dépendance
 * circulaire Gradle.
 
 *
 * @author jo@Dev


*/
public enum VatMode {
 /** TVA sur débit — exigible à l'émission de la facture (régime par défaut). */
 DEBIT,

 /** TVA sur encaissement — exigible au paiement (régime des encaissements, art. 289 II CGI). */
 ENCAISSEMENT
}
