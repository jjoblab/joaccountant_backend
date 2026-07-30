package jo.accountant.invoicing.signature;

/**
 * Type de document électronique soumis à la signature (R-36 — lot-F3-security).
 *
 * <p>Le type de document est passé à {@link ElectronicSignatureService#sign} pour :
 * <ul>
 *   <li>Choisir la signature algorithm/profile appropriée (ex: XAdES-BES pour XML,
 *       PAdES-BES pour PDF).</li>
 *   <li>Inclure le type dans les métadonnées de signature (audit trail, signature
 *       policy identifier).</li>
 *   <li>Adapter la signature policy : un {@code FINANCIAL_STATEMENT} (bilan) peut exiger
 *       une signature avancée (XAdES-T avec TSA) tandis qu'un {@code INVOICE} peut
 *       se contenter d'une signature simple (XAdES-BES).</li>
 * </ul>
 *
 * <p>Ces 4 types couvrent les documents légalement signables du Code Fiscal Haïtien :
 * <ul>
 *   <li>{@link #INVOICE} — facture de vente (Décret 2002 + arrêté DGI 4 oct 2017).</li>
 *   <li>{@link #CREDIT_NOTE} — avoir commercial (même régime que la facture).</li>
 *   <li>{@link #PAYSHP} — bulletin de paie (Décret 2002 — signature électronique
 *       reconnue équivalente à la signature manuscrite).</li>
 *   <li>{@link #FINANCIAL_STATEMENT} — état financier annuel (bilan, compte de résultat,
 *       TAFIRE SYSCOHADA révisé — signature du commissaire aux comptes).</li>
 * </ul>
 */
public enum DocumentType {
    INVOICE,
    CREDIT_NOTE,
    PAYSHP,
    FINANCIAL_STATEMENT
}
