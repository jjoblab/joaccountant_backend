package jo.accountant.demo.fixtures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalogue de produits retail haïtiens (alimentation, ménagers, cosmétiques). Utilisé par
 * le seeder PME1 Boutik Lakay.
 
 *
 * @author jo@Dev


*/
public final class HaitianProducts {

 public record Product(
 String code,
 String label,
 BigDecimal purchasePriceHtg,
 BigDecimal salePriceHtg,
 String category) {}

 public static List<Product> retailCatalog() {
 List<Product> products = new ArrayList<>();
 // Alimentation (30 produits)
 products.add(
 new Product(
 "RIZ-01",
 "Riz Tcha-Tcha 25kg",
 new BigDecimal("3500"),
 new BigDecimal("4500"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "RIZ-02",
 "Riz Crystal 25kg",
 new BigDecimal("4200"),
 new BigDecimal("5500"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "HARI-01",
 "Haricots rouges 5kg",
 new BigDecimal("1500"),
 new BigDecimal("2100"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "HARI-02",
 "Haricots noirs 5kg",
 new BigDecimal("1400"),
 new BigDecimal("1950"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "HUIL-01",
 "Huile Margo 5L",
 new BigDecimal("1200"),
 new BigDecimal("1700"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "HUIL-02",
 "Huile Vital 5L",
 new BigDecimal("1100"),
 new BigDecimal("1550"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "FA",
 "Farine La Belle 5kg",
 new BigDecimal("800"),
 new BigDecimal("1200"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "SUC-01",
 "Sucre Enraud 5kg",
 new BigDecimal("900"),
 new BigDecimal("1300"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "LAIT-01",
 "Lait en poudre Klim 2.5kg",
 new BigDecimal("2200"),
 new BigDecimal("3000"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "CON-01",
 "Conserve tomate Solin 12x400g",
 new BigDecimal("1500"),
 new BigDecimal("2100"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "CON-02",
 "Conserve maïs BonGou 12x400g",
 new BigDecimal("1300"),
 new BigDecimal("1800"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "CON-03",
 "Sardines Casamar 24x125g",
 new BigDecimal("1800"),
 new BigDecimal("2500"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "PATES-01",
 "Spaghetti Délices 20x400g",
 new BigDecimal("1200"),
 new BigDecimal("1700"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "SAUCE-01",
 "Sauce tomate Solin 12x250g",
 new BigDecimal("900"),
 new BigDecimal("1300"),
 "ALIMENTATION"));
 products.add(
 new Product(
 "MAIS-01",
 "Farine de maïs 2kg",
 new BigDecimal("400"),
 new BigDecimal("600"),
 "ALIMENTATION"));
 // Produits ménagers (10 produits)
 products.add(
 new Product(
 "SAV-01",
 "Savon Marseille 12x200g",
 new BigDecimal("600"),
 new BigDecimal("900"),
 "MENAGERS"));
 products.add(
 new Product(
 "SAV-02",
 "Savon-doux-pap 24x100g",
 new BigDecimal("900"),
 new BigDecimal("1300"),
 "MENAGERS"));
 products.add(
 new Product(
 "DET-01",
 "Détergent Boudoo 5kg",
 new BigDecimal("1500"),
 new BigDecimal("2100"),
 "MENAGERS"));
 products.add(
 new Product(
 "JAV-01",
 "Eau de Javel Solin 4L",
 new BigDecimal("500"),
 new BigDecimal("750"),
 "MENAGERS"));
 products.add(
 new Product(
 "BAL-01", "Balai en paille", new BigDecimal("350"), new BigDecimal("550"), "MENAGERS"));
 products.add(
 new Product(
 "SE",
 "Serpillière 100x40cm",
 new BigDecimal("400"),
 new BigDecimal("650"),
 "MENAGERS"));
 products.add(
 new Product(
 "PLO-01",
 "Pommeau-brosse avec manche",
 new BigDecimal("800"),
 new BigDecimal("1200"),
 "MENAGERS"));
 products.add(
 new Product(
 "ESS-01",
 "Essuie-tout 6 rouleaux",
 new BigDecimal("700"),
 new BigDecimal("1050"),
 "MENAGERS"));
 // Cosmétiques (10 produits)
 products.add(
 new Product(
 "CREM-01",
 "Crème hydratante Vaseline 250ml",
 new BigDecimal("800"),
 new BigDecimal("1200"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "SAV-BEAUTE-01",
 "Savon de beauté Dove 12x100g",
 new BigDecimal("1500"),
 new BigDecimal("2200"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "PARF-01",
 "Eau de parfum Paris Paris 50ml",
 new BigDecimal("1800"),
 new BigDecimal("2800"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "SHAM-01",
 "Shampooing Pantene 400ml",
 new BigDecimal("900"),
 new BigDecimal("1350"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "COND-01",
 "Après-shampooing Pantene 400ml",
 new BigDecimal("900"),
 new BigDecimal("1350"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "DEN-01",
 "Dentifrice Colgate 100ml",
 new BigDecimal("300"),
 new BigDecimal("450"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "DEO-01",
 "Déodorant Rexona 50ml",
 new BigDecimal("500"),
 new BigDecimal("750"),
 "COSMETIQUES"));
 products.add(
 new Product(
 "CREM-02",
 "Crème éclairante Clear Essence 200ml",
 new BigDecimal("1500"),
 new BigDecimal("2200"),
 "COSMETIQUES"));
 return products;
 }

 private HaitianProducts() {}
}
