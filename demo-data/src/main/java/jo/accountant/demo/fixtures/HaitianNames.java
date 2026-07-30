package jo.accountant.demo.fixtures;

/** V8.1 — Fixtures de noms et prénoms haïtiens réalistes pour les données démo. */
public final class HaitianNames {

  public static final String[] FIRST_NAMES = {
    "Marie",
    "Jean",
    "Pierre",
    "Charles",
    "Joseph",
    "Moïse",
    "Saintilus",
    "Pierre-Louis",
    "Auguste",
    "Belizaire",
    "Cantilus",
    "Dorcely",
    "Roseline",
    "Nadège",
    "Frantz",
    "Carlo",
    "Emmanuel",
    "Jean-Robert",
    "Marie-Carmel",
    "Marie-Grace",
    "Wilner",
    "Evens",
    "Samuel",
    "David",
    "Daniel",
    "Patrick",
    "Ricardo",
    "Junior",
    "Christina",
    "Sandra"
  };

  public static final String[] LAST_NAMES = {
    "Joseph", "Pierre", "Charles", "Moïse", "Saintilus", "Auguste",
    "Belizaire", "Dorcely", "Pierre-Louis", "Chéry", "Filip", "Pierre-Raymond",
    "Beaulieu", "Cantave", "Casimir", "Célestin", "Chancellor", "Desravines",
    "Étienne", "Fleurant", "Gaspard", "Gervais", "Jean-Baptiste", "Joseph-Paul",
    "Lacombe", "Lubin", "Magnet", "Noël", "Phélisma", "Saint-Cyr"
  };

  private HaitianNames() {}

  public static String randomFirstName() {
    return FIRST_NAMES[(int) (Math.random() * FIRST_NAMES.length)];
  }

  public static String randomLastName() {
    return LAST_NAMES[(int) (Math.random() * LAST_NAMES.length)];
  }

  public static String randomFullName() {
    return randomFirstName() + " " + randomLastName();
  }
}
