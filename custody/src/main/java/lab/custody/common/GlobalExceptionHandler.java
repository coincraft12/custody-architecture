package lab.custody.common;

import lab.custody.adapter.BroadcastRejectedException;
import lab.custody.orchestration.IdempotencyConflictException;
import lab.custody.orchestration.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SENSITIVE_HEX_PATTERN = Pattern.compile("0x[a-fA-F0-9]{64,}");

    // 6-1-4 (already done): Bean Validation → 400 VALIDATION_ERROR
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationError(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        return ResponseEntity.badRequest().body(new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", "Validation failed",
                errors, currentCorrelationId(), Instant.now()));
    }

    // Chain type mismatch / bad argument → 400 BAD_REQUEST
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class,
            InvalidRequestException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex) {
        String message = ex.getMessage();
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "Unsupported value '%s'. Allowed: EVM, BFT".formatted(mismatch.getValue());
        }
        return badRequest("BAD_REQUEST", sanitizeMessage(message));
    }

    // Broadcast rejected by RPC → 400 BAD_REQUEST
    @ExceptionHandler(BroadcastRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleBroadcastRejected(BroadcastRejectedException ex) {
        return badRequest("BAD_REQUEST", sanitizeMessage(ex.getMessage()));
    }

    // Malformed JSON → 400 BAD_REQUEST
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String detail = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        String message = "Invalid JSON body. If you are using PowerShell, use double quotes for JSON (or send --data-binary from a file).";
        if (detail != null && !detail.isBlank()) {
            message += " Detail: " + detail;
        }
        return ResponseEntity.badRequest()
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), "BAD_REQUEST",
                        message, null, currentCorrelationId(), Instant.now()));
    }

    // Missing required request header → 400 BAD_REQUEST
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return badRequest("BAD_REQUEST", "Missing required header: " + ex.getHeaderName());
    }

    // Idempotency conflict → 409 CONFLICT
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(
                HttpStatus.CONFLICT.value(), "CONFLICT", sanitizeMessage(ex.getMessage()),
                null, currentCorrelationId(), Instant.now()));
    }

    // 6-1-5: No handler / resource found → 404 NOT_FOUND
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(), "NOT_FOUND",
                "No handler found for " + request.getMethod() + " " + request.getRequestURI(),
                request.getRequestURI(), currentCorrelationId(), Instant.now()));
    }

    // 6-1-1/6-1-2: DB / transaction failures → 503 SERVICE_UNAVAILABLE
    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    public ResponseEntity<ApiErrorResponse> handleServiceUnavailable(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "SERVICE_UNAVAILABLE",
                "A database error occurred. Please retry later.",
                request.getRequestURI(), currentCorrelationId(), Instant.now()));
    }

    // Catch-all → 500 INTERNAL_ERROR
    @ExceptionHandler({IllegalStateException.class, RuntimeException.class})
    public ResponseEntity<ApiErrorResponse> handleRuntimeException(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                sanitizeMessage(ex.getMessage()),
                request.getRequestURI(), currentCorrelationId(), Instant.now()));
    }

    private ResponseEntity<ApiErrorResponse> badRequest(String errorCode, String message) {
        return ResponseEntity.badRequest().body(new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(), errorCode, message,
                null, currentCorrelationId(), Instant.now()));
    }

    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unexpected server error";
        }
        return SENSITIVE_HEX_PATTERN.matcher(message).replaceAll("0x[REDACTED]");
    }

    private String currentCorrelationId() {
        return MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
    }

    // 6-1-6: Unified error response — status, errorCode, message, path (5xx only), correlationId, timestamp
    public record ApiErrorResponse(
            int status,
            String errorCode,
            String message,
            String path,
            String correlationId,
            Instant timestamp
    ) {}

    // Validation errors carry an extra errors[] field
    public record ValidationErrorResponse(
            int status,
            String errorCode,
            String message,
            List<String> errors,
            String correlationId,
            Instant timestamp
    ) {}
}
