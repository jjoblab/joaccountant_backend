package jo.accountant.demo.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.chartofaccounts.dto.AccountResponse;
import jo.accountant.chartofaccounts.dto.CreateChildRequest;
import jo.accountant.chartofaccounts.entity.ReportingSubcategory;
import jo.accountant.chartofaccounts.service.ChartOfAccountsService;
import jo.accountant.core.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * V9 — Initialise le plan comptable d'une entreprise démo.
 *
 * <p>Responsabilités :
 *
 * <ol>
 * <li><b>Initialisation du squelette PCN_HAITI</b> — appelle {@link
 * ChartOfAccountsService#initialize(UUID, UUID,
 * jo.accountant.chartofaccounts.dto.InitializeRequest.AccountNumberingTemplateDto)} qui
 * génère les classes de niveau 1 (codes 1 à 8) ET les comptes niveau 2/3 du {@code
 * PcnHaitiAccountTemplate} (wiring V6-6 dans {@code initializeMandated}).
 * <li><b>Création des comptes feuilles</b> — pour chaque {@link AccountFixture} passé en
 * paramètre, crée le compte de niveau 2 (code à 6 chiffres) rattaché à la classe de niveau 1
 * correspondante (ex. {@code 411000 Clients} rattaché à la classe {@code 4}).
 * </ol>
 *
 * <p><b>Idempotence</b> — les seeders démo peuvent tourner plusieurs fois (démo itérative, re-seed
 * après修正). Chaque appel vérifie donc :
 *
 * <ul>
 * <li>Si {@code initialize} lance une {@link ConflictException} ({@code
 * CHART_OF_ACCOUNTS_ALREADY_INITIALIZED}), on attrape et on continue — le squelette existe
 * déjà.
 * <li>Si un compte feuille existe déjà (même code), on skip sans erreur — l'absence de {@code
 * findByCompanyIdAndCode} publique sur le service nous oblige à charger la liste complète une
 * fois et à construire un index par code (efficace : 1 SELECT au lieu de N).
 * </ul>
 *
 * <p><b>Stratégie de rattachement</b> — la consigne demande de rattacher chaque compte feuille à la
 * classe de niveau 1 correspondant au premier chiffre du code (ex. {@code 411000} → classe {@code
 * 4}). Cela crée un compte de niveau 2 avec un code à 6 chiffres — inhabituel mais valide dans le
 * moteur (qui ne vérifie pas la cohérence longueur-code / niveau, juste l'unicité du code et la
 * borne niveau ≤ 4).
 *
 * <p>Ce choix est volontaire : les comptes feuilles 6 chiffres sont des comptes opérationnels prêts
 * à recevoir des écritures, distincts des comptes de regroupement (401, 411, etc.) créés par le
 * {@code PcnHaitiAccountTemplate}. Le moteur comptable les distingue par leur code exact, pas par
 * leur niveau hiérarchique.
 *
 * <p><b>Thread-safety</b> — le service est stateless et donc thread-safe. Le contexte tenant doit
 * être posé par l'appelant (typiquement via {@link DemoTenantContext}).
 
 *
 * @author jo@Dev


