package jo.accountant.core.port;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Implémentation par défaut de dev de {@link NotificationChannelPort} (§3.12).
 *
 * <p>§3.12 : le vrai fournisseur n'est intentionnellement pas choisi à ce stade — décision
 * différée. En dev/test, on logge chaque « email » au niveau INFO pour que les tests puissent
 * assert via capture de logs ou via un spy de ce bean (voir les tests d'auth). Une implémentation
 * réelle (relais SMTP ou SaaS transactionnel) sera branchée en Phase 1 ou plus tard via un
 * override de bean — les appelants ne changent jamais.
 */
@Component
@ConditionalOnMissingBean(NotificationChannelPort.class)
public class LoggingNotificationChannelAdapter implements NotificationChannelPort {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNotificationChannelAdapter.class);

    @Override
    public void sendEmail(String to, String templateCode, Map<String, Object> variables) {
        LOG.info("[NOTIFY] to={} template={} variables={}", to, templateCode, variables);
    }
}
