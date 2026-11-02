package com.gutta.gdb.snapshot;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository extends JpaRepository<Snapshot, UUID> {
    List<Snapshot> findAllByTenantIdAndStatusOrderByUploadedAtDesc(UUID tenantId, String status);
    Optional<Snapshot> findByIdAndTenantIdAndStatus(UUID id, UUID tenantId, String status);
    long countByStatus(String status);
}
