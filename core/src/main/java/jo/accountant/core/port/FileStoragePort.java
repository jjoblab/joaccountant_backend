package jo.accountant.core.port;

/**
 * Abstraction du stockage de fichiers (§3.11). Aucune règle métier. Définie dans :core pour que
 * n'importe quel module puisse en dépendre sans dépendance circulaire. Implémentation par défaut
 * filesystem pour dev ; S3/MinIO pour la prod (décision différée).
 *
 * <p>§3.11 : tout fichier stocké est référencé par une clé opaque {@code storageKey} — JAMAIS par
 * une URL publique. L'accès est toujours médié par un endpoint applicatif qui vérifie
 * l'appartenance tenant avant de streamer les octets.
 */
public interface FileStoragePort {

    /**
     * Stocke des octets bruts sous une nouvelle clé opaque, renvoie cette clé.
     * L'appelant est responsable de persister la clé dans une ligne métier.
     */
    String store(byte[] content, String contentType, String suggestedExtension);

    /** Stream les octets en retour. L'appelant DOIT vérifier l'appartenance tenant de la ligne référençante. */
    byte[] load(String storageKey);

    /** Supprime le blob sous-jacent. No-op si la clé n'existe pas. */
    void delete(String storageKey);
}
