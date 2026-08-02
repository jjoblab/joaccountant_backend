package jo.accountant.demo.support;

import java.util.List;
import java.util.UUID;
import jo.accountant.core.exception.ConflictException;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.entity.ResetPolicy;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * V9 — Crée les 10 séquences de numérotation documentaire pour une entreprise démo.
 *
 * <p>Chaque séquence est identifiée par un triplet unique {@code (companyId, documentType,
 * scopeKey)}. Le {@code scopeKey} permet d'avoir plusieurs séquences parallèles pour le même type —
 * typiquement, une séquence par journal comptable (VT pour ventes, AC pour achats, BQ pour banque,
 * OD pour opérations diverses, PA pour paie).
 *
 * <p><b>Séquences créées</b> :
 *
 * <table>
 * <caption>Table des séquences documentaires</caption>
 * <tr><th>Prefix</th><th>DocumentType</th><th>scopeKey</th><th>ResetPolicy</th><th>Usage</th></tr>
 * <tr><td>VT</td><td>JOURNAL_ENTRY</td><td>VT</td><td>YEARLY</td><td>Écritures du journal Ventes</td></tr>
 * <tr><td>AC</td><td>JOURNAL_ENTRY</td><td>AC</td><td>YEARLY</td><td>Écritures du journal Achats</td></tr>
 * <tr><td>BQ</td><td>JOURNAL_ENTRY</td><td>BQ</td><td>YEARLY</td><td>Écritures du journal Banque</td></tr>
 * <tr><td>OD</td><td>JOURNAL_ENTRY</td><td>OD</td><td>YEARLY</td><td>Écritures du journal Opérations diverses</td></tr>
 * <tr><td>PA</td><td>JOURNAL_ENTRY</td><td>PA</td><td>YEARLY</td><td>Écritures du journal Paie</td></tr>
 * <tr><td>FAC</td><td>SALES_INVOICE</td><td>(vide)</td><td>YEARLY</td><td>Factures de vente</td></tr>
 * <tr><td>AV</td><td>CREDIT_NOTE</td><td>(vide)</td><td>YEARLY</td><td>Avoirs clients</td></tr>
 * <tr><td>FF</td><td>PURCHASE_INVOICE</td><td>(vide)</td><td>YEARLY</td><td>Factures fournisseurs</td></tr>
 * <tr><td>BS</td><td>PAYSLIP</td><td>(vide)</td><td>NEVER</td><td>Bulletins de salaire (numéros monotones pluriannuels)</td></tr>
 * <tr><td>RD</td><td>DONATION_RECEIPT</td><td>(vide)</td><td>YEARLY</td><td>Reçus de dons (ONG)</td></tr>
 * </table>
 *
 * <p><b>Choix des politiques de reset</b> :
 *
 * <ul>
 * <li>{@code YEARLY} pour les écritures et factures — conforme aux usages fiscaux haïtiens
 * (numérotation annuelle recommencée au 1er janvier).
 * <li>{@code NEVER} pour les bulletins de salaire — l'usage haïtien veut que les BS soient
 * numérotés de façon monotone pluriannuelle pour faciliter les audits de paie.
 * </ul>
 *
 * <p><b>Format de numéro</b> : {@code {PREFIX}-{YEAR}-{NNNNNN}} (6 chiffres paddés). Exemple :
 * {@code FAC-2025-000143}.
 *
 * <p><b>Idempotence</b> — si une séquence existe déjà pour le triplet {@code (documentType,
 * scopeKey)}, le service lève une {@link ConflictException} — on l'attrape et on continue. Les
 * seeders peuvent ainsi tourner plusieurs fois.
 *
 * <p><b>Thread-safety</b> — le service est stateless. Le contexte tenant doit être posé par
 * l'appelant (typiquement via {@link DemoTenantContext}).
 
 *
 * @author jo@Dev


