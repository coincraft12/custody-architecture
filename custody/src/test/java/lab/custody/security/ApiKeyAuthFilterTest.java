package lab.custody.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new ApiKeyAuthFilter(Map.of(
                "operator-key", List.of("OPERATOR"),
                "approver-key", List.of("APPROVER", "OPERATOR"),
                "admin-key",    List.of("ADMIN", "APPROVER", "OPERATOR")
        ));
    }

    @Test
    void validOperatorKey_setsAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "operator-key");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void validAdminKey_hasAllRoles() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "admin-key");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_APPROVER", "ROLE_OPERATOR");
    }

    @Test
    void invalidKey_noAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key");

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingKey_noAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
