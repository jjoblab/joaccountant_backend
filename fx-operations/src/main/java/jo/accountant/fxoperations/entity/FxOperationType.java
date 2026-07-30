package jo.accountant.fxoperations.entity;

/**
 * Type d'opération de change (restructuration 2026-07-24 suite 3 — module :fx-operations).
 *
 * <p>{@link #BUY} — achat de devise étrangère contre devise fonctionnelle
 *       (ex. acheter USD avec HTG : D 521-USD / C 521-HTG).
 * <p>{@link #SELL} — vente de devise étrangère contre devise fonctionnelle
 *       (ex. vendre USD contre HTG : D 521-HTG / C 521-USD).
 * <p>{@link #REVALUATION} — réévaluation de fin de période des soldes en devises étrangères
 *       au taux de clôture. Génère un gain ou une perte de change latent.
 */
public enum FxOperationType {
    BUY,
    SELL,
    REVALUATION
}
