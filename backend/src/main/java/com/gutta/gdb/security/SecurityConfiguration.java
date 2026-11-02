package com.gutta.gdb.security;

import com.gutta.gdb.config.ApiErrorResponse;
import com.gutta.gdb.config.GdbProperties;
import com.gutta.gdb.shared.SecretHashing;
import com.gutta.gdb.device.DeviceAuthenticationService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GdbProperties properties,
                                            DeviceAuthenticationService devices, SecretHashing hashing,
                                            AdminAuthService adminAuth, AdminUserRepository adminUsers,
                                            ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/api/v1/devices/register",
                                "/api/v1/auth/admin/login", "/api/v1/auth/admin/bootstrap").permitAll()
                        .requestMatchers("/api/v1/auth/admin/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit", "/api/v1/admin/monitoring/**",
                                "/api/v1/admin/conflicts/**", "/api/v1/admin/snapshots/**",
                                "/api/v1/admin/synchronization/**").hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("DEVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> writeError(response, mapper, HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHENTICATED", "Authentication is required."))
                        .accessDeniedHandler((request, response, error) -> writeError(response, mapper, HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN", "This credential is not authorized.")))
                .addFilterBefore(new AdminSessionFilter(adminAuth), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new AdminTokenFilter(properties, hashing, adminUsers), AdminSessionFilter.class)
                .addFilterAfter(new DeviceTokenFilter(devices), AdminTokenFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status,
                                   String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(code, message));
    }
}
