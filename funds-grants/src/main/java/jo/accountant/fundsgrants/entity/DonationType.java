package jo.accountant.fundsgrants.entity;

/**
 * V8-5 — Type de donation (cash vs en nature).
 *
 * <p>Distinction comptable essentielle pour les ONG (Code Fiscal art. 197 Haïti) :
 * <ul>
 *   <li>{@link #CASH} — don en espèces ou virement. Écriture : D 521 Trésorerie / C 70x Produit de don.</li>
 *   <li>{@link #IN_KIND} — don en nature (médicaments, nourriture, équipements).
 *       Écriture : D 3x Stocks / D 215 Immobilisations (si équipement > seuil capitalisation)
 *       / C 70x Produit de don. La valorisation doit être documentée (facture du bailleur
 *       ou estimation marché).</li>
 * </ul>
 *
 * <p>Avant V8-5, tous les dons étaient comptabilisés comme CASH (D 521/C 70x) — incorrect pour
 * 30% des revenus d'une ONG humanitaire typique qui reçoit médicaments + nourriture en nature.
 */
public enum DonationType {
    /** Don en espèces ou virement bancaire. */
    CASH,
    /** Don en nature (médicaments, nourriture, équipements, vêtements). */
    IN_KIND
}
