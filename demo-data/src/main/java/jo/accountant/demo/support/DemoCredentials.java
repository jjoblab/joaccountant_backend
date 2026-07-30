package jo.accountant.demo.support;

/**
 * V9 — Credentials partagés pour les 4 entreprises démo.
 *
 * <p>Centralise le mot de passe démo et la map demoCode → email OWNER pour éviter la duplication
 * entre les seeders et le {@code DemoLoginController}.
 *
 * <p><strong>Sécurité</strong> : le mot de passe {@code "Demo1234!2026"} satisfait la politique de
 * complexité de {@code PasswordValidator} (14 chars ≥ 12, majuscule D, minuscule emo, chiffres
 * 1234/2026, spécial !). Il ne doit être utilisé QUE pour les entreprises démo (is_demo=true) et
 * jamais pour des entreprises réelles.
 *
 * <p>⚠️ Ce mot de passe est commité dans le repo public — c'est volontaire pour la démo publique.
 * En production réelle, ce fichier doit être supprimé et les users démo créés manuellement.
 */
public final class DemoCredentials {

  /** Mot de passe partagé par les 4 users OWNER démo. Conforme à la politique (14 chars). */
  public static final String DEMO_PASSWORD = "Demo1234!2026";

  /** Locale par défaut pour les users démo. */
  public static final String DEMO_LOCALE = "fr";

  private DemoCredentials() {
    // utility class — pas d'instance
  }

  /** Mapping demoCode → email OWNER démo (cohérent avec les seeders V9). */
  public static String ownerEmail(String demoCode) {
    return switch (demoCode) {
      case "BOUTIK_LAKAY" -> "owner@boutik-lakay.demo";
      case "MOISE_ASSOCIES" -> "owner@moise-associes.demo";
      case "ESPWA_POU_AYITI" -> "owner@espwa-ayiti.demo";
      case "CARIBBEAN_TEXTILES" -> "owner@caribbean-textiles.demo";
      default -> "owner@" + demoCode.toLowerCase().replace('_', '-') + ".demo";
    };
  }

  /** Mapping demoCode → email MANAGER démo (pour les segments qui en créent un). */
  public static String managerEmail(String demoCode) {
    return switch (demoCode) {
      case "MOISE_ASSOCIES" -> "manager@moise-associes.demo";
      default -> "manager@" + demoCode.toLowerCase().replace('_', '-') + ".demo";
    };
  }

  /** Mapping demoCode → full name OWNER démo. */
  public static String ownerFullName(String demoCode) {
    return switch (demoCode) {
      case "BOUTIK_LAKAY" -> "Boutik Lakay Owner";
      case "MOISE_ASSOCIES" -> "Maître Moïse Auguste";
      case "ESPWA_POU_AYITI" -> "Espwa pou Ayiti Owner";
      case "CARIBBEAN_TEXTILES" -> "Caribbean Textiles Owner";
      default -> demoCode + " Owner";
    };
  }
}