*/
@Service
public class ChartOfAccountsBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(ChartOfAccountsBootstrap.class);

  private final ChartOfAccountsService coaService;

  public ChartOfAccountsBootstrap(ChartOfAccountsService coaService) {
    this.coaService = coaService;
  }

  /**
   * Initialise le plan comptable d'une entreprise démo.
   *
   * @param companyId identifiant de l'entreprise démo
   * @param frameworkId identifiant du référentiel comptable (typiquement {@code
   * 00000000-0000-0000-0000-000000000004} pour PCN_HAITI)
   * @param accounts liste des comptes feuilles à créer (typiquement {@link AccountFixture#all()})
   */
  public void bootstrap(UUID companyId, UUID frameworkId, List<AccountFixture> accounts) {
    if (companyId == null || frameworkId == null) {
      throw new IllegalArgumentException("companyId et frameworkId sont requis");
    }
    if (accounts == null || accounts.isEmpty()) {
      LOG.warn(
          "V9 — ChartOfAccountsBootstrap appelé avec liste de comptes vide pour companyId={}",
          companyId);
      return;
    }

    // --- 1. Initialisation du squelette PCN (classes niveau 1 + comptes template V6-6) ---
    // Idempotent : si déjà initialisé, le service lève ConflictException — on l'attrape.
    // fix : si initialize échoue (ex: IFRS require template), on skip aussi
    // l'(création des comptes feuilles) car les classes parent de niveau 1
    // n'existent pas → évite les warnings "Classe parente de niveau 1 introuvable".
    // Le seeder appelant a son propre fallback (ex: ensureIfrsFallbackAccounts).
    boolean initializeSucceeded = true;
    try {
      coaService.initialize(companyId, frameworkId, null);
      LOG.info(
          "V9 — Plan comptable initialisé pour companyId={} (frameworkId={})",
          companyId,
          frameworkId);
    } catch (ConflictException ex) {
      LOG.info(
          "V9 — Plan comptable déjà initialisé pour companyId={} — skip initialize ({})",
          companyId,
          ex.getMessage());
    } catch (RuntimeException ex) {
      // Autre erreur (ex: IFRS require template, framework introuvable) — on logue
      // et on skip l'Le seeder appelant gère le fallback.
      LOG.warn(
          "V9 — Erreur non-fatale lors de l'initialisation du plan comptable pour companyId={} : {} "
              + "— skip de la création des comptes feuilles (le seeder doit gérer le fallback).",
          companyId,
          ex.getMessage());
      initializeSucceeded = false;
    }

    if (!initializeSucceeded) {
      return; // — skip step 2, le seeder a son propre fallback.
    }

    // --- 2. Création des comptes feuilles (6 chiffres) ---
    // On charge une fois la liste complète pour bâtir un index code → AccountResponse.
    // Si on appelait existsByCompanyIdAndCode pour chaque compte, on ferait N SELECT
    // (N=50) — l'index fait 1 SELECT et N recherches en Map (O(1)).
    Map<String, AccountResponse> existingByCode = loadExistingAccountsByCode(companyId);

    // Cache des classes de niveau 1 par premier chiffre (évite 50× le scan de la liste)
    Map<String, AccountResponse> classRootByDigit = new HashMap<>();

    int created = 0;
    int skipped = 0;
    for (AccountFixture fixture : accounts) {
      // Idempotence : si le code existe déjà (créé par un seed précédent ou par le
      // PcnHaitiAccountTemplate V6-6), on skip sans erreur.
      if (existingByCode.containsKey(fixture.code())) {
        skipped++;
        continue;
      }

      // Trouver la classe parent de niveau 1 (premier chiffre du code)
      String firstDigit = fixture.code().substring(0, 1);
      AccountResponse parent =
          classRootByDigit.computeIfAbsent(
              firstDigit, d -> findClassRoot(companyId, d, existingByCode));
      if (parent == null) {
        LOG.warn(
            "V9 — Classe parente de niveau 1 introuvable pour le chiffre '{}' — "
                + "compte {} ({}) non créé pour companyId={}",
            firstDigit,
            fixture.code(),
            fixture.label(),
            companyId);
        continue;
      }

      // Création du compte enfant
      try {
        CreateChildRequest req =
            new CreateChildRequest(
                fixture.code(),
                fixture.label(),
                fixture.reportingClass(),
                ReportingSubcategory.N_A,
                fixture.normalBalance(),
                fixture.collective(),
                null,
                List.of());
        AccountResponse created_ = coaService.createChild(companyId, parent.id(), req);
        existingByCode.put(created_.code(), created_); // maj de l'index
        created++;
      } catch (ConflictException ex) {
        // Race condition : un autre thread a créé ce code entre notre check et l'INSERT.
        // On skip sans erreur — c'est l'idempotence qui gagne.
        LOG.debug(
            "V9 — Compte {} déjà existant (race) pour companyId={} — skip",
            fixture.code(),
            companyId);
        skipped++;
      } catch (RuntimeException ex) {
        LOG.error(
            "V9 — Échec création compte {} ({}) pour companyId={} : {}",
            fixture.code(),
            fixture.label(),
            companyId,
            ex.getMessage(),
            ex);
      }
    }

    LOG.info(
        "V9 — Bootstrap plan comptable terminé pour companyId={} : {} comptes créés, "
            + "{} skippés (déjà existants)",
        companyId,
        created,
        skipped);
  }

  /**
   * Charge la liste complète des comptes de l'entreprise et retourne un index code → réponse.
   *
   * <p>Utilisé pour vérifier l'idempotence (compte déjà créé) et pour trouver les classes de niveau
   * 1 (qui sont les parents cibles des comptes feuilles).
   */
  private Map<String, AccountResponse> loadExistingAccountsByCode(UUID companyId) {
    List<AccountResponse> all = coaService.list(companyId, null, null);
    Map<String, AccountResponse> byCode = new HashMap<>(all.size() * 2);
    for (AccountResponse a : all) {
      byCode.put(a.code(), a);
    }
    return byCode;
  }

  /**
   * Trouve la classe de niveau 1 dont le code commence par {@code firstDigit}.
   *
   * <p>Exemples :
   *
   * <ul>
   * <li>{@code firstDigit="4"} → retourne la classe "4" (Tiers), qui est un compte de niveau 1
   * créé par {@code initializeMandated} (PCN_HAITI class seed)
   * <li>{@code firstDigit="6"} → retourne la classe "6" (Charges)
   * </ul>
   *
   * @param firstDigit chiffre unique (1-9) correspondant à la classe PCN recherchée
   * @param existingByCode index des comptes existants (évite un re-scan de la liste)
   * @return la classe de niveau 1, ou {@code null} si introuvable (plan non initialisé ?)
   */
  private AccountResponse findClassRoot(
      UUID companyId, String firstDigit, Map<String, AccountResponse> existingByCode) {
    for (AccountResponse a : existingByCode.values()) {
      if (a.level() == 1 && a.code() != null && a.code().startsWith(firstDigit)) {
        return a;
      }
    }
    // Fallback : recharger la liste au cas où l'index soit périmé
    // (en pratique non, car on vient de le construire — mais défensive)
    List<AccountResponse> all = coaService.list(companyId, null, null);
    for (AccountResponse a : all) {
      if (a.level() == 1 && a.code() != null && a.code().startsWith(firstDigit)) {
        return a;
      }
    }
    return null;
  }
}
