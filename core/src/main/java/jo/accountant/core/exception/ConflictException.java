package jo.accountant.core.exception;

/** 409 — Violation de règle métier / conflit d'état (par ex. email dupliqué, période verrouillée). */
public class ConflictException extends BusinessException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
