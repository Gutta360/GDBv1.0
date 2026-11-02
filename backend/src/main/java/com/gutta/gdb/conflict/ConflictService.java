package com.gutta.gdb.conflict;

import com.gutta.gdb.security.GdbPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ConflictService {
    private final ConflictRepository conflicts;
    public ConflictService(ConflictRepository conflicts) { this.conflicts = conflicts; }
    @Transactional public Conflict record(GdbPrincipal principal, String entityType, java.util.UUID entityId,
                                          long baseVersion, long currentVersion, java.util.UUID eventId, String payload) {
        return conflicts.save(new Conflict(principal.appId(), principal.tenantId(), principal.deviceId(), entityType,
                entityId, baseVersion, currentVersion, eventId, payload));
    }
    @Transactional(readOnly = true) public List<Conflict> open(int limit) {
        return conflicts.findAllByStatusOrderByCreatedAtDesc("OPEN", PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }
    @Transactional(readOnly = true) public long openCount() { return conflicts.countByStatus("OPEN"); }
    @Transactional(readOnly = true) public long openCount(java.util.UUID appId, java.util.UUID tenantId) {
        return conflicts.countByAppIdAndTenantIdAndStatus(appId, tenantId, "OPEN");
    }
}
