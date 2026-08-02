package jo.accountant.core.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Utilitaire JSON centralisé pour le module {@code :core} — évite de répéter la
 * configuration Jackson (ObjectMapper) dans plusieurs services.
 *
 * <p>Utilise un {@link ObjectMapper} partagé thread-safe (Jackson garantit la
 * thread-safety après configuration). L'instance est créée à l'initialisation de la classe
 * — pas de lazy initialization nécessaire.
 *
 * <p>En cas d'erreur de sérialisation, la méthode {@link #toJson(Object)} retourne
 * {@code null} plutôt que de propager une exception vérifiée — c'est volontaire : les
 * métadonnées d'audit ne doivent JAMAIS faire échouer l'opération métier.
 
 *
 * @author jo@Dev


*/
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    /**
     * Sérialise un objet en JSON. Retourne {@code null} en cas d'erreur (audit safe).
     */
    public static String toJson(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Audit-safe : ne jamais propager l'erreur. Logger via SLF4J si possible.
            return null;
        }
    }

    /**
     * Sérialise une Map en JSON — variante type-safe pour les métadonnées d'audit.
     */
    public static String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        return toJson((Object) map);
    }
}
