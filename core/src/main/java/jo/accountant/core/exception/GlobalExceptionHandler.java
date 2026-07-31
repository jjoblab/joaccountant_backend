package jo.accountant.core.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Mappe chaque exception métier prévisible vers un {@link ProblemDetail} (RFC 7807).
 *
 * <p>§3.9 : ne JAMAIS renvoyer un 500 générique sur un cas prévisible. Le chemin 500 est réservé
 * aux erreurs réellement inattendues et est loggé au niveau ERROR avec l'id de corrélation.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(ValidationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", detail, req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
            "Invalid value for parameter " + ex.getName(), req);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        String correlationId = TenantContext.getCorrelationId();
        LOG.error("Unexpected error [correlationId={}]", correlationId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred. Reference: " + correlationId, req);
    }

    // v2.5.2 — NoResourceFoundException (Spring Boot 3.2+) : retourner 404 avec détail
    // au lieu de laisser tomber dans Exception.class → 500 générique. Permet de
    // distinguer "endpoint n'existe pas" (404) des vraies erreurs serveur (500).
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest req) {
        String correlationId = TenantContext.getCorrelationId();
        LOG.warn("No resource found [correlationId={}] : {}", correlationId, ex.getMessage());
        ProblemDetail pd = build(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "Endpoint ou ressource introuvable : " + req.getMethod() + " " + req.getRequestURI()
                + " (Reference: " + correlationId + ")", req);
        pd.setProperty("hint", "Vérifiez l'URL ou la version du backend déployé.");
        return pd;
    }

    private ProblemDetail build(HttpStatus status, String code, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setType(URI.create("https://joaccountant.dev/errors/" + code.toLowerCase()));
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code);
        pd.setProperty("correlationId", TenantContext.getCorrelationId());
        pd.setProperty("companyId", TenantContext.getCompanyId());
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
