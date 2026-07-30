package jo.accountant.demo.seeders;

/**
 * V8.1 — Interface commune aux 4 seeders d'entreprises démo.
 *
 * <p>Chaque implémentation crée une entreprise fictive haïtienne + ses données métier (employés,
 * clients, fournisseurs, produits, écritures, factures, paie) sur 2 exercices fiscaux (FY2024-2025
 * + FY2025-2026).
 */
public interface CompanySeeder {

  /** Code court identifiant la démo (ex : "BOUTIK_LAKAY"). */
  String demoCode();

  /**
   * Seed les données de l'entreprise démo. Idempotent : si déjà seedée (vérification via
   * demo_seed_history), retourne 0 sans rien faire.
   *
   * @return nombre d'enregistrements créés
   */
  int seed();

  /** Nom long de l'entreprise (ex : "Boutik Lakay S.A."). */
  String companyName();

  /**
   * Segment métier (RETAIL_COMMERCE, PROFESSIONAL_SERVICES, NGO_HUMANITARIAN, WHOLESALE_COMMERCE).
   */
  String segment();
}
