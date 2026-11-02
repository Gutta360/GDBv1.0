package com.gutta.gdb.security;

import java.util.UUID;

public record GdbPrincipal(UUID appId, String appCode, UUID tenantId, UUID deviceId, String deviceName) {}
