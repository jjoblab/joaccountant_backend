package jo.accountant.core.port;

import java.util.Map;

/**
 * Abstraction du canal de notification sortant (§3.12). Définie dans :core pour que la* puisse déjà envoyer les emails « invitation utilisateur » et « réinitialisation de mot de
 * passe » SANS dépendre du module complet :notifications (construit en.
 *
 * <p>§3.12 : le fournisseur n'est intentionnellement pas choisi à ce stade — décision différée.
 * Tout envoi d'email passe par ce port, jamais via un appel SDK direct depuis un module métier.
 * L'envoi est toujours asynchrone (listener), jamais sur le thread de la requête HTTP.
 *
 * <p>Quand :notifications sera construit, il deviendra l'implémentation de référence
 * de ce port et absorbera l'historique (table {@code Notification}) ; le contrat ne change PAS.
 
 *
 * @author jo@Dev


*/
public interface NotificationChannelPort {

    /**
     * Envoie un email templaté à {@code to}.
     *
     * @param to adresse email du destinataire
     * @param templateCode code stable du template (par ex. {@code user-invitation}, {@code password-reset})
     * @param variables variables du template (doivent être sérialisables JSON)
     */
    void sendEmail(String to, String templateCode, Map<String, Object> variables);
}
