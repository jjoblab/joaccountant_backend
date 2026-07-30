package jo.accountant.fxoperations.entity;

/**
 * Statut d'une opération de change.
 *
 * <p>{@link #POSTED} — l'opération a généré son écriture comptable, est définitive.
 * <p>{@link #REVERSED} — l'opération a été contre-passée (une écriture inversée a été générée).
 */
public enum FxOperationStatus {
    POSTED,
    REVERSED
}
