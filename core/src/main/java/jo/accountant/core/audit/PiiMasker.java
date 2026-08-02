package jo.accountant.core.audit;

/**
 * Utilitaire de masquage des PII (Personally Identifiable Information) dans les événements
 * d'audit —Finding BAS.
 *
 * <p>la version précédente stockait les PII (email, fullName) en clair dans {@code audit_log.new_value_json}.
 * En cas de fuite de la base de données, ces informations seraient exposées. Désormais, les
 * PII sont masquées avant persistance : on conserve suffisamment d'info pour la forensique
 * (2 premiers caractères + domaine pour l'email, initiales pour le fullName) sans exposer
 * l'identité complète.
 *
 * <p>Exemples :
 * <ul>
 * <li>{@code maskEmail("marie@joaccountant.dev")} → {@code "ma***@joaccountant.dev"}</li>
 * <li>{@code maskFullName("Marie Joseph")} → {@code "M. J."}</li>
 * <li>{@code maskPhone("+509 3701 2345")} → {@code "+509 ***"}</li>
 * </ul>
 *
 * <p>Note : le masquage est irréversible (one-way). Si l'audit forensique nécessite l'email
 * complet, il faut croiser avec la table {@code users} (qui n'est PAS masquée — c'est la
 * source de vérité pour l'identité).
 
 *
 * @author jo@Dev


*/
public final class PiiMasker {

    private PiiMasker() {}

    /**
     * Masque un email : garde les 2 premiers caractères + "***" + domaine.
     * {@code "marie@joaccountant.dev"} → {@code "ma***@joaccountant.dev"}
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return email;
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***";
        String prefix = email.substring(0, Math.min(2, atIndex));
        String domain = email.substring(atIndex);
        return prefix + "***" + domain;
    }

    /**
     * Masque un nom complet : garde les initiales de chaque mot + ".".
     * {@code "Marie Joseph"} → {@code "M. J."}
     * {@code "Jean-Pierre Dubois"} → {@code "J. D."}
     */
    public static String maskFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) return fullName;
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(". ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Masque un numéro de téléphone : garde l'indicatif pays + "***".
     * {@code "+509 3701 2345"} → {@code "+509 ***"}
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return phone;
        // Garder les 4 premiers caractères (indicatif) + ***
        int keep = Math.min(4, phone.length());
        return phone.substring(0, keep) + "***";
    }

    /**
     * Masque tous les champs PII connus dans un JSON string.
     *
     * <p>Remplace les valeurs des clés {@code "email"}, {@code "fullName"}, {@code "phone"}
     * par leurs versions masquées. Utilise des regex simples (suffisant pour du JSON sérialisé
     * par Jackson/Gson — pas de parsing complet pour rester léger).
     *
     * @param json le JSON potentiellement contenant des PII
     * @return le JSON avec PII masquées
     */
    public static String maskPiiInJson(String json) {
        if (json == null || json.isBlank()) return json;
        // Masquer email : "email":"value" → "email":"ma***@domain"
        json = maskJsonField(json, "email", PiiMasker::maskEmail);
        // Masquer fullName : "fullName":"value" → "fullName":"M. J."
        json = maskJsonField(json, "fullName", PiiMasker::maskFullName);
        // Masquer phone : "phone":"value" → "phone":"+50***"
        json = maskJsonField(json, "phone", PiiMasker::maskPhone);
        return json;
    }

    /**
     * Helper — remplace la valeur d'une clé JSON par sa version masquée.
     * Pattern : {@code "keyName":"originalValue"} → {@code "keyName":"maskedValue"}
     */
    private static String maskJsonField(String json, String fieldName, java.util.function.Function<String, String> masker) {
        // Regex : capture la valeur entre guillemets après "fieldName":
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String originalValue = matcher.group(1);
            String maskedValue = masker.apply(originalValue);
            // Escape special regex chars in replacement
            maskedValue = maskedValue.replace("\\", "\\\\").replace("$", "\\$");
            matcher.appendReplacement(sb, "\"" + fieldName + "\":\"" + maskedValue + "\"");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
