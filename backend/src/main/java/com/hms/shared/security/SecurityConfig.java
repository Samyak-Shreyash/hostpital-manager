package com.hms.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Skeleton security: only health probes are public, everything else is denied until
 * authentication (ADR-0005) and RBAC (ADR-0006) land in M1.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 401/403 raised in the filter chain go to the MVC exception resolver, so they get the same
     * Problem+JSON body as every other error (ProblemDetailsHandler).
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                resolver.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) ->
                                resolver.resolveException(request, response, null, e)))
                .build();
    }
}
