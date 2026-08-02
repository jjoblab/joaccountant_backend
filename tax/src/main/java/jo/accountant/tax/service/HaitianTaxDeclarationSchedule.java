package jo.accountant.tax.service;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jo.accountant.tax.dto.TaxDeclarationSchedule;
import jo.accountant.tax.dto.TaxDeclarationSchedule.DeclarationDeadline;
import org.springframework.stereotype.Component;

/**
 * Calendrier déclaratif DGI Haïtien (Lot B ).
 *
 * <p>Avant la , le calendrier fiscal de {@link TaxService#getDeclarationSchedule} était
 * 100% français (CA3 le 19 du mois M+1, DES le 10, IS acomptes 15 mars/juin/sept/déc,
 * solde 15 mai N+1). Une entreprise haïtienne voyait donc des échéances <em>inexistantes
 * en Haïti</em> (DES n'existe pas hors UE) et manquait les échéances <em>réelles</em> de
 * la DGI (TVA/TCA/RS le 15, IS solde au 31 mars).
 *
 * <p>Le calendrier DGI Haïtien (Code Fiscal art. 5 et décret du 27 septembre 2005) est :
 *
 * <h2>Mensuel — dépôt + paiement avant le 15 du mois M+1</h2>
 * <ul>
 * <li><b>TVA</b> — Taxe sur la Valeur Ajoutée (art. 191, 10%).</li>
 * <li><b>TCA</b> — Taxe sur le Chiffre d'Affaires (art. 196/197, 2/5/10% selon secteur).</li>
 * <li><b>RS</b> — Retenue à la Source sur prestations (art. 156-1, 2% standard).</li>
 * <li><b>Acompte IS 1%</b> — Acompte sur l'Impôt sur les Sociétés, prélevé à 1% sur les
 * encaissements bruts (Code Fiscal art. 5). Cet acompte est imputable sur l'IS dû au
 * règlement du solde annuel.</li>
 * </ul>
 *
 * <h2>Annuel — dépôt + paiement au plus tard le 31 mars N+1</h2>
 * <ul>
 * <li><b>IS solde</b> — Impôt sur les Sociétés (art. 195, 30% du bénéfice fiscal).</li>
 * <li><b>DCR</b> — Déclaration Cession/Répartition (états financiers consolidés).</li>
 * <li><b>DCRf</b> — Déclaration Cession/Répartition filiales.</li>
 * <li><b>DCRG</b> — Déclaration Cession/Répartition groupe.</li>
 * <li><b>DCLS</b> — Déclaration Comptable et Légale Synthétique (liasse fiscale annuelle).</li>
 * </ul>
 *
 * <p><b>Limitation v1</b> : comme pour le calendrier français, les reports de weekend/jour
 * férié ne sont pas gérés (en Haïti, si le 15 tombe un samedi/dimanche/jour férié légal,
 * l'échéance est reportée au prochain jour ouvré — arrêté DGI). À enrichir en v4.8 via un
 * {@code HaitianBusinessCalendar}.
 *
 * <p>Les taux (10% TVA, 1% acompte IS, 30% IS solde) sont indicatifs 2024 — à valider par
 * un expert-comptable DGI avant mise en production.
 */
@Component
public class HaitianTaxDeclarationSchedule {

 /** Jour de dépôt mensuel DGI Haïti (15 du mois M+1). */
 private static final int MONTHLY_DEADLINE_DAY = 15;

 /** Jour de dépôt annuel DGI Haïti (31 mars N+1). */
 private static final int ANNUAL_DEADLINE_DAY = 31;
 private static final int ANNUAL_DEADLINE_MONTH = 3; // mars

 /**
 * Génère le planning annuel des échéances DGI Haïti pour l'exercice {@code year}.
 *
 * @param companyId l'entreprise (pour le DTO retourné)
 * @param year l'exercice (ex. 2024)
 * @return le planning complet (12 échéances mensuelles + 5 échéances annuelles)
 */
 public TaxDeclarationSchedule build(UUID companyId, int year) {
 List<DeclarationDeadline> deadlines = new ArrayList<>();

 // ── Échéances mensuelles (15 du mois M+1) ──
 // Pour l'année N : TVA/TCA/RS/Acompte IS 1% de janvier N → échéance 15 fév N, etc.
 // La dernière échéance (décembre N) tombe le 15 janvier N+1.
 for (int m = 0; m < 12; m++) {
 int declaredMonthIdx = m; // 0..11 (janv..déc)
 int deadlineMonthIdx = (declaredMonthIdx + 1) % 12;
 int deadlineYear = (declaredMonthIdx + 1) > 11 ? year + 1 : year;
 LocalDate due = LocalDate.of(deadlineYear, deadlineMonthIdx + 1, MONTHLY_DEADLINE_DAY);
 String monthLabel = Month.of(declaredMonthIdx + 1)
 .getDisplayName(TextStyle.FULL, Locale.FRENCH);

 // Une échéance composite par mois — 4 déclarations DGI distinctes mais même date.
 // On expose 4 DeclarationDeadline séparées pour permettre un suivi indépendant
 // (statut "déposé"/"à déposer" par type de taxe).
 deadlines.add(new DeclarationDeadline(
 due, "VAT_MONTHLY_HT", "TVA " + capitalize(monthLabel) + " " + year));
 deadlines.add(new DeclarationDeadline(
 due, "TCA_MONTHLY_HT", "TCA " + capitalize(monthLabel) + " " + year));
 deadlines.add(new DeclarationDeadline(
 due, "WITHHOLDING_MONTHLY_HT", "RS " + capitalize(monthLabel) + " " + year));
 deadlines.add(new DeclarationDeadline(
 due, "CORPORATE_TAX_INSTALLMENT_HT",
 "Acompte IS 1% (art. 5) " + capitalize(monthLabel) + " " + year));
 }

 // ── Échéances annuelles (31 mars N+1) ──
 // Toutes ces déclarations sont dues au plus tard le 31 mars de l'année suivante.
 LocalDate annualDue = LocalDate.of(year + 1, ANNUAL_DEADLINE_MONTH, ANNUAL_DEADLINE_DAY);
 deadlines.add(new DeclarationDeadline(
 annualDue, "CORPORATE_TAX_BALANCE_HT",
 "Solde IS exercice " + year + " (Code Fiscal art. 195)"));
 deadlines.add(new DeclarationDeadline(
 annualDue, "DCR_ANNUAL_HT", "DCR exercice " + year));
 deadlines.add(new DeclarationDeadline(
 annualDue, "DCRF_ANNUAL_HT", "DCRf exercice " + year));
 deadlines.add(new DeclarationDeadline(
 annualDue, "DCRG_ANNUAL_HT", "DCRG exercice " + year));
 deadlines.add(new DeclarationDeadline(
 annualDue, "DCLS_ANNUAL_HT", "DCLS exercice " + year));

 // Trier par date croissante
 deadlines.sort(Comparator.comparing(DeclarationDeadline::date));

 return new TaxDeclarationSchedule(companyId, year, "MENSUEL_HT", deadlines);
 }

 private static String capitalize(String s) {
 if (s == null || s.isEmpty()) return s;
 return Character.toUpperCase(s.charAt(0)) + s.substring(1);
 }
}
