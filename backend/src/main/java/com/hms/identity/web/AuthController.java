package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.application.AuthenticationService;
import com.hms.identity.application.LoginResult;
import com.hms.shared.web.ProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Session login/logout (api-design.md section 2, ADR-0005). The session cookie and CSRF are set up in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthenticationService authentication;
    private final SecurityContextRepository securityContextRepository;

    AuthController(AuthenticationService authentication, SecurityContextRepository securityContextRepository) {
        this.authentication = authentication;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    LoginResponse login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return switch (authentication.login(request.email(), request.password())) {
            case LoginResult.Succeeded ok -> {
                startSession(ok.principal(), httpRequest, httpResponse);
                yield LoginResponse.of(ok.principal(), ok.mustChangePassword());
            }
            case LoginResult.InvalidCredentials ignored -> throw new ProblemException(
                    HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
            case LoginResult.Locked ignored -> throw new ProblemException(
                    HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Too many failed attempts. Try again later.");
            case LoginResult.Inactive ignored -> throw new ProblemException(
                    HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account is not active.");
        };
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal AuthenticatedPrincipal principal, HttpServletRequest httpRequest) {
        authentication.recordLogout(principal);
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate(); // Spring Session deletes the row: the cookie is dead from here on
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    LoginResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return LoginResponse.of(principal, false);
    }

    private void startSession(AuthenticatedPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        // Session fixation: an existing session id (e.g. from an earlier login) is replaced, never reused.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        // Authorities are the permission codes loaded at login (ADR-0006); a role change applies on next login.
        List<SimpleGrantedAuthority> authorities = principal.permissions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
