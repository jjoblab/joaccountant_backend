package jo.accountant.accountingengine.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jo.accountant.accountingengine.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository des lignes d'écriture.
 *
 * <p>Inclut des méthodes d'agrégation pour le grand livre et la balance générale, ainsi que
 * l'implémentation de {@link AccountBalanceGuard} (calcul du solde d'un compte).
 */
@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

 /** Lignes d'une écriture, triées par numéro de ligne. */
 List<JournalLine> findByJournalEntryIdOrderByLineNumber(UUID journalEntryId);

 /** Somme des débits pour un compte (uniquement les écritures POSTED) — utilisé par
 * AccountBalanceGuard. Les écritures DRAFT/PENDING_APPROVAL ne sont pas comptabilisées. */
 @Query("select coalesce(sum(l.debit), 0) from JournalLine l " +
 "where l.companyId = :companyId and l.accountId = :accountId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 ")")
 BigDecimal sumDebitByCompanyIdAndAccountId(@Param("companyId") UUID companyId,
 @Param("accountId") UUID accountId);

 /** Somme des crédits pour un compte (uniquement les écritures POSTED). */
 @Query("select coalesce(sum(l.credit), 0) from JournalLine l " +
 "where l.companyId = :companyId and l.accountId = :accountId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 ")")
 BigDecimal sumCreditByCompanyIdAndAccountId(@Param("companyId") UUID companyId,
 @Param("accountId") UUID accountId);

 /**
 * Grand livre : toutes les lignes POSTED pour un compte et une plage de dates.
 * Joint avec JournalEntry pour filtrer sur entryDate et status.
 */
 @Query("select l from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.accountId = :accountId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED " +
 " and e.entryDate between :from and :to" +
 ") order by l.journalEntryId, l.lineNumber")
 List<JournalLine> findLedger(@Param("companyId") UUID companyId,
 @Param("accountId") UUID accountId,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to);

 /**
 * Toutes les lignes POSTED d'une entreprise (pour la balance générale).
 */
 @Query("select l from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 ") order by l.accountCode")
 List<JournalLine> findAllPosted(@Param("companyId") UUID companyId);

 /**
 * Toutes les lignes POSTED d'une entreprise, filtrées par plage de dates (Vague 2, item 2.1).
 * Utilisé par getBalanceSheet(asOf) et getIncomeStatement(from, to).
 */
 @Query("select l from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and e.entryDate <= :asOf" +
 ") order by l.accountCode")
 List<JournalLine> findAllPostedUpToDate(@Param("companyId") UUID companyId,
 @Param("asOf") LocalDate asOf);

 @Query("select l from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and e.entryDate between :from and :to" +
 ") order by l.accountCode")
 List<JournalLine> findAllPostedBetweenDates(@Param("companyId") UUID companyId,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to);

 /**
 * Lignes POSTED d'un tiers donné dans une entreprise — utilisées par le relevé de compte
 * tiers ({@code ThirdPartiesService.getStatement}).
 *
 * <p><b>Audit v4.7 §7.2 FIX N+1 CRITIQUE</b> : la v4.7 chargeait
 * {@code findAllPosted(companyId)} (TOUTES les écritures POSTED de l'entreprise, potentiellement
 * 50K lignes → ~50 MB heap) puis filtrait côté Java par {@code thirdPartyId}. Latence P99
 * estimée à 5-15s sur 10K lignes, >60s sur 50K (probable OOM).
 *
 * <p>Cette méthode fait le filtrage côté PostgreSQL avec un index composite
 * {@code (company_id, third_party_id)} (à ajouter en migration V47 ou via index dédié).
 * Latence attendue : <100ms sur 10K lignes, <500ms sur 50K. Gain : ~100× sur le relevé tiers.
 *
 * <p>Joint avec JournalEntry pour filtrer sur {@code status=POSTED} et optionnellement sur
 * une plage de dates. Si {@code from}/{@code to} sont {@code null}, pas de filtre date.
 *
 * <p>Note : l'ORDER BY se fait par {@code l.journalEntryId, l.lineNumber} car JPQL ne permet
 * pas facilement de référencer {@code e.entryDate} dans le ORDER BY d'une sous-requête. Le
 * tri chronologique final est fait côté Java dans {@code ThirdPartiesService.getStatement}
 * après chargement des JournalEntry associées.
 *
 * @param companyId identifiant de l'entreprise (sécurité multi-tenant)
 * @param thirdPartyId identifiant du tiers (filtrage indexé)
 * @param from date de début (inclusive), {@code null} = pas de filtre bas
 * @param to date de fin (inclusive), {@code null} = pas de filtre haut
 * @return les lignes POSTED triées par journalEntryId puis lineNumber
 */
 @Query("select l from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.thirdPartyId = :thirdPartyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and (:from is null or e.entryDate >= :from)" +
 " and (:to is null or e.entryDate <= :to)" +
 ") order by l.journalEntryId, l.lineNumber")
 List<JournalLine> findPostedByThirdParty(@Param("companyId") UUID companyId,
 @Param("thirdPartyId") UUID thirdPartyId,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to);

 // =========================================================================
 // (lot-C-perf-devops) — Agrégations SQL pour états financiers / balance
 // =========================================================================
 //
 // Les méthodes ci-dessous remplacent le pattern "charger toutes les lignes en
 // mémoire puis agréger en Java" qui explosait sur 100K+ lignes (latence P99
 // > 10s, heap > 100 MB). Désormais, le GROUP BY est poussé côté PostgreSQL.
 //
 // Toutes retournent une projection {@link AccountAggregate} (accountId,
 // accountCode, totalDebit, totalCredit) — l'appelant fait ensuite le calcul
 // du solde (debit - credit) et la classification par reportingClass en Java
 // (en croisant avec la liste des comptes chargée séparément).

 /**
 * Agrège les lignes POSTED d'une entreprise par compte, jusqu'à une date donnée.
 *
 * <p>Utilisé par {@code FinancialStatementsService.getBalanceSheet()} (bilan à une date).
 * Avant : chargeait toutes les lignes via {@link #findAllPostedUpToDate} puis agrégeait
 * en Java. Sur 100K lignes : ~50 MB heap + 1.5s d'itération Java. Maintenant : 1 requête
 * SQL GROUP BY qui retourne 1 ligne par compte (~100 lignes), <100 ms.
 *
 * @param companyId identifiant de l'entreprise (sécurité multi-tenant)
 * @param asOf date de clôture (inclusive) — filtre {@code e.entryDate <= asOf}
 * @return agrégats par compte (un par account_id présent dans les écritures POSTED)
 */
 @Query("select l.accountId AS accountId, max(l.accountCode) AS accountCode, " +
 "coalesce(sum(l.debit), 0) AS totalDebit, coalesce(sum(l.credit), 0) AS totalCredit " +
 "from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and e.entryDate <= :asOf" +
 ") " +
 "group by l.accountId")
 List<AccountAggregate> aggregateByAccountUpToDate(@Param("companyId") UUID companyId,
 @Param("asOf") LocalDate asOf);

 /**
 * Agrège les lignes POSTED d'une entreprise par compte, sur une plage de dates.
 *
 * <p>Utilisé par {@code FinancialStatementsService.getIncomeStatement()},
 * {@code getCashFlowStatement()}, et {@code AccountingEngineService.getTrialBalance()}.
 * Avant : chargeait toutes les lignes via {@link #findAllPostedBetweenDates} puis
 * agrégeait en Java. Maintenant : 1 requête SQL GROUP BY.
 *
 * <p><b>V8.3</b> — Correction du pattern {@code :param is null OR col >= :param} qui
 * pose problème avec Hibernate 6 + PostgreSQL (« could not determine data type of
 * parameter » → HTTP 500 sur dashboard, cf. fff.txt). On utilise
 * {@code COALESCE(:param, col) <= col} / {@code COALESCE(:param, col) >= col} qui
 * fournit à Hibernate le type du paramètre via la colonne.
 *
 * @param companyId identifiant de l'entreprise (sécurité multi-tenant)
 * @param from date de début (inclusive), null = pas de borne basse
 * @param to date de fin (inclusive), null = pas de borne haute
 * @return agrégats par compte
 */
 @Query("select l.accountId AS accountId, max(l.accountCode) AS accountCode, " +
 "coalesce(sum(l.debit), 0) AS totalDebit, coalesce(sum(l.credit), 0) AS totalCredit " +
 "from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and (COALESCE(:from, e.entryDate) <= e.entryDate)" +
 " and (COALESCE(:to, e.entryDate) >= e.entryDate)" +
 ") " +
 "group by l.accountId")
 List<AccountAggregate> aggregateByAccountBetweenDates(@Param("companyId") UUID companyId,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to);

 /**
 * V84 — v7-2 : Somme des soldes des comptes CAPITAUX_PROPRES à une date donnée.
 *
 * <p>Utilisé par {@code FinancialStatementsService.getStatementOfChangesInEquity()} pour
 * calculer les capitaux propres d'ouverture et de clôture.
 *
 * <p>Le solde d'un compte EQUITY est (credit − debit) — compte à solde créditeur normal.
 *
 * <p>JPQL ne permet pas de JOIN direct avec une entité Account non mappée comme association.
 * On utilise une sous-requête sur Account pour filtrer par reportingClass.
 */
 @org.springframework.data.jpa.repository.Query("select coalesce(sum(l.credit - l.debit), 0) from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and e.entryDate <= :asOf" +
 ") " +
 "and l.accountId in (" +
 " select a.id from Account a where a.companyId = :companyId " +
 " and a.reportingClass = jo.accountant.core.framework.ReportingClass.CAPITAUX_PROPRES" +
 ")")
 java.math.BigDecimal sumEquityUpToDate(@Param("companyId") UUID companyId,
 @Param("asOf") LocalDate asOf);

 /**
 * V84 — v7-2 : Somme des mouvements (debit - credit) pour les comptes dont le code
 * matche un pattern LIKE, sur une plage de dates.
 *
 * <p>Utilisé pour calculer les émissions de capital (D 512 / C 101 → somme crédit sur 101),
 * les rachats d'actions (D 109 / C 512 → somme débit sur 109), les dividendes
 * (D 455 / C 512 → somme débit sur 455), etc.
 *
 * <p>Pour un compte à solde créditeur (101, 455), le « montant du mouvement » est
 * credit - debit. Pour un compte à solde débiteur (109), c'est debit - credit. C'est
 * l'appelant qui interprète le signe selon le sens comptable attendu.
 *
 * <p>On retourne (credit - debit) pour rester cohérent avec la convention « solde
 * créditeur = positif » utilisée par les capitaux propres. L'appelant peut appeler
 * {@code negate()} si nécessaire.
 */
 @org.springframework.data.jpa.repository.Query("select coalesce(sum(l.credit - l.debit), 0) from JournalLine l " +
 "where l.companyId = :companyId " +
 "and l.accountCode like :accountPattern " +
 "and l.journalEntryId in (" +
 " select e.id from JournalEntry e where e.status = jo.accountant.accountingengine.entity.JournalEntryStatus.POSTED" +
 " and e.entryDate between :from and :to" +
 ")")
 java.math.BigDecimal sumByAccountCodePatternBetweenDates(
 @Param("companyId") UUID companyId,
 @Param("accountPattern") String accountPattern,
 @Param("from") LocalDate from,
 @Param("to") LocalDate to);

 /**
 * Projection pour les agrégations SQL par compte.
 *
 * <p>{@code max(l.accountCode)} est utilisé dans le GROUP BY car toutes les lignes d'un
 * même compte partagent le même accountCode (snapshot au moment de l'écriture). Le {@code max}
 * évite d'avoir à l'ajouter au GROUP BY (PostgreSQL l'exigerait sinon).
 */
 interface AccountAggregate {
 UUID getAccountId();
 String getAccountCode();
 BigDecimal getTotalDebit();
 BigDecimal getTotalCredit();
 }
}
