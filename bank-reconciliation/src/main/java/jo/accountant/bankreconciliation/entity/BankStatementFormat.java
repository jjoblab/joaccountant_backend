package jo.accountant.bankreconciliation.entity;

/**
 * Format de relevé bancaire importé (§13 Phase 13).
 *
 * <p>Parseurs complets pour les deux formats dès cette phase — pas de "on ajoutera d'autres
 * formats plus tard" en commentaire. Si un format n'est pas dans le périmètre, il n'apparaît
 * nulle part dans le code.
 */
public enum BankStatementFormat {
    CSV,
    OFX
}
