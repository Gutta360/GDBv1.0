package com.gutta.gdb.device;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentCodeRepository extends JpaRepository<EnrollmentCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<EnrollmentCode> findByCodeHash(String codeHash);
}
