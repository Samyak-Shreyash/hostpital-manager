package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.application.AuthenticationService;
import com.hms.shared.web.ProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * While the user's {@code must_change_password} flag is set, every {@code /api/**} call except the auth
 * endpoints below is refused with 403 PASSWORD_CHANGE_REQUIRED (api-design.md section 2). The flag is read
 * from the database on each request, so it clears the moment the password is changed and a flag set by an
 * admin reset applies to sessions that are already open. Runs after security, so anonymous calls are still 401.
 */
@Configuration(proxyBeanMethods = false)
class PasswordChangeGate implements WebMvcConfigurer {

    static final Set<String> EXEMPT = Set.of(
            "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/change-password", "/api/v1/auth/login");

    private final AuthenticationService authentication;

    PasswordChangeGate(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal principal
                        && !EXEMPT.contains(request.getRequestURI())
                        && authentication.isPasswordChangeRequired(principal.userId())) {
                    throw new ProblemException(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                            "You must change your password before continuing.");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
