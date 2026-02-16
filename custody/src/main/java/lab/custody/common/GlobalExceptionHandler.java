package lab.custody.common;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;

@ControllerAdvice
public class GlobalExceptionHandler {

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
        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid JSON body. If you are using PowerShell, use double quotes for JSON (or send --data-binary from a file).",
                allowedChainTypes()
        );

        return ResponseEntity.badRequest()
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body);
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
}
