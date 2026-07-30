package jo.accountant.core.exception;

/** 404 — Ressource introuvable OU appartient à un autre tenant. On ne distingue JAMAIS (§3.9). */
public class NotFoundException extends BusinessException {

    public NotFoundException(String resourceType, Object id) {
        super("NOT_FOUND", resourceType + " not found: " + id);
    }

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
