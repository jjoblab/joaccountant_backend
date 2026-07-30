package jo.accountant.demo.fixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * V8.1 — Taux de change BRH mensuels HTG/USD 2024-2026 pour les données démo.
 *
 * <p>Valeurs indicatives basées sur les taux BRH historiques (variation 150-160 HTG/USD).
 */
public final class ExchangeRateFixtures {

  public record MonthlyRate(int year, int month, BigDecimal htgPerUsd, BigDecimal htgPerEur) {}

  private static final Map<String, MonthlyRate> RATES = new HashMap<>();

  static {
    // FY2024-2025 (oct 2024 → sept 2025)
    put(2024, 10, "152.50", "165.30");
    put(2024, 11, "153.20", "162.10");
    put(2024, 12, "154.80", "161.40");
    put(2025, 1, "155.30", "159.20");
    put(2025, 2, "156.10", "163.50");
    put(2025, 3, "157.40", "168.20");
    put(2025, 4, "158.20", "169.50");
    put(2025, 5, "159.10", "171.30");
    put(2025, 6, "160.50", "172.10");
    put(2025, 7, "159.80", "170.40");
    put(2025, 8, "158.60", "168.90");
    put(2025, 9, "157.30", "166.80");
    // FY2025-2026 (oct 2025 → sept 2026)
    put(2025, 10, "158.10", "167.50");
    put(2025, 11, "159.40", "169.20");
    put(2025, 12, "160.20", "168.10");
    put(2026, 1, "161.50", "166.30");
    put(2026, 2, "162.30", "171.40");
    put(2026, 3, "163.10", "175.20");
    put(2026, 4, "163.80", "176.50");
    put(2026, 5, "164.50", "178.30");
    put(2026, 6, "165.20", "179.10");
    put(2026, 7, "164.80", "177.40");
    put(2026, 8, "163.50", "175.90");
    put(2026, 9, "162.30", "173.80");
  }

  private static void put(int year, int month, String htgPerUsd, String htgPerEur) {
    RATES.put(
        year + "-" + month,
        new MonthlyRate(year, month, new BigDecimal(htgPerUsd), new BigDecimal(htgPerEur)));
  }

  public static MonthlyRate get(int year, int month) {
    return RATES.get(year + "-" + month);
  }

  public static MonthlyRate get(LocalDate date) {
    return get(date.getYear(), date.getMonthValue());
  }

  private ExchangeRateFixtures() {}
}
