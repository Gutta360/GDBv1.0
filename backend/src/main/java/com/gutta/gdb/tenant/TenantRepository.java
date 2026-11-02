package com.gutta.gdb.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByIdAndApplication_Id(UUID id, UUID appId);
    Optional<Tenant> findByApplication_IdAndCode(UUID appId, String code);
    List<Tenant> findAllByOrderByCreatedAtDesc();
    long countByStatus(String status);
}
