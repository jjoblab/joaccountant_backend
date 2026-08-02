package jo.accountant.core.exception;

/** 403 — Permissions insuffisantes (par ex. mauvais rôle, mismatch tenant remonté explicitement). 
 *
 * @author jo@Dev


*/
public class ForbiddenException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String code, String message) {
        super(code, message);
    }

    public ForbiddenException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
