package com.gutta.gdb.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

import static com.gutta.gdb.security.AdminDtos.*;

@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {
    private final AdminAuthService auth;
    private final com.gutta.gdb.config.GdbProperties properties;

    public AdminAuthController(AdminAuthService auth, com.gutta.gdb.config.GdbProperties properties) {
        this.auth = auth; this.properties = properties;
    }

    @PostMapping("/bootstrap")
    ResponseEntity<SessionResponse> bootstrap(@Valid @RequestBody BootstrapRequest request, HttpServletResponse response) {
        AdminAuthService.SessionIssue issue = auth.bootstrap(request);
        setCookie(response, issue.rawToken(), properties.adminSessionTtl());
        return ResponseEntity.status(HttpStatus.CREATED).body(issue.response());
    }

    @PostMapping("/login")
    SessionResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AdminAuthService.SessionIssue issue = auth.login(request);
        setCookie(response, issue.rawToken(), properties.adminSessionTtl());
        return issue.response();
    }

    @GetMapping("/me")
    SessionResponse me(Authentication authentication) {
        AdminPrincipal principal = (AdminPrincipal) authentication.getPrincipal();
        return new SessionResponse(principal.username(), principal.role(), null);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(cookie(request));
        clearCookie(response);
    }

    @PostMapping("/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeAll(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        auth.revokeAll((AdminPrincipal) authentication.getPrincipal());
        clearCookie(response);
    }

    private void setCookie(HttpServletResponse response, String token, Duration ttl) {
        response.addHeader("Set-Cookie", ResponseCookie.from(AdminSessionFilter.COOKIE_NAME, token)
                .httpOnly(true).secure(properties.adminSessionCookieSecure()).sameSite("Strict")
                .path("/").maxAge(ttl).build().toString());
    }

    private void clearCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(AdminSessionFilter.COOKIE_NAME, "")
                .httpOnly(true).secure(properties.adminSessionCookieSecure()).sameSite("Strict")
                .path("/").maxAge(Duration.ZERO).build().toString());
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (AdminSessionFilter.COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
