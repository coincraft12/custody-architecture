package lab.custody.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    public static final String MDC_CLIENT_ID_KEY = "clientId";
    public static final String MDC_USER_ID_KEY = "userId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
        MDC.put(MDC_CLIENT_ID_KEY, resolveMockClientId(correlationId));
        MDC.put(MDC_USER_ID_KEY, resolveMockUserId(correlationId, request));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID_KEY);
            MDC.remove(MDC_CLIENT_ID_KEY);
            MDC.remove(MDC_USER_ID_KEY);
        }
    }

    private String resolveCorrelationId(String incoming) {
        if (incoming == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = incoming.trim();
        return trimmed.isEmpty() ? UUID.randomUUID().toString() : trimmed;
    }

    // Lab/demo-only mock identity values so students can see "request trace" and "request actor" separately.
    private String resolveMockClientId(String correlationId) {
        int n = Math.floorMod(correlationId.hashCode(), 3) + 1;
        return "mock-client-%02d".formatted(n);
    }

    private String resolveMockUserId(String correlationId, HttpServletRequest request) {
        String seed = correlationId + "|" + request.getMethod() + "|" + request.getRequestURI();
        int n = Math.floorMod(seed.hashCode(), 20) + 1;
        return "mock-user-%03d".formatted(n);
    }
}
