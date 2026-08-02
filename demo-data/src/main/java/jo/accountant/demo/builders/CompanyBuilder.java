package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.entity.LegalForm;
import jo.accountant.company.entity.OrganizationNature;
import jo.accountant.company.entity.Sector;
import jo.accountant.company.entity.TaxExemptionStatus;

/**
 * Builder fluent pour créer des entreprises démo.
 
 *
 * @author jo@Dev


*/
public class CompanyBuilder {

    private final Company company = new Company();

    public CompanyBuilder() {
        company.setId(UUID.randomUUID());
        java.time.Instant now = java.time.Instant.now();
        company.setCreatedAt(now);
        company.setUpdatedAt(now);
        company.setCountry("HT");
        company.setFunctionalCurrency("HTG");
        company.setLegalForm(LegalForm.SARL);
        company.setSector(Sector.COMMERCE);
        company.setOrganizationNature(OrganizationNature.FOR_PROFIT);
        company.setFiscalYearStartMonth(10);
        company.setTaxExemptionStatus(TaxExemptionStatus.STANDARD);
        company.setMonthlyLegalHours(new BigDecimal("208"));
        company.setWizardStep(jo.accountant.company.entity.Company.TOTAL_WIZARD_STEPS);
        company.setWizardCompleted(true);
        company.setIsDemo(true);
        company.setFreeZone(false);
    }

    public CompanyBuilder name(String name) { company.setName(name); return this; }
    public CompanyBuilder legalForm(LegalForm form) { company.setLegalForm(form); return this; }
    public CompanyBuilder country(String c) { company.setCountry(c); return this; }
    public CompanyBuilder currency(String c) { company.setFunctionalCurrency(c); return this; }
    public CompanyBuilder nif(String n) { company.setNif(n); return this; }
    public CompanyBuilder address(String a) { company.setAddress(a); return this; }
    public CompanyBuilder sector(Sector s) { company.setSector(s); return this; }
    public CompanyBuilder organizationNature(OrganizationNature n) { company.setOrganizationNature(n); return this; }
    public CompanyBuilder businessTypeCode(String c) { company.setBusinessTypeCode(c); return this; }
    public CompanyBuilder primaryActivity(String a) { company.setPrimaryActivityLabel(a); return this; }
    public CompanyBuilder frameworkId(UUID id) { company.setAccountingFrameworkId(id); return this; }
    public CompanyBuilder fiscalYearStartMonth(int m) { company.setFiscalYearStartMonth(m); return this; }
    public CompanyBuilder freeZone(boolean f) { company.setFreeZone(f); return this; }
    public CompanyBuilder taxExemptionStatus(TaxExemptionStatus s) { company.setTaxExemptionStatus(s); return this; }
    public CompanyBuilder isDemo(boolean d) { company.setIsDemo(d); return this; }

    public Company build() {
        if (company.getName() == null) throw new IllegalStateException("name required");
        return company;
    }
}
