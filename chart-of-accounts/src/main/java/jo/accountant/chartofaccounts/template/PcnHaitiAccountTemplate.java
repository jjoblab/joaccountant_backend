package jo.accountant.chartofaccounts.template;

import java.util.List;
import jo.accountant.chartofaccounts.template.SectorAccountTemplate.AccountSeed;
import jo.accountant.core.framework.ReportingClass;

/**
 * Template du Plan Comptable National Haïtien (PCN_HAITI) — R-42 (lot-F1-code-arch).
 *
 * <p><b>Problème</b> : avant R-42, le PCN Haïtien était traité comme un SYSCOHADA renommé — la
 * classe 8 (qui en PCN Haïtien est "Comptes spéciaux" : engagements hors bilan + comptes de
 * régularisation) était mappée à {@code CHARGES/PRODUITS} (HAO) comme en SYSCOHADA. Conséquence :
 * les engagements hors bilan (qui ne sont PAS des charges d'exploitation) remontaient dans le
 * compte de résultat et faussaient le résultat net.
 *
 * <p><b>Solution</b> : ce template définit le plan comptable de référence du PCN Haïtien, avec
 * la spécificité de la classe 8 mappée à {@link ReportingClass#OTHER} (cf.
 * {@code ChartOfAccountsService.inferReportingClass} pour la branche PCN_HAITI dédiée).
 *
 * <p><b>Structure du PCN Haïtien</b> (différences notables vs SYSCOHADA) :
 * <ul>
 *   <li><b>Classe 1</b> — Capitaux : 10xx Capital, 11xx Réserves, 12xx Report à nouveau, 14xx Subventions</li>
 *   <li><b>Classe 2</b> — Immobilisations : 20xx Terrains, 21xx Constructions, 22xx Matériel, 28xx Amortissements</li>
 *   <li><b>Classe 3</b> — Stocks : 30xx Marchandises, 31xx Matières premières, 37xx Stocks à l'extérieur</li>
 *   <li><b>Classe 4</b> — Tiers : 401 Fournisseurs, 411 Clients, 42 État-RS, 44 État-IS, 45 État-TCA,
 *       46 État-TVA, 47 État-taxes diverses, 48 État-reste (les comptes "État xxx" sont spécifiques
 *       au contexte fiscal haïtien — RS = Retenues à la source, TCA = Taxe sur le Chiffre d'Affaires,
 *       IS = Impôt sur les Sociétés)</li>
 *   <li><b>Classe 5</b> — Financiers : 50xx Valeurs mobilières, 51xx Banques, 53xx Caisse</li>
 *   <li><b>Classe 6</b> — Charges : 60xx Achats, 61xx Transports, 62xx Services extérieurs,
 *       63xx Charges personnel, 64xx Impôts taxes, 65xx Financières, 66xx Charges diverses</li>
 *   <li><b>Classe 7</b> — Produits : 70xx Ventes, 71xx Subventions, 75xx Financiers, 77xx Produits divers</li>
 *   <li><b>Classe 8</b> — <strong>Comptes spéciaux</strong> (engagements hors bilan, comptes de
 *       régularisation) — <em>DISTINCT de SYSCOHADA où classe 8 = HAO</em>. Mappé à
 *       {@link ReportingClass#OTHER} : ces comptes ne remontent NI dans le compte de résultat
 *       (ne sont pas des charges/produits) NI dans le bilan standard (ce sont des engagements
 *       hors bilan, affichés en annexe).</li>
 *   <li><b>Classe 9</b> — Comptes analytiques (optionnel — non mappé ici)</li>
 * </ul>
 *
 * <p><b>Usage</b> : ce template est un référentiel de définition du PCN Haïtien. Il peut être
 * consommé par une méthode d'initialisation dédiée (future évolution de
 * {@code ChartOfAccountsService.initializeMandated} pour PCN_HAITI), ou utilisé pour valider
 * qu'un plan comptable existant respecte la nomenclature PCN.
 *
 * <p><b>Backward compat</b> : ce template est nouveau (R-42) et n'est pas encore wiring dans
 * {@code ChartOfAccountsService} — l'initialisation actuelle crée uniquement les comptes de
 * niveau 1 (les "classes") via le {@code mandatedClassSeedJson} du référentiel. Le seed des
 * comptes niveau 2+ spécifiques au PCN Haïtien se fait via la migration V60 (comptes 442, 446,
 * 447, 448, 4438 — sous-classe 44 "État") qui est idempotente. Une future évolution pourrait
 * appeler {@link #pcnHaitiAccounts()} depuis {@code initializeMandated} pour créer
 * automatiquement le plan complet niveau 2+ lors de l'initialisation d'une entreprise PCN_HAITI.
 *
 * @see SectorAccountTemplate
 * @see jo.accountant.core.framework.ReportingClass#OTHER
 */
