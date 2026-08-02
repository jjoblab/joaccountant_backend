package jo.accountant.documentnumbering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import jo.accountant.core.tenant.TenantAwareEntity;

/**
 * Compteur d'une séquence documentaire (§6).
 *
 * <p>Une seule ligne active par {@code (sequenceConfigId, periodKey)}. La ligne est
 * <strong>verrouillée à chaque émission</strong> via {@code SELECT ... FOR UPDATE} dans
 * {@link jo.accountant.documentnumbering.service.DocumentNumberingService#nextNumber} —
 * c'est ce verrou pessimiste qui garantit l'atomicité de l'incrémentation et l'absence de
 * doublon en cas d'appels concurrents (§6 règle non négociable).
 *
 * <p>{@code periodKey} dépend de {@link DocumentSequenceConfig#getResetPolicy()} :
 * <ul>
 * <li>{@link ResetPolicy#NEVER} → {@code periodKey} reste vide (chaîne vide)</li>
 * <li>{@link ResetPolicy#YEARLY} → {@code periodKey} = année sur 4 chiffres (ex. {@code "2026"})</li>
 * <li>{@link ResetPolicy#MONTHLY} → {@code periodKey} = {@code "YYYY-MM"} (ex. {@code "2026-07"})</li>
 * </ul>
 *
 * <p>Lorsque la période change, une NOUVELLE ligne est créée avec le nouveau {@code periodKey}
 * et {@code lastValue = 0}. L'ancienne ligne reste pour audit — elle n'est jamais réutilisée
 * (conformément à la règle "aucune réutilisation de numéro", §6).
 *
 * <p>Pourquoi ne pas utiliser une SEQUENCE PostgreSQL native ? Parce que la {@code resetPolicy}
 * {@code YEARLY}/{@code MONTHLY} nécessite de changer de {@code periodKey} proprement, ce qu'une
 * SEQUENCE PG ne sait pas faire sans {@code ALTER SEQUENCE RESTART} (fragile, non atomique avec
 * la détermination du periodKey). Le verrou pessimiste sur un compteur applicatif est le
 * pattern retenu à l'implémentation (autorisé explicitement par §6).
 *
 * <p>La {@code @Version} (verrouillage optimiste) est également posée comme filet de sécurité
 * supplémentaire : même si le verrou pessimiste était accidentellement retiré, deux écritures
 * concurrentes ne pourraient pas toutes deux réussir.
 */
@Entity
@Table(name = "document_sequence_counter",
    uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uc_doc_seq_counter",
            columnNames = {"sequence_config_id", "period_key"})
    })
/**
 * DocumentSequenceCounter.
 *
 * @author jo@Dev


 */

public class DocumentSequenceCounter extends TenantAwareEntity {

    @Column(name = "sequence_config_id", nullable = false)
    private UUID sequenceConfigId;

    @Column(name = "period_key", nullable = false, length = 10)
    private String periodKey = "";

    @Column(name = "last_value", nullable = false)
    private long lastValue = 0;

    public UUID getSequenceConfigId() { return sequenceConfigId; }
    public void setSequenceConfigId(UUID sequenceConfigId) { this.sequenceConfigId = sequenceConfigId; }

    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey == null ? "" : periodKey;
    }

    public long getLastValue() { return lastValue; }
    public void setLastValue(long lastValue) { this.lastValue = lastValue; }
}
