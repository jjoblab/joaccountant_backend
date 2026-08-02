package jo.accountant.demo.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** — Résumé d'une entreprise démo pour l'endpoint GET /api/v1/demos. 
 *
 * @author jo@Dev


*/
public record DemoCompanySummary(
    String demoCode,
    String name,
    String segment,
    String location,
    int employees,
    BigDecimal annualRevenue,
    String currency,
    List<String> fiscalYears,
    List<String> modules,
    List<String> highlights,
    UUID companyId) {}
