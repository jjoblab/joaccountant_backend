package jo.accountant.notifications.listener;

import jo.accountant.accountingengine.event.JournalEntryPostedEvent;
import jo.accountant.accountingengine.event.JournalEntryReversedEvent;
import jo.accountant.bankreconciliation.event.BankStatementImportedEvent;
import jo.accountant.chartofaccounts.event.AccountUpdatedEvent;
import jo.accountant.chartofaccounts.event.ChartOfAccountsInitializedEvent;
import jo.accountant.fixedassets.event.AssetCreatedEvent;
import jo.accountant.fixedassets.event.AssetDisposedEvent;
import jo.accountant.fixedassets.event.DepreciationPostedEvent;
import jo.accountant.invoicing.event.InvoiceIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listener de domaine du module {@code :notifications} — point d'entrée unique pour la
 * <b>future distribution de notifications métier</b> sur les événements à plus fort
 * impact opérationnel.
 *
 * <p><b>Events de domaine</b> (audit batch 2) : 26/30 events publiés dans le
 * codebase n'avaient aucun abonné dédié. Ce listener câblait 4 {@code @EventListener} sur les
 * événements de domaine les plus critiques.
 *
 * <p><b>(audit batch C) — 5 events supplémentaires câblés</b> : ce listener étend
 * la couverture forensique à 9 événements de domaine au total. Les 5 nouveaux ont été choisis
 * pour leur impact comptable/réglementaire (contre-passation, immobilisations, plan comptable) :
 * <ul>
 * <li>{@link InvoiceIssuedEvent} — émission d'une facture client (déjà câblé en batch 2).</li>
 * <li>{@link JournalEntryPostedEvent} — postage d'une écriture comptable (déjà câblé).</li>
 * <li>{@link BankStatementImportedEvent} — import d'un relevé bancaire (déjà câblé).</li>
 * <li>{@link AssetDisposedEvent} — cession d'immobilisation (déjà câblé).</li>
 * <li><b>NEW</b> {@link JournalEntryReversedEvent} — contre-passation d'une écriture (acte
 * d'audit sensible : toute contre-passation doit être tracée pour investigation
 * post-incident, potentiel signalement fraude si pattern anormal).</li>
 * <li><b>NEW</b> {@link AssetCreatedEvent} — création d'immobilisation (impact : bilan,
 * plan d'amortissement, IS futur).</li>
 * <li><b>NEW</b> {@link DepreciationPostedEvent} — postage d'une ligne d'amortissement
 * (impact : charge d'exploitation, résultat net).</li>
 * <li><b>NEW</b> {@link ChartOfAccountsInitializedEvent} — initialisation du plan comptable
 * d'une entreprise (réinitialisation suspecte en production → trail forensique obligatoire).</li>
 * <li><b>NEW</b> {@link AccountUpdatedEvent} — modification d'un compte (renommage,
 * désactivation, mapping fiscal) — impacte les états financiers futurs.</li>
 * </ul>
 *
 * <p><b>Comportement actuel (forensique no-op)</b> : chaque listener ne fait qu'écrire une ligne
 * de log INFO avec les champs clés. <b>Aucune notification réelle n'est envoyée</b> —
 * l'envoi effectif (NotificationChannelPort, templates, règles de routing par
 * préférence/utilisateur) sera branché ultérieurement. L'objectif immédiat est :
 * <ol>
 * <li>Fournir un trail d'audit lisible dans les logs serveur pour investigation
 * post-incident.</li>
 * <li>Marquer la frontière de couplage : tout futur consommateur de notifications passe par
 * ici, pas par un listener ad-hoc dans un module métier.</li>
 * </ol>
 *
 * <p><b>Dépendances</b> : le module {@code :notifications} dépend déjà (build.gradle.kts) des
 * modules {@code :invoicing}, {@code :accounting-engine}, {@code :bank-reconciliation},
 * {@code :fixed-assets} et désormais {@code :chart-of-accounts} afin de pouvoir
 * référencer les types concrets des événements écoutés (aucun cycle — {@code :notifications}
 * est un consommateur pur, jamais importé par ces modules).
 *
 * <p><b>Async</b> : l'écoute est asynchrone pour ne pas bloquer la transaction métier. Si le
 * listener échoue (ex: logger indisponible), l'erreur est avalée silencieusement — les
 * notifications (forensiques comme futures) ne doivent JAMAIS casser le flux métier.
 *
 * <p><b>Note d'architecture</b> : un listener forensique similaire
 * ({@code jo.accountant.notifications.service.ForensicEventListener}) existe déjà dans le
 * sous-paquetage {@code service}. Ce présent listener, dans le sous-paquetage {@code listener},
 * est l'<b>emblème du package</b> : tout ajout d'abonné notification doit être placé ici.
 * La séparation {@code service} (forensique pur) vs {@code listener} (notifications utilisateur)
 * évite que la logique d'envoi n'interfère avec le trail d'audit.
 
 *
 * @author jo@Dev


