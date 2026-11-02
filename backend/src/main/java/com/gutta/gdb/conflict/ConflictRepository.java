package com.gutta.gdb.conflict;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ConflictRepository extends JpaRepository<Conflict, UUID> {
    List<Conflict> findAllByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    long countByStatus(String status);
    long countByAppIdAndTenantIdAndStatus(java.util.UUID appId, java.util.UUID tenantId, String status);
}
