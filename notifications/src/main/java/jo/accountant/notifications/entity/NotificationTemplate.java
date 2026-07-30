package jo.accountant.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Gabarit de notification (§9). Seed de base, personnalisable par entreprise.
 */
@Entity
@Table(name = "ntf_template")
public class NotificationTemplate {

    @Id
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "locale", nullable = false, length = 5)
    private String locale = "fr";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public NotificationChannel getChannel() { return channel; }
    public void setChannel(NotificationChannel channel) { this.channel = channel; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
