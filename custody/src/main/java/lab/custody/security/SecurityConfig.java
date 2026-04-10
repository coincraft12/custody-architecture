package lab.custody.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeyProperties.class)
@ConditionalOnProperty(name = "custody.security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfig {

    private final ApiKeyProperties properties;

    public SecurityConfig(ApiKeyProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        Map<String, List<String>> keyToRoles = properties.getApiKeys().stream()
                .filter(e -> e.key() != null && !e.key().isBlank())
                .collect(Collectors.toMap(
                        ApiKeyProperties.ApiKeyEntry::key,
                        ApiKeyProperties.ApiKeyEntry::roles
                ));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(keyToRoles),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // 헬스체크 — 인증 불필요
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // H2 콘솔 — 개발 환경에서만 (production 프로파일에선 콘솔 자체가 비활성화)
                        .requestMatchers("/h2/**").permitAll()
                        // 화이트리스트 승인·취소 — APPROVER 이상
                        .requestMatchers(HttpMethod.POST, "/whitelist/*/approve").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST, "/whitelist/*/revoke").hasRole("APPROVER")
                        // 출금 생성 — OPERATOR 이상
                        .requestMatchers(HttpMethod.POST, "/withdrawals").hasRole("OPERATOR")
                        // 나머지 모든 요청 — 인증 필요
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"status\":401,\"message\":\"Missing or invalid X-API-Key header\"}"
                            );
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write(
                                    "{\"status\":403,\"message\":\"Insufficient role for this operation\"}"
                            );
                        })
                );

        return http.build();
    }
}