public final class PcnHaitiAccountTemplate {

    private PcnHaitiAccountTemplate() {}

    /**
     * Retourne la liste des comptes niveau 2+ à créer pour une entreprise utilisant le PCN Haïtien.
     *
     * <p>Contrairement à {@link SectorAccountTemplate#forBusinessType(String)} qui spécialise par
     * type métier, cette méthode retourne un set UNIQUE pour le PCN Haïtien (toutes les entreprises
     * PCN_HAITI ont besoin de la même structure de base : capitaux, immo, stocks, tiers, financier,
     * charges, produits, comptes spéciaux).
     *
     * <p>Convention : reprend le format {@link AccountSeed} de {@link SectorAccountTemplate} pour
     * permettre la réutilisation de la logique de {@code ChartOfAccountsService.seedSectorAccounts}.
     */
    public static List<AccountSeed> pcnHaitiAccounts() {
        return List.of(
            // ════════ Classe 1 — Capitaux ════════
            new AccountSeed("1", "10", "Capital",                         ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("10", "101", "Capital social",                ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("1", "11", "Réserves",                        ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("11", "110", "Réserve légale",                ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("1", "12", "Report à nouveau",                ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("12", "120", "Report à nouveau créditeur",    ReportingClass.CAPITAUX_PROPRES, "EQUITY",      "CREDIT", false, null),
            new AccountSeed("1", "14", "Subventions",                     ReportingClass.CAPITAUX_PROPRES, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("14", "141", "Subventions d'équipement",      ReportingClass.CAPITAUX_PROPRES, "NON_COURANT", "CREDIT", false, null),

            // ════════ Classe 2 — Immobilisations ════════
            new AccountSeed("2", "20", "Terrains",                        ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("20", "200", "Terrains nus",                  ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "21", "Constructions",                   ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("21", "210", "Bâtiments industriels",         ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "22", "Matériel",                        ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("22", "220", "Matériel industriel",           ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("22", "221", "Matériel de bureau",            ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements",                  ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "280", "Amort. terrains",               ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "281", "Amort. constructions",          ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "282", "Amort. matériel",               ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),

            // ════════ Classe 3 — Stocks ════════
            new AccountSeed("3", "30", "Marchandises",                    ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("30", "300", "Marchandises en stock",         ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("3", "31", "Matières premières",              ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("31", "310", "Matières premières en stock",   ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("3", "37", "Stocks à l'extérieur",            ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("37", "370", "Stocks en dépôt",               ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),

            // ════════ Classe 4 — Tiers (spécificité PCN Haïtien — État-RS, État-IS, État-TCA, État-TVA, État-taxes diverses, État-reste) ════════
            new AccountSeed("4", "40", "Fournisseurs",                    ReportingClass.PASSIF, "COURANT", "CREDIT", true,  "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux",           ReportingClass.PASSIF, "COURANT", "CREDIT", true,  "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients",                         ReportingClass.ACTIF,  "COURANT", "DEBIT",  true,  "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Clients locaux",                ReportingClass.ACTIF,  "COURANT", "DEBIT",  true,  "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "État - Retenues à la source (RS)", ReportingClass.PASSIF, "COURANT", "CREDIT", true,  null),
            new AccountSeed("42", "442", "État-RS — Retenues à la source", ReportingClass.PASSIF, "COURANT", "CREDIT", false, null),
            new AccountSeed("4", "44", "État - Impôt sur les sociétés (IS)", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "447", "État-IS — Impôt sur les sociétés", ReportingClass.PASSIF, "COURANT", "CREDIT", false, null),
            new AccountSeed("4", "45", "État - TCA",                       ReportingClass.PASSIF, "COURANT", "CREDIT", true,  null),
            new AccountSeed("45", "446", "État-TCA — Taxe sur chiffre d'affaires", ReportingClass.PASSIF, "COURANT", "CREDIT", false, null),
            new AccountSeed("4", "46", "État - TVA",                       ReportingClass.PASSIF, "COURANT", "CREDIT", true,  null),
            new AccountSeed("46", "443",  "TVA collectée",                 ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("46", "4438", "TVA différée non encaissée",    ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_DEFERRED_UNCOLLECTED"),
            new AccountSeed("4", "47", "État - Taxes diverses",            ReportingClass.PASSIF, "COURANT", "CREDIT", true,  null),
            new AccountSeed("47", "448", "État-taxes diverses",            ReportingClass.PASSIF, "COURANT", "CREDIT", false, null),
            new AccountSeed("4", "48", "État - Reste à payer",             ReportingClass.PASSIF, "COURANT", "CREDIT", true,  null),

            // ════════ Classe 5 — Financiers ════════
            new AccountSeed("5", "50", "Valeurs mobilières",              ReportingClass.ACTIF, "COURANT",     "DEBIT", false, null),
            new AccountSeed("50", "500", "Actions",                       ReportingClass.ACTIF, "COURANT",     "DEBIT", false, null),
            new AccountSeed("5", "51", "Banques",                         ReportingClass.ACTIF, "COURANT",     "DEBIT", false, "CASH"),
            new AccountSeed("51", "510", "Banque Nationale",              ReportingClass.ACTIF, "COURANT",     "DEBIT", false, "CASH"),
            new AccountSeed("5", "53", "Caisse",                          ReportingClass.ACTIF, "COURANT",     "DEBIT", false, "CASH"),
            new AccountSeed("53", "530", "Caisse principale",             ReportingClass.ACTIF, "COURANT",     "DEBIT", false, "CASH"),

            // ════════ Classe 6 — Charges ════════
            new AccountSeed("6", "60", "Achats",                          ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "600", "Achats de marchandises",        ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("6", "61", "Transports",                      ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("61", "610", "Transport sur achats",          ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "62", "Services extérieurs",             ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "620", "Loyer commercial",              ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel",            ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "630", "Salaires et appointements",     ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("6", "64", "Impôts et taxes",                 ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("64", "640", "Impôts et taxes divers",        ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "65", "Charges financières",             ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("65", "650", "Intérêts bancaires",            ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "66", "Charges diverses",                ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("66", "660", "Pertes diverses",               ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),

            // ════════ Classe 7 — Produits ════════
            new AccountSeed("7", "70", "Ventes",                          ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "700", "Ventes de marchandises",        ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("7", "71", "Subventions d'exploitation",      ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("71", "710", "Subventions reçues",            ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("7", "75", "Produits financiers",             ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "750", "Intérêts créditeurs",           ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("7", "77", "Produits divers",                 ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("77", "770", "Gains divers",                  ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),

            // ════════ Classe 8 — Comptes spéciaux (spécificité PCN Haïtien — NON HAO) ════════
            // Mappés à ReportingClass.OTHER : ni HAO (CHARGES/PRODUITS) ni OPERATING (ACTIF/PASSIF).
            // Exclus du compte de résultat ET du bilan standard — affichés en annexe (engagements
            // hors bilan) ou en régularisation (selon le sous-compte).
            new AccountSeed("8", "80", "Engagements hors bilan",          ReportingClass.OTHER, "N_A", "CREDIT", false, null),
            new AccountSeed("80", "800", "Engagements de financement",    ReportingClass.OTHER, "N_A", "CREDIT", false, null),
            new AccountSeed("80", "801", "Engagements de garantie",       ReportingClass.OTHER, "N_A", "CREDIT", false, null),
            new AccountSeed("8", "81", "Comptes de régularisation",       ReportingClass.OTHER, "N_A", "CREDIT", false, null),
            new AccountSeed("81", "810", "Charges constatées d'avance",   ReportingClass.OTHER, "N_A", "DEBIT",  false, null),
            new AccountSeed("81", "811", "Produits constatés d'avance",   ReportingClass.OTHER, "N_A", "CREDIT", false, null)
        );
    }
}
