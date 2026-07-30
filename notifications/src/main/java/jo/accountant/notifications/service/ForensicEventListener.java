package jo.accountant.notifications.service;

import jo.accountant.accountingengine.event.JournalEntryPostedEvent;
import jo.accountant.bankreconciliation.event.BankStatementImportedEvent;
import jo.accountant.fixedassets.event.AssetDisposedEvent;
import jo.accountant.invoicing.event.InvoiceIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listener forensique no-op des 4 événements de domaine les plus importants (Finding #1 —
 * audit batch 1).
 *
 * <p><b>Contexte</b> : 26/30 events publiés dans le codebase n'avaient aucun abonné. Câbler des
 * listeners factices pour TOUS serait du YAGNI. La décision d'audit est de :
 * <ol>
 *   <li>Documenter dans chaque event file qu'il est « prêt pour consommation future ».</li>
 *   <li>Câbler un listener no-op UNIQUEMENT pour les 4 événements à plus fort intérêt forensique
 *       (impact financier, conformité, audit) :</li>
 *   <li>Les autres 22+ événements restent publiés mais sans abonné — la trace persiste dans
 *       l'audit-trail (via {@link jo.accountant.audit.AuditEventListener}) indépendamment.</li>
 * </ol>
 *
 * <p><b>Les 4 événements forensiques</b> :
 * <ul>
 *   <li>{@link InvoiceIssuedEvent} — émission d'une facture client (impact : chiffre d'affaires,
 *       TVA collectée, recouvrement).</li>
 *   <li>{@link JournalEntryPostedEvent} — postage effectif d'une écriture comptable (impact :
 *       tous les états financiers, équilibre bilan).</li>
 *   <li>{@link BankStatementImportedEvent} — import d'un relevé bancaire (impact : rapprochement,
 *       détection fraude, trésorerie).</li>
 *   <li>{@link AssetDisposedEvent} — cession d'immobilisation (impact : plus/moins-value, IS,
 *       immobilisations).</li>
 * </ul>
 *
 * <p><b>Comportement</b> : le listener ne fait qu'écrire une ligne de log INFO avec les
 * champs clés. Il ne persiste RIEN en base (les notifications in-app restent déclenchées par
 * les règles métier explicites — pas par ces events forensiques). L'objectif est de fournir un
 * trail d'audit lisible dans les logs serveur pour investigation post-incident.
 *
 * <p><b>Async</b> : l'écoute est asynchrone pour ne pas bloquer la transaction métier. Si le
 * listener échoue (ex: logger indisponible), l'erreur est avalée silencieusement — forensique
 * ne doit JAMAIS casser le flux métier.
 */
@Component
public class ForensicEventListener {

    private static final Logger FORENSIC_LOG = LoggerFactory.getLogger("FORENSIC_EVENTS");

    @Async("audit-async-executor")
    @EventListener
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        try {
            FORENSIC_LOG.info(
                "[FORENSIC] InvoiceIssued — companyId={} invoiceId={} number={} total={} actorUserId={}",
                event.companyId(), event.invoiceId(), event.invoiceNumber(),
                event.totalAmount(), event.actorUserId());
        } catch (Exception ex) {
            // Best-effort — le forensique ne doit jamais casser le flux métier.
        }
    }

    @Async("audit-async-executor")
    @EventListener
    public void onJournalEntryPosted(JournalEntryPostedEvent event) {
        try {
            FORENSIC_LOG.info(
                "[FORENSIC] JournalEntryPosted — companyId={} entryId={} reference={} amount={} actorUserId={}",
                event.companyId(), event.entryId(), event.reference(),
                event.amount(), event.actorUserId());
        } catch (Exception ex) {
            // Best-effort
        }
    }

    @Async("audit-async-executor")
    @EventListener
    public void onBankStatementImported(BankStatementImportedEvent event) {
        try {
            FORENSIC_LOG.info(
                "[FORENSIC] BankStatementImported — companyId={} importId={} bankAccountId={} lineCount={} actorUserId={}",
                event.companyId(), event.importId(), event.bankAccountId(),
                event.lineCount(), event.actorUserId());
        } catch (Exception ex) {
            // Best-effort
        }
    }

    @Async("audit-async-executor")
    @EventListener
    public void onAssetDisposed(AssetDisposedEvent event) {
        try {
            FORENSIC_LOG.info(
                "[FORENSIC] AssetDisposed — companyId={} assetId={} disposalAmount={} gainOrLoss={} actorUserId={}",
                event.companyId(), event.assetId(), event.disposalAmount(),
                event.gainOrLoss(), event.actorUserId());
        } catch (Exception ex) {
            // Best-effort
        }
    }
}
