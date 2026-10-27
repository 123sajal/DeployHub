package com.deployhub.platform.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves the API as an RFC 9457 "problem detail" JSON body, so the
 * frontend only has to understand one error shape.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(FieldValidationException.class)
    ProblemDetail handleFieldValidation(FieldValidationException ex) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        body.setProperty("errors", Map.of(ex.getField(), ex.getMessage()));
        return body;
    }

    /** Wrong email or password. Deliberately one message for both, so accounts can't be probed. */
    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Login failed", "Invalid email or password.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required", "Log in to access this resource.");
    }

    /** A service DeployHub depends on (e.g. Docker for databases) isn't available right now. */
    @ExceptionHandler(ServiceUnavailableException.class)
    ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", ex.getMessage());
    }

    /** The deploy needs a paid deploy credit; the body says what it costs so the app can offer to pay. */
    @ExceptionHandler(PaymentRequiredException.class)
    ProblemDetail handlePaymentRequired(PaymentRequiredException ex) {
        ProblemDetail body = problem(HttpStatus.PAYMENT_REQUIRED, "Payment required", ex.getMessage());
        body.setProperty("price", ex.getPrice());
        body.setProperty("currency", ex.getCurrency());
        return body;
    }

    @ExceptionHandler(PaymentVerificationException.class)
    ProblemDetail handlePaymentVerification(PaymentVerificationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Payment not verified", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail handleIdempotencyReuse(IdempotencyKeyReuseException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused", ex.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfter().toSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts", ex.getMessage()));
    }

    /** e.g. two sign-ups racing for the same email; the unique constraint wins. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with existing data.");
    }

    /** The database is unreachable or overloaded. Never fall back; tell the client to retry. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, RecoverableDataAccessException.class})
    ProblemDetail handleDatabaseUnavailable(Exception ex) {
        log.warn("Database unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
                "DeployHub can't reach its database right now. Please try again shortly.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The resource was changed by another request. Reload and try again.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        // A connection lost mid-request can surface wrapped in something generic (e.g. "Unable to
        // rollback" when the rollback also hits the dead connection). The root cause decides.
        if (causedByLostDatabaseConnection(ex)) {
            return handleDatabaseUnavailable(ex);
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> errors.putIfAbsent(
                result.getMethodParameter().getParameterName(),
                result.getResolvableErrors().getFirst().getDefaultMessage()));
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more parameters are invalid.");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Which exception a dropped connection produces depends on timing (query vs. rollback vs. pool),
     * so classify by what they have in common: SQLState class "08" is the SQL standard's
     * "connection exception", and a rollback that fails can only mean the connection is broken.
     */
    private static boolean causedByLostDatabaseConnection(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLRecoverableException || t instanceof SQLTransientConnectionException
                    || t instanceof SQLNonTransientConnectionException || t instanceof java.net.ConnectException
                    || (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))
                    || (t instanceof org.hibernate.TransactionException && String.valueOf(t.getMessage()).contains("rollback"))) {
                return true;
            }
        }
        return false;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
