package jo.accountant.core.exception;

/** 409 — Violation de règle métier / conflit d'état (par ex. email dupliqué, période verrouillée). 
 *
 * @author jo@Dev


*/
public class ConflictException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
