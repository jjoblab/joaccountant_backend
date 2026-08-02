package jo.accountant.tax.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Échéancier des déclarations fiscales pour un exercice (audit mobile #7).
 *
 * <p>Retourne un planning complet des échéances fiscales françaises pour l'année demandée :
 * <ul>
 * <li><b>TVA mensuelle</b> — dépôt + paiement de la TVA du mois M avant le 19 du mois M+1
 * (régime normal mensuel, art. 286 CGI). Télédéclaration obligatoire (EFI).</li>
 * <li><b>TVA trimestrielle</b> — alternative pour les entreprises dont la TVA annuelle &lt; 4 000 €
 * (4 échéances : avril, juillet, octobre, janvier N+1).</li>
 * <li><b>IS acomptes</b> — 4 acomptes (15 mars, 15 juin, 15 septembre, 15 décembre) calculés
 * sur l'IS N-1 (art. 1668 CGI). Télérèglement obligatoire.</li>
 * <li><b>IS solde</b> — solde de l'IS N au plus tard le 15 mai N+1 (art. 1668 CGI).</li>
 * <li><b>DES</b> — Déclaration d'Échanges de Services (intra-UE B2B), mensuelle, dépôt avant
 * le 10 du mois M+1 (article 289 B CGI / réglement DES 2022).</li>
 * </ul>
 *
 * <p><b>Limitation v1</b> : le planning est statique — il ne tient pas compte des jours fériés
 * ni des reports de weekend (en France, si l'échéance tombe un samedi/dimanche/jour férié, elle
 * est reportée au jour ouvré suivant — art. A. 40 A du Livre des procédures fiscales). Ce report
 * sera ajouté antérieurement via un {@code FrenchBusinessCalendar}.
 *
 * <p>Le {@code vatRegime} (MENSUEL/TRIMESTRIEL) est déterminé côté service à partir de la
 * {@code TaxRule} active de l'entreprise — par défaut MENSUEL.
 *
 * @param companyId l'entreprise concernée
 * @param year l'exercice (ex. 2026)
 * @param vatRegime "MENSUEL" ou "TRIMESTRIEL" — détermine le rythme des échéances TVA
 * @param deadlines liste des échéances triées par date croissante
 
 *
 * @author jo@Dev


*/
public record TaxDeclarationSchedule(
    UUID companyId,
    int year,
    String vatRegime,
    List<DeclarationDeadline> deadlines
) {

    /**
     * Une échéance fiscale.
     *
     * @param date date limite de dépôt/paiement
     * @param type type de déclaration : "VAT_MONTHLY", "VAT_QUARTERLY", "CORPORATE_TAX_INSTALLMENT",
     * "CORPORATE_TAX_BALANCE", "DES_MONTHLY"
     * @param label libellé lisible (ex: "TVA Mars 2026", "Acompte IS 1er trimestre 2026")
     */
    public record DeclarationDeadline(
        LocalDate date,
        String type,
        String label
    ) {}
}
