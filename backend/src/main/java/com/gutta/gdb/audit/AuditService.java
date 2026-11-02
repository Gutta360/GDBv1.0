package com.gutta.gdb.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogRepository logs;
    public AuditService(AuditLogRepository logs) { this.logs = logs; }
    @Transactional public void record(String actorType, String actorId, UUID appId, UUID tenantId, UUID deviceId,
                                      String action, String outcome, String details) {
        logs.save(new AuditLog(actorType, actorId, appId, tenantId, deviceId, action, outcome,
                details == null ? null : details.substring(0, Math.min(details.length(), 500))));
    }
    @Transactional(readOnly = true) public List<AuditLog> recent(int limit) {
        return logs.findAllByOrderByOccurredAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }
}
