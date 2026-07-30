package jo.accountant.core.exception;

/** 403 — Permissions insuffisantes (par ex. mauvais rôle, mismatch tenant remonté explicitement). */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String code, String message) {
        super(code, message);
    }

    public ForbiddenException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
