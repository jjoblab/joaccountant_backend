package jo.accountant.demo.fixtures;

/** — Adresses haïtiennes réalistes pour les données démo. 
 *
 * @author jo@Dev


*/
public final class HaitianAddresses {

  public static final String[] PORT_AU_PRINCE = {
    "Rue Capois",
    "Rue Lamarre",
    "Avenue John Brown",
    "Rue Monseigneur Guilloux",
    "Champs de Mars",
    "Rue Pavée",
    "Avenue Martin Luther King",
    "Rue Dr. Aubry",
    "Avenue Independence",
    "Rue Magny"
  };

  public static final String[] PETION_VILLE = {
    "Rue Lamarre",
    "Avenue N",
    "Rue Charéron",
    "Rue Boyer",
    "Bourdon",
    "Turgeau",
    "Avenue John Brown Ext.",
    "Rue M. R. Laraque",
    "Delmas 33",
    "Tabarre 26"
  };

  public static final String[] CAP_HAITIEN = {
    "Boulevard du Cap", "Rue 24", "Rue A", "Rue 16",
    "Laférrière", "Charrier", "Madan Robin", "Baconnois"
  };

  public static final String[] OUANAMINTHE = {
    "CODEVI Zone Franche", "Avenue Christophe", "Rue Charlot",
    "Marchand", "Robineau", "Centre-ville Ouanaminthe"
  };

  public static final String[] DELMAS = {
    "Delmas 31", "Delmas 33", "Delmas 75", "Delmas 19",
    "Delmas 28", "Delmas 60", "Delmas 83", "Delmas 95"
  };

  private HaitianAddresses() {}

  public static String randomAddress(String segment) {
    String[] arr =
        switch (segment == null ? "" : segment) {
          case "RETAIL_COMMERCE" -> PETION_VILLE;
          case "PROFESSIONAL_SERVICES" -> PORT_AU_PRINCE;
          case "NGO_HUMANITARIAN" -> DELMAS;
          case "WHOLESALE_COMMERCE", "FREE_ZONE" -> OUANAMINTHE;
          default -> PORT_AU_PRINCE;
        };
    return arr[(int) (Math.random() * arr.length)];
  }
}
