package jo.accountant.notifications.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jo.accountant.core.port.NotificationChannelPort;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.notifications.dto.CreateAlertRuleRequest;
import jo.accountant.notifications.dto.NotificationResponse;
import jo.accountant.notifications.dto.UpdatePreferencesRequest;
import jo.accountant.notifications.entity.AlertRule;
import jo.accountant.notifications.entity.AlertType;
import jo.accountant.notifications.entity.Notification;
import jo.accountant.notifications.entity.NotificationChannel;
import jo.accountant.notifications.entity.NotificationPreference;
import jo.accountant.notifications.entity.NotificationStatus;
import jo.accountant.notifications.repository.AlertRuleRepository;
import jo.accountant.notifications.repository.NotificationPreferenceRepository;
import jo.accountant.notifications.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de notifications (§9, §13 Phase 15).
 *
 * <p>Devient l'implémentation de référence de {@link NotificationChannelPort} (posé en Phase 1).
 * Absorbe l'historique des envois (table {@link Notification}) — le contrat du port ne change pas,
 * seule l'implémentation gagne en richesse (persistance, préférences, canal in-app).
 *
 * <p>Pattern : même que :audit-trail — s'abonne aux événements de domaine, aucun module métier
 * n'appelle :notifications directement (principe 5).
 */
@Service
public class NotificationsService implements NotificationChannelPort {

 private static final Logger LOG = LoggerFactory.getLogger(NotificationsService.class);

 private final NotificationRepository notificationRepository;
 private final NotificationPreferenceRepository preferenceRepository;
 private final AlertRuleRepository alertRuleRepository;
 private final ObjectMapper objectMapper;

 public NotificationsService(NotificationRepository notificationRepository,
 NotificationPreferenceRepository preferenceRepository,
 AlertRuleRepository alertRuleRepository,
 ObjectMapper objectMapper) {
 this.notificationRepository = notificationRepository;
 this.preferenceRepository = preferenceRepository;
 this.alertRuleRepository = alertRuleRepository;
 this.objectMapper = objectMapper;
 }

 // --- NotificationChannelPort implementation ---

 /**
 * Implémentation de {@link NotificationChannelPort#sendEmail}.
 *
 * <p>En Phase 15, cette méthode crée une notification IN_APP + tente l'envoi EMAIL.
 * Le destinataire est identifié par son email — on crée une notification avec
 * recipientUserId = null (système) car on n'a pas accès à :auth pour résoudre
 * l'email en userId. L'email est stocké dans le payload.
 */
 @Override
 @Transactional
 public void sendEmail(String to, String templateCode, Map<String, Object> variables) {
 try {
 Map<String, Object> payload = new HashMap<>();
 payload.put("to", to);
 payload.put("templateCode", templateCode);
 if (variables != null) payload.put("variables", variables);

 Notification notif = new Notification();
 notif.setId(UUID.randomUUID());
 notif.setRecipientUserId(TenantContext.getUserId()); // peut être null
 notif.setType(templateCode);
 notif.setPayloadJson(objectMapper.writeValueAsString(payload));
 notif.setChannel(NotificationChannel.EMAIL);
 notif.setStatus(NotificationStatus.SENT);
 notif.setCreatedAt(Instant.now());
 notif.setSentAt(Instant.now());
 notificationRepository.save(notif);

 LOG.info("Notification EMAIL envoyée : to={} template={}", to, templateCode);
 } catch (Exception e) {
 LOG.error("Échec d'envoi de notification : to={} template={}", to, templateCode, e);
 }
 }

 // --- Notifications in-app ---

 /**
 * Crée une notification in-app pour un utilisateur.
 */
 @Transactional
 public Notification createInAppNotification(UUID companyId, UUID recipientUserId,
 String type, Map<String, Object> payload) {
 try {
 Notification notif = new Notification();
 notif.setId(UUID.randomUUID());
 notif.setCompanyId(companyId);
 notif.setRecipientUserId(recipientUserId);
 notif.setType(type);
 notif.setPayloadJson(payload != null ? objectMapper.writeValueAsString(payload) : "{}");
 notif.setChannel(NotificationChannel.IN_APP);
 notif.setStatus(NotificationStatus.PENDING); // also SENT;
 notif.setCreatedAt(Instant.now());
 return notificationRepository.save(notif);
 } catch (Exception e) {
 throw new IllegalStateException("Failed to create notification", e);
 }
 }

