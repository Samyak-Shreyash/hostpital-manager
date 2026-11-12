package com.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Session-cookie authentication (ADR-0005): the security context lives in the server-side session
 * (Spring Session JDBC), state-changing requests need the {@code X-XSRF-TOKEN} header matching the
 * {@code XSRF-TOKEN} cookie, and every {@code /api/**} call except login needs a session. Endpoint-level
 * rules are {@code @PreAuthorize} on each controller method (ADR-0006).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Shared with the login endpoint, which saves the context it creates (identity web layer). */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 401/403 raised in the filter chain go to the MVC exception resolver, so they get the same
     * Problem+JSON body as every other error (ProblemDetailsHandler).
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Scraped by Prometheus inside the network; never routed by the reverse proxy.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // SPA mode: XSRF-TOKEN cookie readable by the SPA, sent back as X-XSRF-TOKEN; applies to login too.
                .csrf(csrf -> csrf.spa())
                // Logout is POST /api/v1/auth/logout (audited); the framework's /logout endpoint is not exposed.
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                resolver.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) ->
                                resolver.resolveException(request, response, null, e)))
                .build();
    }
}
