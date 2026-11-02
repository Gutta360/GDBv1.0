package com.gutta.gdb.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AdminDtos {
    private AdminDtos() {}

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record BootstrapRequest(
            @NotBlank @Size(min = 32, max = 512) String bootstrapToken,
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record SessionResponse(String username, String role, Instant expiresAt) {}
}
