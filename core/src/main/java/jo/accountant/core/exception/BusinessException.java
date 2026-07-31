package jo.accountant.core.exception;

/**
 * Type de base de toutes les exceptions métier (§3.9). Les sous-classes mappent vers des codes
 * HTTP spécifiques via {@link GlobalExceptionHandler}. Chaque exception porte un {@code code}
 * stable sur lequel le frontend peut brancher sans parser du texte libre.
 */
public abstract class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}