*/
@Service
public class DocumentNumberingBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentNumberingBootstrap.class);

  /** Padding standard : 6 chiffres (suffit jusqu'à 999 999 documents/an par séquence). */
  private static final int PADDING = 6;

  /**
   * Définition d'une séquence documentaire à créer.
   *
   * @param prefix préfixe du numéro (1-20 chars alphanumériques)
   * @param documentType type de document (JOURNAL_ENTRY, SALES_INVOICE, etc.)
   * @param scopeKey clé de portée (code journal pour JOURNAL_ENTRY, "" sinon)
   * @param resetPolicy politique de reset (YEARLY ou NEVER)
   */
  private record SequenceDef(
      String prefix, DocumentType documentType, String scopeKey, ResetPolicy resetPolicy) {}

  /**
   * Liste ordonnée des 10 séquences à créer.
   *
   * <p>L'ordre est conservé pour les logs (alphabétique par documentType puis scopeKey, pour
   * faciliter la lecture des logs de seed).
   */
  private static final List<SequenceDef> SEQUENCES =
      List.of(
          // --- Journaux comptables (JOURNAL_ENTRY avec scopeKey = code journal) ---
          new SequenceDef("AC", DocumentType.JOURNAL_ENTRY, "AC", ResetPolicy.YEARLY),
          new SequenceDef("BQ", DocumentType.JOURNAL_ENTRY, "BQ", ResetPolicy.YEARLY),
          new SequenceDef("OD", DocumentType.JOURNAL_ENTRY, "OD", ResetPolicy.YEARLY),
          new SequenceDef("PA", DocumentType.JOURNAL_ENTRY, "PA", ResetPolicy.YEARLY),
          new SequenceDef("VT", DocumentType.JOURNAL_ENTRY, "VT", ResetPolicy.YEARLY),
          // --- Documents commerciaux et RH (scopeKey vide = séquence unique par type) ---
          new SequenceDef("AV", DocumentType.CREDIT_NOTE, "", ResetPolicy.YEARLY),
          new SequenceDef("BS", DocumentType.PAYSLIP, "", ResetPolicy.NEVER),
          new SequenceDef("FAC", DocumentType.SALES_INVOICE, "", ResetPolicy.YEARLY),
          new SequenceDef("FF", DocumentType.PURCHASE_INVOICE, "", ResetPolicy.YEARLY),
          new SequenceDef("RD", DocumentType.DONATION_RECEIPT, "", ResetPolicy.YEARLY));

  private final DocumentNumberingService numberingService;

  public DocumentNumberingBootstrap(DocumentNumberingService numberingService) {
    this.numberingService = numberingService;
  }

  /**
   * Crée les 10 séquences documentaires pour l'entreprise démo.
   *
   * <p>Idempotent : les séquences déjà existantes sont skippées silencieusement.
   *
   * @param companyId identifiant de l'entreprise démo
   */
  public void bootstrap(UUID companyId) {
    if (companyId == null) {
      throw new IllegalArgumentException("companyId est requis");
    }

    int created = 0;
    int skipped = 0;
    for (SequenceDef def : SEQUENCES) {
      try {
        numberingService.createSequence(
            companyId,
            def.documentType(),
            def.scopeKey(),
            def.prefix(),
            true, // includeYear → "FAC-2025-000143"
            PADDING,
            def.resetPolicy());
        created++;
        LOG.debug(
            "V9 — Séquence créée : prefix={} type={} scopeKey={} policy={} companyId={}",
            def.prefix(),
            def.documentType(),
            def.scopeKey(),
            def.resetPolicy(),
            companyId);
      } catch (ConflictException ex) {
        // Séquence déjà existante — idempotence normale
        skipped++;
        LOG.debug(
            "V9 — Séquence déjà existante : prefix={} type={} scopeKey={} — skip",
            def.prefix(),
            def.documentType(),
            def.scopeKey());
      } catch (RuntimeException ex) {
        LOG.error(
            "V9 — Échec création séquence prefix={} type={} scopeKey={} pour companyId={} : {}",
            def.prefix(),
            def.documentType(),
            def.scopeKey(),
            companyId,
            ex.getMessage(),
            ex);
      }
    }

    LOG.info(
        "V9 — Bootstrap numérotation documentaire terminé pour companyId={} : "
            + "{} séquences créées, {} skippées (déjà existantes)",
        companyId,
        created,
        skipped);
  }
}
