package jo.accountant.notifications.entity;

/**
 * Canal de notification (§9).
 *
 * <ul>
 * <li>{@link #EMAIL} — e-mail via {@link jo.accountant.core.port.NotificationChannelPort}</li>
 * <li>{@link #IN_APP} — notification in-app, consultée à la demande via l'API</li>
 * </ul>
 *
 * <p>Hors périmètre v1 (§9) : SMS, notifications push mobile natives, canal temps réel (WebSocket).
 
 *
 * @author jo@Dev


*/
public enum NotificationChannel {
    EMAIL,
    IN_APP
}
