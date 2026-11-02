package com.gutta.gdb.security;

import com.gutta.gdb.config.GdbProperties;
import com.gutta.gdb.shared.SecretHashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class AdminTokenFilter extends OncePerRequestFilter {
    private final GdbProperties properties;
    private final SecretHashing hashing;
    private final AdminUserRepository users;

    public AdminTokenFilter(GdbProperties properties, SecretHashing hashing, AdminUserRepository users) {
        this.properties = properties; this.hashing = hashing; this.users = users;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader("X-GDB-Admin-Token");
        // The bootstrap header is accepted only before the first administrator
        // is provisioned. Normal access must use an expiring session cookie.
        if (candidate != null && users.countByStatus("ACTIVE") == 0 && hashing.constantTimeEquals(candidate, properties.adminToken())) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "gdb-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
        chain.doFilter(request, response);
    }
}
