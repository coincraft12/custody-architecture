package lab.custody.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 기준 Rate Limiting 필터.
 *
 * POST /withdrawals : withdrawalsPerSecond 토큰/초
 * POST /whitelist   : whitelistPerSecond   토큰/초
 *
 * 초과 시 429 Too Many Requests 반환.
 * custody.rate-limit.enabled=false 로 비활성화 가능.
 */
@Component
@ConditionalOnProperty(name = "custody.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimitProperties.class)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String POST = HttpMethod.POST.name();

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    // key = "ENDPOINT:IP"
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String method = request.getMethod();
        String path   = request.getRequestURI();

        Integer limit = resolveLimit(method, path);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = resolveIp(request);
        String key = path + ":" + ip;

        Bucket bucket = buckets.computeIfAbsent(key, k -> buildBucket(limit));

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("event=rate_limit.exceeded path={} ip={}", path, ip);
            writeTooManyRequests(response, path);
        }
    }

    private Integer resolveLimit(String method, String path) {
        if (!POST.equals(method)) return null;
        if ("/withdrawals".equals(path))  return props.withdrawalsPerSecond();
        if ("/whitelist".equals(path))    return props.whitelistPerSecond();
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Bucket buildBucket(int tokensPerSecond) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(tokensPerSecond)
                .refillGreedy(tokensPerSecond, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private void writeTooManyRequests(HttpServletResponse response, String path) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "1");

        Map<String, Object> body = Map.of(
                "status", 429,
                "message", "Too many requests. Please slow down.",
                "path", path
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
