package jo.accountant.core.exception;

/** 422 — Échec de validation Bean / validation sémantique (par ex. mot de passe faible, champ obligatoire manquant). */
public class ValidationException extends BusinessException {

    public ValidationException(String code, String message) {
        super(code, message);
    }
}