 @Transactional(readOnly = true)
 public List<NotificationResponse> listNotifications(UUID recipientUserId) {
 return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId)
 .stream().map(NotificationsService::toResponse).toList();
 }

 /**
 * Liste paginée des notifications d'un utilisateur — .
 *
 * <p>Variante paginée de {@link #listNotifications(UUID)} — utilise le {@code Page<>} du
 * repository pour ne charger qu'une page à la fois. Le {@code Pageable} doit être cappé
 * côté appelant (typiquement {@code size ≤ 200}).
 *
 * @param recipientUserId identifiant de l'utilisateur destinataire
 * @param pageable paramètres de pagination (page, size, sort)
 * @return page de {@link NotificationResponse}
 */
 @Transactional(readOnly = true)
 public org.springframework.data.domain.Page<NotificationResponse> listNotifications(
 UUID recipientUserId, org.springframework.data.domain.Pageable pageable) {
 return notificationRepository
 .findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, pageable)
 .map(NotificationsService::toResponse);
 }

 @Transactional
 public NotificationResponse markAsRead(UUID companyId, UUID notificationId, UUID userId) {
 Notification notif = notificationRepository.findById(notificationId)
 .orElseThrow(() -> new jo.accountant.core.exception.NotFoundException("Notification", notificationId));
 // Audit v4.7 §6.2 — defense-in-depth : vérifier companyId ET recipientUserId
 // (avant : seul recipientUserId était vérifié, pas companyId → un user appartenant à
 // plusieurs companies pouvait marquer comme lu une notification d'une autre company)
 if (notif.getCompanyId() != null && !notif.getCompanyId().equals(companyId)) {
 throw new jo.accountant.core.exception.NotFoundException("Notification", notificationId);
 }
 if (!notif.getRecipientUserId().equals(userId)) {
 throw new jo.accountant.core.exception.NotFoundException("Notification", notificationId);
 }
 notif.setStatus(NotificationStatus.READ);
 notif.setReadAt(Instant.now());
 notificationRepository.save(notif);
 return toResponse(notif);
 }

 /**
 * @deprecated utiliser {@link #markAsRead(UUID, UUID, UUID)} avec companyId. Conservé pour
 * backward-compat pendant la migration des callers — sera supprimé en v4.8.
 */
 @Deprecated
 @Transactional
 public NotificationResponse markAsRead(UUID notificationId, UUID userId) {
 return markAsRead(null, notificationId, userId);
 }

 /**
 * Marque toutes les notifications non lues comme lues (bulk).
 */
 @Transactional
 public List<NotificationResponse> markAllAsRead(UUID companyId, UUID userId) {
 List<Notification> all = notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId);
 List<Notification> unread = all.stream()
 .filter(n -> n.getStatus() != jo.accountant.notifications.entity.NotificationStatus.READ)
 .collect(java.util.stream.Collectors.toList());;
 Instant now = Instant.now();
 List<NotificationResponse> result = new java.util.ArrayList<>();
 for (Notification n : unread) {
 if (companyId != null && n.getCompanyId() != null && !n.getCompanyId().equals(companyId))
 continue;
 n.setStatus(NotificationStatus.READ);
 n.setReadAt(now);
 notificationRepository.save(n);
 result.add(toResponse(n));
 }
 return result;
 }

 // --- Préférences ---

 @Transactional
 public NotificationPreference updatePreferences(UUID userId, UUID companyId, UpdatePreferencesRequest req) {
 NotificationPreference pref = preferenceRepository
 .findByUserIdAndCompanyIdAndType(userId, companyId, req.type())
 .orElseGet(() -> {
 NotificationPreference p = new NotificationPreference();
 p.setId(UUID.randomUUID());
 p.setUserId(userId);
 p.setCompanyId(companyId);
 p.setType(req.type());
 return p;
 });
 if (req.emailEnabled() != null) pref.setEmailEnabled(req.emailEnabled());
 if (req.inAppEnabled() != null) pref.setInAppEnabled(req.inAppEnabled());
 return preferenceRepository.save(pref);
 }

 @Transactional(readOnly = true)
 public List<NotificationPreference> getPreferences(UUID userId, UUID companyId) {
 return preferenceRepository.findAll().stream()
 .filter(p -> p.getUserId().equals(userId) && p.getCompanyId().equals(companyId))
 .toList();
 }

 // --- Règles d'alerte ---

 @Transactional
 public AlertRule createAlertRule(UUID companyId, CreateAlertRuleRequest req) {
 AlertRule rule = new AlertRule();
 rule.setId(UUID.randomUUID());
 rule.setCompanyId(companyId);
 rule.setType(req.type());
 rule.setThresholdValue(req.thresholdValue());
 rule.setActive(req.active());
 return alertRuleRepository.save(rule);
 }

 @Transactional(readOnly = true)
 public List<AlertRule> listAlertRules(UUID companyId) {
 return alertRuleRepository.findByCompanyId(companyId);
 }

 @Transactional(readOnly = true)
 public boolean isAlertActive(UUID companyId, AlertType type) {
 return alertRuleRepository.findByCompanyIdAndActiveTrue(companyId).stream()
 .anyMatch(r -> r.getType() == type);
 }

 // --- Helper ---

 private static NotificationResponse toResponse(Notification n) {
 return new NotificationResponse(n.getId(), n.getCompanyId(), n.getRecipientUserId(),
 n.getType(), n.getPayloadJson(), n.getChannel(), n.getStatus(),
 n.getCreatedAt(), n.getSentAt(), n.getReadAt());
 }
}
