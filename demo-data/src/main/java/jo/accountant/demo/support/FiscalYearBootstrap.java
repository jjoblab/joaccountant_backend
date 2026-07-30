package jo.accountant.demo.support;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateFiscalYearRequest;
import jo.accountant.accountingengine.entity.FiscalYear;
import jo.accountant.accountingengine.entity.Journal;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.core.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * V9 — Crée les journaux comptables et les exercices fiscaux d'une entreprise démo.
 *
 * <p>Responsabilités :
 *
 * <ol>
 *   <li><b>Création des 5 journaux comptables</b> standards (VT, AC, BQ, OD, PA) — un par type
 *       d'opération métier. Ces codes journaux DOIVENT matcher les {@code scopeKey} des séquences
 *       {@code JOURNAL_ENTRY} créées par {@link DocumentNumberingBootstrap} (sinon la numérotation
 *       automatique des écritures échouerait).
 *   <li><b>Création des 2 exercices fiscaux</b> démo : FY2024-2025 (01/10/2024 → 30/09/2025) et
 *       FY2025-2026 (01/10/2025 → 30/09/2026), conformes à l'exercice haïtien (année fiscale
 *       commençant au 1er octobre — cf. Code Fiscal haïtien).
 * </ol>
 *
 * <p><b>Exercice haïtien</b> : contrairement à la France (01/01 → 31/12) ou aux États-Unis
 * (variable), l'exercice fiscal haïtien standard court du <strong>1er octobre au 30
 * septembre</strong> de l'année suivante. Les deux exercices démo couvrent 24 mois d'activité, ce
 * qui permet de visualiser des bilans comparatifs N vs N-1 dans les états financiers.
 *
 * <p><b>Idempotence</b> :
 *
 * <ul>
 *   <li>Journaux — vérification via {@link JournalRepository#findByCompanyIdAndCode(UUID, String)}
 *       avant création. Si déjà existant, skip.
 *   <li>Exercices — vérification via {@link AccountingEngineService#listFiscalYears(UUID)} filtrée
 *       sur la plage de dates. Si un exercice chevauchant la plage existe déjà, skip.
 * </ul>
 *
 * <p><b>Auto-activation</b> : {@code AccountingEngineService.createFiscalYear} active
 * automatiquement le premier exercice créé (best-effort) — c'est ce qui permettra aux autres
 * seeders (écritures, factures, paie) d'utiliser l'exercice "actif" sans avoir à le spécifier.
 *
 * <p><b>Thread-safety</b> — le service est stateless. Le contexte tenant doit être posé par
 * l'appelant (typiquement via {@link DemoTenantContext}).
 */
@Service
public class FiscalYearBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(FiscalYearBootstrap.class);

  /**
   * Définition d'un journal comptable à créer.
   *
   * @param code code court du journal (2 lettres, ex. "VT")
   * @param label libellé long (ex. "Journal des ventes")
   */
  private record JournalDef(String code, String label) {}

  /**
   * Liste des 5 journaux standards, alignés avec les {@code scopeKey} des séquences {@code
   * JOURNAL_ENTRY} de {@link DocumentNumberingBootstrap}.
   *
   * <p><b>Ordre logique</b> (pas alphabétique) pour les logs : ventes d'abord (cycle commercial
   * normal), puis achats, banque, OD, paie.
   */
  private static final List<JournalDef> JOURNALS =
      List.of(
          new JournalDef("VT", "Journal des ventes"),
          new JournalDef("AC", "Journal des achats"),
          new JournalDef("BQ", "Journal de banque"),
          new JournalDef("OD", "Journal des opérations diverses"),
          new JournalDef("PA", "Journal de paie"));

  /**
   * Définition d'un exercice fiscal à créer.
   *
   * @param startDate date de début (inclusive)
   * @param endDate date de fin (inclusive)
   * @param label libellé (ex. "Exercice FY2024-2025")
   */
  private record FiscalYearDef(LocalDate startDate, LocalDate endDate, String label) {}

  /**
   * Les 2 exercices fiscaux démo — exercice haïtien 01/10 → 30/09.
   *
   * <p><b>Coverage</b> :
   *
   * <ul>
   *   <li>FY2024-2025 : 01/10/2024 → 30/09/2025 (12 mois, exercice N-1)
   *   <li>FY2025-2026 : 01/10/2025 → 30/09/2026 (12 mois, exercice N courant)
   * </ul>
   *
   * <p>Les seeders d'écritures génèrent des opérations réparties sur ces 24 mois pour permettre la
   * visualisation de bilans N vs N-1 dans l'application.
   */
  private static final List<FiscalYearDef> FISCAL_YEARS =
      List.of(
          new FiscalYearDef(
              LocalDate.of(2024, 10, 1), LocalDate.of(2025, 9, 30), "Exercice FY2024-2025"),
          new FiscalYearDef(
              LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30), "Exercice FY2025-2026"));

  private final AccountingEngineService accountingEngineService;
  private final JournalRepository journalRepository;

  public FiscalYearBootstrap(
      AccountingEngineService accountingEngineService, JournalRepository journalRepository) {
    this.accountingEngineService = accountingEngineService;
    this.journalRepository = journalRepository;
  }

  /**
   * Crée les 5 journaux + les 2 exercices fiscaux pour l'entreprise démo.
   *
   * <p>Idempotent : les journaux et exercices déjà existants sont skippés silencieusement.
   *
   * @param companyId identifiant de l'entreprise démo
   */
  public void bootstrap(UUID companyId) {
    if (companyId == null) {
      throw new IllegalArgumentException("companyId est requis");
    }
    bootstrapJournals(companyId);
    bootstrapFiscalYears(companyId);
  }

  /**
   * Crée les 5 journaux comptables standards (VT, AC, BQ, OD, PA).
   *
   * <p>Idempotent via {@link JournalRepository#findByCompanyIdAndCode(UUID, String)}. Un double
   * filet de sécurité attrape aussi la {@link ConflictException} au cas où une race condition
   * ferait que le journal est créé entre notre check et l'INSERT.
   */
  private void bootstrapJournals(UUID companyId) {
    int created = 0;
    int skipped = 0;
    for (JournalDef def : JOURNALS) {
      // Idempotence — check d'existence avant création
      if (journalRepository.findByCompanyIdAndCode(companyId, def.code()).isPresent()) {
        skipped++;
        LOG.debug("V9 — Journal {} déjà existant pour companyId={} — skip", def.code(), companyId);
        continue;
      }
      try {
        Journal journal = accountingEngineService.createJournal(companyId, def.code(), def.label());
        created++;
        LOG.debug(
            "V9 — Journal créé : code={} label={} id={} companyId={}",
            def.code(),
            def.label(),
            journal.getId(),
            companyId);
      } catch (ConflictException ex) {
        // Race condition : créé entre notre check et l'INSERT — idempotence gagne
        skipped++;
        LOG.debug(
            "V9 — Journal {} déjà existant (race) pour companyId={} — skip", def.code(), companyId);
      } catch (RuntimeException ex) {
        LOG.error(
            "V9 — Échec création journal {} pour companyId={} : {}",
            def.code(),
            companyId,
            ex.getMessage(),
            ex);
      }
    }
    LOG.info(
        "V9 — Bootstrap journaux terminé pour companyId={} : {} créés, {} skippés",
        companyId,
        created,
        skipped);
  }

  /**
   * Crée les 2 exercices fiscaux démo (FY2024-2025 + FY2025-2026).
   *
   * <p>Idempotence : charge la liste des exercices existants et construit un set de clés {@code
   * startDate|endDate} pour vérifier rapidement si un exercice existe déjà. On ne compare que les
   * dates (pas le label) — un utilisateur peut avoir renommé le libellé sans que cela casse
   * l'idempotence.
   */
  private void bootstrapFiscalYears(UUID companyId) {
    // Index des exercices existants par clé "startDate|endDate"
    List<FiscalYear> existingFy = accountingEngineService.listFiscalYears(companyId);
    Set<String> existingKeys = new HashSet<>(existingFy.size() * 2);
    for (FiscalYear fy : existingFy) {
      existingKeys.add(fyKey(fy.getStartDate(), fy.getEndDate()));
    }

    int created = 0;
    int skipped = 0;
    for (FiscalYearDef def : FISCAL_YEARS) {
      if (existingKeys.contains(fyKey(def.startDate(), def.endDate()))) {
        skipped++;
        LOG.debug(
            "V9 — Exercice fiscal {} → {} déjà existant pour companyId={} — skip",
            def.startDate(),
            def.endDate(),
            companyId);
        continue;
      }
      try {
        FiscalYear fy =
            accountingEngineService.createFiscalYear(
                companyId,
                new CreateFiscalYearRequest(def.startDate(), def.endDate(), def.label()));
        created++;
        LOG.debug(
            "V9 — Exercice créé : {} → {} (id={}) companyId={}",
            def.startDate(),
            def.endDate(),
            fy.getId(),
            companyId);
      } catch (ConflictException ex) {
        // Exercice déjà existant — idempotence
        skipped++;
        LOG.debug(
            "V9 — Exercice fiscal {} → {} déjà existant (race) — skip",
            def.startDate(),
            def.endDate());
      } catch (RuntimeException ex) {
        LOG.error(
            "V9 — Échec création exercice fiscal {} → {} pour companyId={} : {}",
            def.startDate(),
            def.endDate(),
            companyId,
            ex.getMessage(),
            ex);
      }
    }
    LOG.info(
        "V9 — Bootstrap exercices fiscaux terminé pour companyId={} : {} créés, {} skippés",
        companyId,
        created,
        skipped);
  }

  /** Construit la clé d'unicité d'un exercice (startDate + endDate). */
  private static String fyKey(LocalDate startDate, LocalDate endDate) {
    return startDate.toString() + "|" + endDate.toString();
  }
}
