package lab.custody.common;

import lab.custody.domain.withdrawal.ChainType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern SENSITIVE_HEX_PATTERN = Pattern.compile("0x[a-fA-F0-9]{64,}");

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        List<String> allowedTypes = allowedChainTypes();
        String message = ex.getMessage();

        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "Unsupported chain type '%s'. Allowed types: %s"
                    .formatted(mismatch.getValue(), String.join(", ", allowedTypes));
        }

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                allowedTypes
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String detail = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        String message = "Invalid JSON body. If you are using PowerShell, use double quotes for JSON (or send --data-binary from a file).";
        if (detail != null && !detail.isBlank()) {
            message += " Detail: " + detail;
        }

        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                allowedChainTypes()
        );

        return ResponseEntity.badRequest()
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Missing required header: " + ex.getHeaderName(),
                allowedChainTypes()
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({IllegalStateException.class, RuntimeException.class})
    public ResponseEntity<RuntimeErrorResponse> handleRuntimeException(Exception ex, HttpServletRequest request) {
        RuntimeErrorResponse body = new RuntimeErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                sanitizeMessage(ex.getMessage()),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unexpected server error";
        }
        return SENSITIVE_HEX_PATTERN.matcher(message).replaceAll("0x[REDACTED]");
    }

    private List<String> allowedChainTypes() {
        return Arrays.stream(ChainType.values())
                .map(Enum::name)
                .toList();
    }

    public record ErrorResponse(
            int status,
            String message,
            List<String> allowedTypes
    ) {}

    public record RuntimeErrorResponse(
            int status,
            String message,
            String path
    ) {
    }
}
