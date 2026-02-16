package lab.custody.common;

import lab.custody.domain.withdrawal.ChainType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