*/
@Component
public class DomainEventListener {

 private static final Logger LOG = LoggerFactory.getLogger(DomainEventListener.class);

 /**
 * Émission d'une facture client — événement à fort impact financier (CA, TVA, recouvrement).
 *
 * <p><b>À venir</b> : déclenchera une notification in-app + e-mail au dirigeant /
 * expert-comptable + alimentation des KPI temps-réel. Pour l'heure, log INFO forensique.
 */
 @Async("audit-async-executor")
 @EventListener
 public void onInvoiceIssued(InvoiceIssuedEvent event) {
 try {
 // TODO: NotificationDispatcher.dispatch(
 // event.companyId(), NotificationTemplate.INVOICE_ISSUED,
 // Map.of("invoiceNumber", event.invoiceNumber(),
 // "totalAmount", event.totalAmount(),
 // "actorUserId", event.actorUserId()));
 LOG.info(
 "[DOMAIN-EVENT] InvoiceIssued — companyId={} invoiceId={} number={} total={} actorUserId={} "
 + "(future notification v4.9: alert dirigeant + KPI CA temps-réel)",
 event.companyId(), event.invoiceId(), event.invoiceNumber(),
 event.totalAmount(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort — les notifications ne doivent jamais casser le flux métier.
 }
 }

 /**
 * Postage effectif d'une écriture comptable — impacte tous les états financiers et l'équilibre
 * du bilan.
 *
 * <p><b>À venir</b> : déclenchera une notification au manager si montant &gt; seuil
 * configurable, et invalidation du cache des états financiers.
 */
 @Async("audit-async-executor")
 @EventListener
 public void onJournalEntryPosted(JournalEntryPostedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] JournalEntryPosted — companyId={} entryId={} reference={} amount={} actorUserId={}",
 event.companyId(), event.entryId(), event.reference(),
 event.amount(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Import d'un relevé bancaire — déclenche le rapprochement automatique et la détection d'anomalies.
 *
 * <p><b>À venir</b> : déclenchera une notification « N écritures rapprochées /
 * N anomalies détectées » au comptable en charge du rapprochement.
 */
 @Async("audit-async-executor")
 @EventListener
 public void onBankStatementImported(BankStatementImportedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] BankStatementImported — companyId={} importId={} bankAccountId={} lineCount={} actorUserId={}",
 event.companyId(), event.importId(), event.bankAccountId(),
 event.lineCount(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Cession d'immobilisation — impacte la plus/moins-value, l'IS à payer et le registre des
 * immobilisations.
 *
 * <p><b>À venir</b> : déclenchera une notification au responsable comptable +
 * intégration dans la déclaration IS prévisionnelle.
 */
 @Async("audit-async-executor")
 @EventListener
 public void onAssetDisposed(AssetDisposedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] AssetDisposed — companyId={} assetId={} disposalAmount={} gainOrLoss={} actorUserId={}",
 event.companyId(), event.assetId(), event.disposalAmount(),
 event.gainOrLoss(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 // =========================================================================
 // (audit batch C) — 5 events forensiques supplémentaires
 // =========================================================================

 /**
 * Contre-passation d'une écriture comptable — acte d'audit sensible.
 *
 * <p>Toute contre-passation doit être tracée pour investigation post-incident : pattern
 * anormal (ex: 10 contre-passations en 1h sur le même compte) peut indiquer une fraude
 * ou une erreur de saisie récurrente. Le log forensique est la source pour le futur
 * détecteur d'anomalies (+ machine learning).
 *
 * <p><b>À venir</b> : déclenchera une notification au contrôleur interne si la
 * contre-passation porte sur une écriture de l'exercice précédent (N-1 verrouillé).
 */
 @Async("audit-async-executor")
 @EventListener
 public void onJournalEntryReversed(JournalEntryReversedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] JournalEntryReversed — companyId={} originalEntryId={} reversalEntryId={} actorUserId={}"
 + " (forensique : contre-passation = acte d'audit, tracer pour détection pattern anormal)",
 event.companyId(), event.originalEntryId(), event.reversalEntryId(),
 event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Création d'immobilisation — impacte le bilan, le plan d'amortissement et l'IS futur.
 *
 * <p><b>À venir</b> : déclenchera une notification au responsable comptable pour
 * validation manuelle des données d'acquisition (coût, durée, méthode).
 */
 @Async("audit-async-executor")
 @EventListener
 public void onAssetCreated(AssetCreatedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] AssetCreated — companyId={} assetId={} label={} acquisitionCost={} actorUserId={}",
 event.companyId(), event.assetId(), event.label(),
 event.acquisitionCost(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Postage d'une ligne d'amortissement — impacte la charge d'exploitation et le résultat net.
 *
 * <p><b>À venir</b> : déclenchera un agrégat mensuel « N amortissements postés pour
 * un total de X » au responsable comptable.
 */
 @Async("audit-async-executor")
 @EventListener
 public void onDepreciationPosted(DepreciationPostedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] DepreciationPosted — companyId={} assetId={} scheduleLineId={} journalEntryId={} amount={} actorUserId={}",
 event.companyId(), event.assetId(), event.scheduleLineId(),
 event.journalEntryId(), event.amount(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Initialisation du plan comptable d'une entreprise — évènement rare et normalement unique
 * par entreprise. Une réinitialisation en production est suspecte et doit être tracée.
 *
 * <p><b>À venir</b> : déclenchera une alerte sécurité si une réinitialisation est
 * détectée (la première initialisation est attendue à l'onboarding, toute autre est
 * potentiellement malveillante).
 */
 @Async("audit-async-executor")
 @EventListener
 public void onChartOfAccountsInitialized(ChartOfAccountsInitializedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] ChartOfAccountsInitialized — companyId={} accountingFrameworkId={} accountsCreated={} actorUserId={}"
 + " (forensique : réinitialisation suspecte en production, tracer pour audit sécurité)",
 event.companyId(), event.accountingFrameworkId(),
 event.accountsCreated(), event.actorUserId());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 /**
 * Modification d'un compte du plan comptable — impacte les états financiers futurs.
 *
 * <p>Stocke l'ancienne et la nouvelle valeur au format JSON (présent dans le payload de
 * l'event) pour audit complet. La modification d'un compte verrouillé est impossible
 * (409 côté service) — cet event n'est donc publié QUE pour des comptes non verrouillés.
 *
 * <p><b>À venir</b> : déclenchera une notification au contrôleur interne si la
 * modification porte sur le mapping fiscal d'un compte (impact déclarations TVA/IS).
 */
 @Async("audit-async-executor")
 @EventListener
 public void onAccountUpdated(AccountUpdatedEvent event) {
 try {
 LOG.info(
 "[DOMAIN-EVENT] AccountUpdated — companyId={} accountId={} actorUserId={} oldValue={} newValue={}",
 event.companyId(), event.accountId(), event.actorUserId(),
 event.oldValueJson(), event.newValueJson());
 } catch (Exception ex) {
 // Best-effort
 }
 }

 // =========================================================================
 // Events NON câblés (YAGNI — seront câblés quand le besoin métier
 // arrivera). La trace reste dans l'audit-trail via AuditEventListener (interface
 // AuditableAction). 17 events identifiés à ce jour :
 // =========================================================================
 //
 // 1. AccountCreatedEvent — :chart-of-accounts
 // 2. ApprovalRuleCreatedEvent — :approval-workflow
 // 3. ApprovalRequestedEvent — :approval-workflow
 // 4. ApprovalDecidedEvent — :approval-workflow
 // (déjà consommé par AccountingEngineService pour transition PENDING_APPROVAL → POSTED,
 // mais pas pour notifications utilisateur — Non implémenté : alerter demandeur/décideur)
 // 5. CompanyCreatedEvent — :company
 // 6. CompanyWizardCompletedEvent — :company
 // 7. DocumentGeneratedEvent — :document-generation
 // 8. FinancialStatementSnapshotCreatedEvent — :financial-statements
 // 9. GrantCreatedEvent — :funds-grants
 // 10. LowStockEvent — :inventory
 // 11. NumberIssuedEvent — :document-numbering
 // 12. ProjectCreatedEvent — :time-billing
 // 13. SequenceConfigCreatedEvent — :document-numbering
 // 14. StockMoveCreatedEvent — :inventory
 // 15. ThirdPartyCreatedEvent — :third-parties
 // 16. TimesheetEntryApprovedEvent — :time-billing
 // 17. UserRegisteredEvent — :auth
 //
 // Rationale YAGNI : aucun de ces events n'a aujourd'hui de consommateur notification
 // explicitement requis par le métier. Le coût de câbler 17 listeners "au cas où" est
 // (a) une dépendance compile-time de :notifications vers 9 modules supplémentaires,
 // (b) du bruit dans les logs serveur, (c) un risque de couplage inutile. On attend que
 // le métier matérialise un besoin concret (ex: "alerte stock bas" pour LowStockEvent)
 // avant d'ajouter le listener dédié — c'est l'approche YAGNI recommandée par l'audit.
 // =========================================================================
}
