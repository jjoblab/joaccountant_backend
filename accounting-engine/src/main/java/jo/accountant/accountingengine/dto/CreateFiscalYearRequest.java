package jo.accountant.accountingengine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Corps de requête pour {@code POST .../fiscal-years}.
 
 *
 * @author jo@Dev


*/
public record CreateFiscalYearRequest(
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    String label
) {}
