package jo.accountant.demo.support;

import java.util.List;
import jo.accountant.chartofaccounts.entity.NormalBalance;
import jo.accountant.core.framework.ReportingClass;

/**
 * V9 — Référentiel statique des 50 comptes PCN_HAITI les plus courants pour les entreprises démo.
 *
 * <p>Ce référentiel est consommé par {@link ChartOfAccountsBootstrap} pour créer le plan comptable
 * d'une entreprise démo. Il couvre les 7 classes principales du PCN Haïtien (capitaux,
 * immobilisations, stocks, tiers, financiers, charges, produits) avec les comptes de niveau 3
 * (codes à 6 chiffres) utilisés en pratique par les 4 seeders (Boutik Lakay, Moïse &amp; Associés,
 * Espwa pou Ayiti, Caribbean Textiles).
 *
 * <p><b>Différence avec {@code PcnHaitiAccountTemplate}</b> : le template du module {@code
 * chart-of-accounts} définit les comptes de niveaux 1, 2 et 3 (codes courts type "10", "101",
 * "411") — il est wiring dans {@code ChartOfAccountsService.initializeMandated} et crée
 * automatiquement le squelette hiérarchique du PCN lors de l'initialisation.
 *
 * <p>{@code AccountFixture} complète ce squelette avec des comptes <em>feuilles</em> à 6 chiffres
 * (ex. {@code 411000 Clients}, {@code 521000 Banque}) prêts à recevoir des écritures. Sans ces
 * comptes feuilles, les seeders ne pourraient pas générer d'écritures comptables (le moteur refuse
 * d'écrire sur un compte de regroupement non collectif).
 *
 * <p><b>Convention de mapping ReportingClass</b> :
 *
 * <ul>
 *   <li>Classe 1 (capitaux, réserves, report à nouveau, résultat) → {@link
 *       ReportingClass#CAPITAUX_PROPRES}
 *   <li>Classe 2 (immo corporelles + titres + amortissements) → {@link ReportingClass#ACTIF} (même
 *       les amortissements, qui ont un solde CREDIT mais restent classés ACTIF car ils sont des
 *       moins-values d'actif — convention PCN/SYSCOHADA)
 *   <li>Classe 3 (stocks) → {@link ReportingClass#ACTIF}
 *   <li>Classe 4 (tiers) → mixte ACTIF (clients, débiteurs) / PASSIF (fournisseurs, état, TVA
 *       collectée)
 *   <li>Classe 5 (trésorerie) → {@link ReportingClass#ACTIF}
 *   <li>Classe 6 (charges) → {@link ReportingClass#CHARGES}
 *   <li>Classe 7 (produits) → {@link ReportingClass#PRODUITS}
 * </ul>
 *
 * <p><b>Comptes collectifs</b> : les comptes 401000 (Fournisseurs) et 411000 (Clients) sont marqués
 * {@code collective=true} car ils regroupent des tiers individuels (un sous-compte par
 * client/fournisseur). Les autres comptes sont des comptes généraux ({@code collective=false}).
 *
 * @param code code du compte (6 chiffres, ex. "411000")
 * @param label libellé du compte (ex. "Clients")
 * @param reportingClass classification universelle (consommée par financial-statements)
 * @param normalBalance sens normal du solde (DEBIT pour actif/charges, CREDIT pour
 *     passif/capitaux/produits)
 * @param collective true si compte collectif (regroupement de tiers)
 */
