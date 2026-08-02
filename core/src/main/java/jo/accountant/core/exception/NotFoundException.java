package jo.accountant.core.exception;

/** 404 — Ressource introuvable OU appartient à un autre tenant. On ne distingue JAMAIS (§3.9). 
 *
 * @author jo@Dev


*/
public class NotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String resourceType, Object id) {
        super("NOT_FOUND", resourceType + " not found: " + id);
    }

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
