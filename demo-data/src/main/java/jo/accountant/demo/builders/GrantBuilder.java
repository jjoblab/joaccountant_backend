package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.fundsgrants.entity.Grant;
import jo.accountant.fundsgrants.entity.RestrictionType;

/**
 * Builder fluent pour créer des subventions démo (ONG Espwa pou Ayiti).
 
 *
 * @author jo@Dev


*/
public class GrantBuilder {

    private final Grant grant = new Grant();

    public GrantBuilder() {
        grant.setCurrency("USD");
        grant.setRestrictionType(RestrictionType.RESTRICTED);
        grant.setStartDate(LocalDate.of(2024, 10, 1));
        grant.setEndDate(LocalDate.of(2026, 9, 30));
    }

    public GrantBuilder donorThirdPartyId(UUID id) { grant.setDonorThirdPartyId(id); return this; }
    public GrantBuilder code(String c) { grant.setCode(c); return this; }
    public GrantBuilder label(String l) { grant.setLabel(l); return this; }
    public GrantBuilder totalAmount(BigDecimal a) { grant.setTotalAmount(a); return this; }
    public GrantBuilder currency(String c) { grant.setCurrency(c); return this; }
    public GrantBuilder startDate(LocalDate d) { grant.setStartDate(d); return this; }
    public GrantBuilder endDate(LocalDate d) { grant.setEndDate(d); return this; }
    public GrantBuilder restrictionType(RestrictionType r) { grant.setRestrictionType(r); return this; }
    public GrantBuilder analyticalValueId(UUID id) { grant.setAnalyticalValueId(id); return this; }

    public Grant build() {
        if (grant.getDonorThirdPartyId() == null) throw new IllegalStateException("donorThirdPartyId required");
        if (grant.getCode() == null) throw new IllegalStateException("code required");
        if (grant.getLabel() == null) throw new IllegalStateException("label required");
        if (grant.getTotalAmount() == null) throw new IllegalStateException("totalAmount required");
        return grant;
    }
}