public record AccountFixture(
    String code,
    String label,
    ReportingClass reportingClass,
    NormalBalance normalBalance,
    boolean collective) {

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 1 — Capitaux propres
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture CAPITAL_SOCIAL =
      new AccountFixture(
          "1013", "Capital social", ReportingClass.CAPITAUX_PROPRES, NormalBalance.CREDIT, false);

  public static final AccountFixture RESERVES =
      new AccountFixture(
          "106000", "Réserves", ReportingClass.CAPITAUX_PROPRES, NormalBalance.CREDIT, false);

  public static final AccountFixture REPORT_A_NOUVEAU =
      new AccountFixture(
          "110000",
          "Report à nouveau",
          ReportingClass.CAPITAUX_PROPRES,
          NormalBalance.CREDIT,
          false);

  public static final AccountFixture RESULTAT_EXERCICE =
      new AccountFixture(
          "120000",
          "Résultat de l'exercice",
          ReportingClass.CAPITAUX_PROPRES,
          NormalBalance.CREDIT,
          false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 2 — Immobilisations
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture TERRAINS =
      new AccountFixture("211000", "Terrains", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture CONSTRUCTIONS =
      new AccountFixture(
          "213000", "Constructions", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture INSTALLATIONS_TECHNIQUES =
      new AccountFixture(
          "215000",
          "Installations techniques matériel et outillage",
          ReportingClass.ACTIF,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture AUTRES_IMMO_CORPORELLES =
      new AccountFixture(
          "218000",
          "Autres immobilisations corporelles",
          ReportingClass.ACTIF,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture TITRES_PARTICIPATION =
      new AccountFixture(
          "271000", "Titres de participation", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture AMORTISSEMENTS =
      new AccountFixture(
          "280000", "Amortissements", ReportingClass.ACTIF, NormalBalance.CREDIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 3 — Stocks
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture STOCKS_MARCHANDISES =
      new AccountFixture(
          "310000", "Stocks de marchandises", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture STOCKS_PRODUITS_FINIS =
      new AccountFixture(
          "350000", "Stocks de produits finis", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 4 — Tiers
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture FOURNISSEURS =
      new AccountFixture(
          "401000", "Fournisseurs", ReportingClass.PASSIF, NormalBalance.CREDIT, true);

  public static final AccountFixture CLIENTS =
      new AccountFixture("411000", "Clients", ReportingClass.ACTIF, NormalBalance.DEBIT, true);

  public static final AccountFixture PERSONNEL_REMUNERATIONS_DUES =
      new AccountFixture(
          "421000",
          "Personnel - rémunérations dues",
          ReportingClass.PASSIF,
          NormalBalance.CREDIT,
          true);  // V8.3 — collective=true car utilisé comme collectiveAccountId pour les employés

  public static final AccountFixture PRETS_CREANCES_PERSONNEL =
      new AccountFixture(
          "422000",
          "Prêts et créances sur le personnel",
          ReportingClass.ACTIF,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture SECURITE_SOCIALE =
      new AccountFixture(
          "431000", "Sécurité sociale", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  public static final AccountFixture RETENUES_FISCALES =
      new AccountFixture(
          "433000", "Retenues fiscales", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  public static final AccountFixture ETAT_IMPOTS_A_PAYER =
      new AccountFixture(
          "440000", "État - impôts à payer", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  public static final AccountFixture TVA_COLLECTEE =
      new AccountFixture(
          "443000", "TVA collectée", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  public static final AccountFixture TVA_DEDUCTIBLE =
      new AccountFixture(
          "445000", "TVA déductible", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture TCA_COLLECTEE =
      new AccountFixture(
          "446000", "TCA collectée", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  public static final AccountFixture TCA_DEDUCTIBLE =
      new AccountFixture(
          "447000", "TCA déductible", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture DEBITS_CREDITS_DIVERS =
      new AccountFixture(
          "471000", "Débits et crédits divers", ReportingClass.PASSIF, NormalBalance.CREDIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 5 — Trésorerie
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture BANQUE =
      new AccountFixture("521000", "Banque", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  public static final AccountFixture CAISSE =
      new AccountFixture("530000", "Caisse", ReportingClass.ACTIF, NormalBalance.DEBIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 6 — Charges
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture ACHATS_MARCHANDISES =
      new AccountFixture(
          "601000", "Achats de marchandises", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture VARIATION_STOCKS =
      new AccountFixture(
          "603000", "Variation de stocks", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture LOCATIONS =
      new AccountFixture("613000", "Locations", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture CHARGES_LOCATIVES =
      new AccountFixture(
          "614000", "Charges locatives", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture ENTRETIEN_REPARATIONS =
      new AccountFixture(
          "615000", "Entretien et réparations", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture PRIMES_ASSURANCE =
      new AccountFixture(
          "616000", "Primes d'assurance", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture REMUNERATIONS_INTERMEDIAIRES =
      new AccountFixture(
          "622000",
          "Rémunérations d'intermédiaires",
          ReportingClass.CHARGES,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture PUBLICITE =
      new AccountFixture("623000", "Publicité", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture DEPLACEMENTS_MISSIONS =
      new AccountFixture(
          "625000", "Déplacements missions", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture FRAIS_POSTAUX_TELECOM =
      new AccountFixture(
          "626000",
          "Frais postaux et télécommunications",
          ReportingClass.CHARGES,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture SERVICES_BANCAIRES =
      new AccountFixture(
          "627000", "Services bancaires", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture IMPOTS_TAXES =
      new AccountFixture(
          "631000", "Impôts et taxes", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture REMUNERATIONS_PERSONNEL =
      new AccountFixture(
          "641000",
          "Rémunérations du personnel",
          ReportingClass.CHARGES,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture CHARGES_SECURITE_SOCIALE =
      new AccountFixture(
          "645000",
          "Charges de sécurité sociale",
          ReportingClass.CHARGES,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture CHARGES_DIVERS_GESTION =
      new AccountFixture(
          "658000",
          "Charges diverses de gestion courante",
          ReportingClass.CHARGES,
          NormalBalance.DEBIT,
          false);

  public static final AccountFixture CHARGES_INTERETS =
      new AccountFixture(
          "661000", "Charges d'intérêts", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  public static final AccountFixture PERTES_EXCEPTIONNELLES =
      new AccountFixture(
          "671000", "Pertes exceptionnelles", ReportingClass.CHARGES, NormalBalance.DEBIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Classe 7 — Produits
  // ════════════════════════════════════════════════════════════════════════

  public static final AccountFixture VENTES_MARCHANDISES =
      new AccountFixture(
          "701000", "Ventes de marchandises", ReportingClass.PRODUITS, NormalBalance.CREDIT, false);

  public static final AccountFixture PRESTATIONS_SERVICES =
      new AccountFixture(
          "706000",
          "Prestations de services",
          ReportingClass.PRODUITS,
          NormalBalance.CREDIT,
          false);

  public static final AccountFixture VENTES_PRODUITS_FINIS =
      new AccountFixture(
          "707000",
          "Ventes de produits finis",
          ReportingClass.PRODUITS,
          NormalBalance.CREDIT,
          false);

  public static final AccountFixture PRODUITS_ACCESSOIRES =
      new AccountFixture(
          "708000", "Produits accessoires", ReportingClass.PRODUITS, NormalBalance.CREDIT, false);

  public static final AccountFixture SUBVENTIONS_EXPLOITATION =
      new AccountFixture(
          "740000",
          "Subventions d'exploitation",
          ReportingClass.PRODUITS,
          NormalBalance.CREDIT,
          false);

  public static final AccountFixture AUTRES_PRODUITS_GESTION =
      new AccountFixture(
          "750000",
          "Autres produits de gestion courante",
          ReportingClass.PRODUITS,
          NormalBalance.CREDIT,
          false);

  public static final AccountFixture PRODUITS_EXCEPTIONNELS =
      new AccountFixture(
          "771000", "Produits exceptionnels", ReportingClass.PRODUITS, NormalBalance.CREDIT, false);

  // ════════════════════════════════════════════════════════════════════════
  //  Catalogue complet
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Liste exhaustive des 50 comptes du référentiel, triés par code croissant.
   *
   * <p>Utilisé par défaut par {@link ChartOfAccountsBootstrap#bootstrap} pour créer le plan
   * comptable complet d'une entreprise démo. Les seeders spécifiques peuvent filtrer cette liste
   * via les méthodes utilitaires (ex. {@link #revenueAccounts()}) si seule une sous-section du plan
   * est nécessaire.
   *
   * @return liste non-modifiable des 50 AccountFixture
   */
  public static List<AccountFixture> all() {
    return List.of(
        // Classe 1
        CAPITAL_SOCIAL,
        RESERVES,
        REPORT_A_NOUVEAU,
        RESULTAT_EXERCICE,
        // Classe 2
        TERRAINS,
        CONSTRUCTIONS,
        INSTALLATIONS_TECHNIQUES,
        AUTRES_IMMO_CORPORELLES,
        TITRES_PARTICIPATION,
        AMORTISSEMENTS,
        // Classe 3
        STOCKS_MARCHANDISES,
        STOCKS_PRODUITS_FINIS,
        // Classe 4
        FOURNISSEURS,
        CLIENTS,
        PERSONNEL_REMUNERATIONS_DUES,
        PRETS_CREANCES_PERSONNEL,
        SECURITE_SOCIALE,
        RETENUES_FISCALES,
        ETAT_IMPOTS_A_PAYER,
        TVA_COLLECTEE,
        TVA_DEDUCTIBLE,
        TCA_COLLECTEE,
        TCA_DEDUCTIBLE,
        DEBITS_CREDITS_DIVERS,
        // Classe 5
        BANQUE,
        CAISSE,
        // Classe 6
        ACHATS_MARCHANDISES,
        VARIATION_STOCKS,
        LOCATIONS,
        CHARGES_LOCATIVES,
        ENTRETIEN_REPARATIONS,
        PRIMES_ASSURANCE,
        REMUNERATIONS_INTERMEDIAIRES,
        PUBLICITE,
        DEPLACEMENTS_MISSIONS,
        FRAIS_POSTAUX_TELECOM,
        SERVICES_BANCAIRES,
        IMPOTS_TAXES,
        REMUNERATIONS_PERSONNEL,
        CHARGES_SECURITE_SOCIALE,
        CHARGES_DIVERS_GESTION,
        CHARGES_INTERETS,
        PERTES_EXCEPTIONNELLES,
        // Classe 7
        VENTES_MARCHANDISES,
        PRESTATIONS_SERVICES,
        VENTES_PRODUITS_FINIS,
        PRODUITS_ACCESSOIRES,
        SUBVENTIONS_EXPLOITATION,
        AUTRES_PRODUITS_GESTION,
        PRODUITS_EXCEPTIONNELS);
  }

  // ════════════════════════════════════════════════════════════════════════
  //  Méthodes utilitaires — regroupements thématiques
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Comptes de tiers clients (collectifs).
   *
   * <ul>
   *   <li>{@code 411000 Clients} — compte collectif des ventes à crédit
   *   <li>{@code 422000 Prêts et créances sur le personnel} — avances et prêts salariés
   * </ul>
   */
  public static List<AccountFixture> clientAccounts() {
    return List.of(CLIENTS, PRETS_CREANCES_PERSONNEL);
  }

  /**
   * Comptes de tiers fournisseurs (collectifs et assimilés).
   *
   * <ul>
   *   <li>{@code 401000 Fournisseurs} — compte collectif des achats à crédit
   *   <li>{@code 471000 Débits et crédits divers} — tiers occasionnels (avocats, consultants
   *       ponctuels)
   * </ul>
   */
  public static List<AccountFixture> supplierAccounts() {
    return List.of(FOURNISSEURS, DEBITS_CREDITS_DIVERS);
  }

  /**
   * Comptes de TVA / TCA — taxes sur le chiffre d'affaires haïtiennes.
   *
   * <ul>
   *   <li>{@code 443000 TVA collectée} — TVA facturée aux clients (PASSIF, CREDIT)
   *   <li>{@code 445000 TVA déductible} — TVA payée aux fournisseurs (ACTIF, DEBIT)
   *   <li>{@code 446000 TCA collectée} — Taxe sur le Chiffre d'Affaires collectée (PASSIF, CREDIT)
   *   <li>{@code 447000 TCA déductible} — TCA payée aux fournisseurs (ACTIF, DEBIT)
   * </ul>
   */
  public static List<AccountFixture> vatAccounts() {
    return List.of(TVA_COLLECTEE, TVA_DEDUCTIBLE, TCA_COLLECTEE, TCA_DEDUCTIBLE);
  }

  /**
   * Comptes de paie (charges et passifs sociaux).
   *
   * <ul>
   *   <li>{@code 421000 Personnel - rémunérations dues} — net à payer (PASSIF, CREDIT)
   *   <li>{@code 431000 Sécurité sociale} — cotisations OFATMA dues (PASSIF, CREDIT)
   *   <li>{@code 433000 Retenues fiscales} — impôt sur le salaire retenu à la source (PASSIF,
   *       CREDIT)
   *   <li>{@code 641000 Rémunérations du personnel} — brut salarial (CHARGES, DEBIT)
   *   <li>{@code 645000 Charges de sécurité sociale} — part patronale OFATMA (CHARGES, DEBIT)
   * </ul>
   */
  public static List<AccountFixture> payrollAccounts() {
    return List.of(
        PERSONNEL_REMUNERATIONS_DUES,
        SECURITE_SOCIALE,
        RETENUES_FISCALES,
        REMUNERATIONS_PERSONNEL,
        CHARGES_SECURITE_SOCIALE);
  }

  /**
   * Comptes de charges d'exploitation (hors paie — voir {@link #payrollAccounts()}).
   *
   * <p>Inclut les achats, variations de stocks, services extérieurs, impôts/taxes et charges
   * diverses de gestion courante.
   */
  public static List<AccountFixture> expenseAccounts() {
    return List.of(
        ACHATS_MARCHANDISES,
        VARIATION_STOCKS,
        LOCATIONS,
        CHARGES_LOCATIVES,
        ENTRETIEN_REPARATIONS,
        PRIMES_ASSURANCE,
        REMUNERATIONS_INTERMEDIAIRES,
        PUBLICITE,
        DEPLACEMENTS_MISSIONS,
        FRAIS_POSTAUX_TELECOM,
        SERVICES_BANCAIRES,
        IMPOTS_TAXES,
        CHARGES_DIVERS_GESTION,
        CHARGES_INTERETS,
        PERTES_EXCEPTIONNELLES);
  }

  /**
   * Comptes de produits (classe 7).
   *
   * <p>Ventes, prestations, subventions d'exploitation (pour les ONG) et produits exceptionnels.
   */
  public static List<AccountFixture> revenueAccounts() {
    return List.of(
        VENTES_MARCHANDISES,
        PRESTATIONS_SERVICES,
        VENTES_PRODUITS_FINIS,
        PRODUITS_ACCESSOIRES,
        SUBVENTIONS_EXPLOITATION,
        AUTRES_PRODUITS_GESTION,
        PRODUITS_EXCEPTIONNELS);
  }

  /**
   * Comptes de trésorerie (classe 5).
   *
   * <ul>
   *   <li>{@code 521000 Banque} — compte courant bancaire (ACTIF, DEBIT)
   *   <li>{@code 530000 Caisse} — espèces (ACTIF, DEBIT)
   * </ul>
   */
  public static List<AccountFixture> treasuryAccounts() {
    return List.of(BANQUE, CAISSE);
  }

  /**
   * Comptes d'immobilisations (classe 2).
   *
   * <p>Inclut les immobilisations corporelles, financières (titres) et les amortissements (compte
   * de solde CREDIT rattaché à l'ACTIF).
   */
  public static List<AccountFixture> fixedAssetAccounts() {
    return List.of(
        TERRAINS,
        CONSTRUCTIONS,
        INSTALLATIONS_TECHNIQUES,
        AUTRES_IMMO_CORPORELLES,
        TITRES_PARTICIPATION,
        AMORTISSEMENTS);
  }

  /** Comptes de capitaux propres (classe 1). */
  public static List<AccountFixture> equityAccounts() {
    return List.of(CAPITAL_SOCIAL, RESERVES, REPORT_A_NOUVEAU, RESULTAT_EXERCICE);
  }

  /** Comptes de stocks (classe 3). */
  public static List<AccountFixture> inventoryAccounts() {
    return List.of(STOCKS_MARCHANDISES, STOCKS_PRODUITS_FINIS);
  }

  /**
   * Comptes fiscaux (impôts différés / à payer).
   *
   * <ul>
   *   <li>{@code 440000 État - impôts à payer} — IS et autres impôts dus (PASSIF, CREDIT)
   *   <li>{@code 631000 Impôts et taxes} — impôts et taxes déductibles (CHARGES, DEBIT)
   * </ul>
   */
  public static List<AccountFixture> taxAccounts() {
    return List.of(ETAT_IMPOTS_A_PAYER, IMPOTS_TAXES);
  }
}
