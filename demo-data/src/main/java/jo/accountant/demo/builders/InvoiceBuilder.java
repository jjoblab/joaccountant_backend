package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.invoicing.entity.InvoiceStatus;
import jo.accountant.invoicing.entity.InvoiceType;
import jo.accountant.invoicing.entity.SalesInvoice;

/**
 * V8.1 — Builder fluent pour créer des factures de vente démo (insertion JPA directe).
 */
public class InvoiceBuilder {

    private final SalesInvoice invoice = new SalesInvoice();

    public InvoiceBuilder() {
        invoice.setType(InvoiceType.STANDARD);
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setCurrency("HTG");
        invoice.setSubtotal(BigDecimal.ZERO);
        invoice.setTaxAmount(BigDecimal.ZERO);
        invoice.setTotalAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setIssueDate(LocalDate.now());
        invoice.setDueDate(LocalDate.now().plusDays(30));
    }

    public InvoiceBuilder thirdPartyId(UUID id) { invoice.setThirdPartyId(id); return this; }
    public InvoiceBuilder type(InvoiceType t) { invoice.setType(t); return this; }
    public InvoiceBuilder status(InvoiceStatus s) { invoice.setStatus(s); return this; }
    public InvoiceBuilder invoiceNumber(String n) { invoice.setInvoiceNumber(n); return this; }
    public InvoiceBuilder issueDate(LocalDate d) { invoice.setIssueDate(d); return this; }
    public InvoiceBuilder dueDate(LocalDate d) { invoice.setDueDate(d); return this; }
    public InvoiceBuilder currency(String c) { invoice.setCurrency(c); return this; }
    public InvoiceBuilder subtotal(BigDecimal s) { invoice.setSubtotal(s); return this; }
    public InvoiceBuilder taxAmount(BigDecimal t) { invoice.setTaxAmount(t); return this; }
    public InvoiceBuilder totalAmount(BigDecimal t) { invoice.setTotalAmount(t); return this; }
    public InvoiceBuilder paidAmount(BigDecimal p) { invoice.setPaidAmount(p); return this; }

    public SalesInvoice build() {
        if (invoice.getThirdPartyId() == null) throw new IllegalStateException("thirdPartyId required");
        return invoice;
    }
}
