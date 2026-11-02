package com.gutta.gdb.application;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<GdbApplicationEntity, UUID> {
    Optional<GdbApplicationEntity> findByCode(String code);
    List<GdbApplicationEntity> findAllByOrderByCode();
}
