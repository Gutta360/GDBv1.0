package com.gutta.gdb.security;

import java.util.UUID;

public record AdminPrincipal(UUID userId, String username, String role) {}
