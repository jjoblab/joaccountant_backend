package jo.accountant.demo.builders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalEntry;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;

/**
 * V8.1 — Builder fluent pour créer des écritures comptables démo (insertion JPA directe).
 *
 * <p>Construit l'entité {@link JournalEntry} sans passer par {@code AccountingEngineService}
 * (qui exige idempotence, fiscal period resolution, etc.). Permet aux seeders démo d'insérer
 * rapidement des écritures POSTED pour alimenter les dashboards.
 */
public class JournalEntryBuilder {

    private final JournalEntry entry = new JournalEntry();

    public JournalEntryBuilder() {
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setSourceModule(JournalEntrySourceModule.MANUAL);
        entry.setEntryDate(LocalDate.now());
        entry.setIdempotencyKey("demo-" + UUID.randomUUID().toString());
    }

    public JournalEntryBuilder journalId(UUID id) { entry.setJournalId(id); return this; }
    public JournalEntryBuilder fiscalPeriodId(UUID id) { entry.setFiscalPeriodId(id); return this; }
    public JournalEntryBuilder entryDate(LocalDate d) { entry.setEntryDate(d); return this; }
    public JournalEntryBuilder reference(String r) { entry.setReference(r); return this; }
    public JournalEntryBuilder description(String d) { entry.setDescription(d); return this; }
    public JournalEntryBuilder status(JournalEntryStatus s) { entry.setStatus(s); return this; }
    public JournalEntryBuilder sourceModule(JournalEntrySourceModule m) { entry.setSourceModule(m); return this; }
    public JournalEntryBuilder idempotencyKey(String k) { entry.setIdempotencyKey(k); return this; }

    public JournalEntry build() {
        if (entry.getJournalId() == null) throw new IllegalStateException("journalId required");
        if (entry.getFiscalPeriodId() == null) throw new IllegalStateException("fiscalPeriodId required");
        return entry;
    }
}
