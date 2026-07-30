package jo.accountant.app;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jo.accountant.core.port.NotificationChannelPort;

/**
 * Spy de test pour {@link NotificationChannelPort}. Capture le dernier email envoyé pour que
 * les tests d'intégration puissent vérifier que le bon template + les bonnes variables ont
 * été utilisés (Phase 1 DoD §13 : "L'invitation utilisateur et la réinitialisation de mot de
 * passe utilisent le NotificationChannelPort").
 *
 * <p>Phase 4 : un compteur {@link #sendCount} a été ajouté pour vérifier que le bon nombre
 * d'emails a été envoyé (par exemple : un par approbateur éligible dans
 * {@code approval-workflow}).
 */
public class RecordingNotificationChannel implements NotificationChannelPort {

    volatile String lastTo;
    volatile String lastTemplateCode;
    volatile Map<String, Object> lastVariables;
    final AtomicInteger sendCount = new AtomicInteger(0);

    @Override
    public synchronized void sendEmail(String to, String templateCode, Map<String, Object> variables) {
        this.lastTo = to;
        this.lastTemplateCode = templateCode;
        this.lastVariables = variables;
        this.sendCount.incrementAndGet();
    }

    public synchronized void reset() {
        this.lastTo = null;
        this.lastTemplateCode = null;
        this.lastVariables = null;
        this.sendCount.set(0);
    }
}

