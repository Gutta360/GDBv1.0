package com.gutta.gdb.device;

import com.gutta.gdb.application.ApplicationService;
import com.gutta.gdb.application.GdbApplicationEntity;
import com.gutta.gdb.audit.AuditService;
import com.gutta.gdb.config.ApiException;
import com.gutta.gdb.shared.SecretHashing;
import com.gutta.gdb.tenant.Tenant;
import com.gutta.gdb.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.gutta.gdb.device.DeviceDtos.*;

@Service
public class DeviceService {
    private final DeviceRepository devices;
    private final EnrollmentCodeRepository codes;
    private final TenantService tenants;
    private final ApplicationService applications;
    private final SecretHashing hashing;
    private final AuditService audit;

    public DeviceService(DeviceRepository devices, EnrollmentCodeRepository codes, TenantService tenants,
                          ApplicationService applications, SecretHashing hashing, AuditService audit) {
        this.devices = devices; this.codes = codes; this.tenants = tenants; this.applications = applications;
        this.hashing = hashing; this.audit = audit;
    }

    @Transactional
    public EnrollmentCodeResponse createEnrollmentCode(UUID tenantId) {
        Tenant tenant = tenants.require(tenantId);
        if (!tenant.isActive()) throw new ApiException(HttpStatus.CONFLICT, "TENANT_INACTIVE", "Tenant is not active.");
        String raw = hashing.randomToken("GDBE_");
        Instant now = Instant.now();
        codes.save(new EnrollmentCode(UUID.randomUUID(), tenant.getApplication(), tenant, hashing.sha256(raw),
                now.plus(java.time.Duration.ofMinutes(30)), now));
        audit.record("ADMIN", "gdb-admin", tenant.getApplication().getId(), tenant.getId(), null, "ENROLLMENT_CODE_CREATED", "SUCCESS", "Expiring one-use code created.");
        return new EnrollmentCodeResponse(tenant.getApplication().getId(), tenantId, raw, now.plus(java.time.Duration.ofMinutes(30)));
    }

    @Transactional
    public RegisterDeviceResponse register(RegisterDeviceRequest request) {
        EnrollmentCode code = codes.findByCodeHash(hashing.sha256(request.enrollmentCode().trim()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ENROLLMENT_CODE", "Enrollment code is invalid or expired."));
        Instant now = Instant.now();
        if (!code.canUseAt(now)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ENROLLMENT_CODE", "Enrollment code is invalid or expired.");
        code.consume(now);
        String token = hashing.randomToken("GDBD_");
        Device device = devices.save(new Device(UUID.randomUUID(), code.getApplication(), code.getTenant(), request.deviceName().trim(),
                hashing.sha256(token), request.applicationVersion().trim(), request.platform().trim(), now));
        audit.record("DEVICE", device.getId().toString(), device.getApplication().getId(), device.getTenant().getId(), device.getId(), "DEVICE_ENROLLED", "SUCCESS", "Device enrolled.");
        return new RegisterDeviceResponse(device.getApplication().getId(), device.getTenant().getId(), device.getId(), "Bearer", token,
                device.getApplication().getCode(), device.getTenant().getCode(), device.getTenant().getName(), now);
    }

    @Transactional
    public RevokeDeviceResponse revoke(UUID deviceId) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Device was not found."));
        Instant now = Instant.now(); device.revoke(now);
        audit.record("ADMIN", "gdb-admin", device.getApplication().getId(), device.getTenant().getId(), device.getId(), "DEVICE_REVOKED", "SUCCESS", "Device revoked.");
        return new RevokeDeviceResponse(deviceId, device.getStatus(), now);
    }

    @Transactional
    public RotateCredentialResponse rotate(UUID deviceId) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Device was not found."));
        if (!"ACTIVE".equals(device.getStatus()))
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_REVOKED", "A revoked device must be re-enrolled before it can receive credentials.");
        Instant now = Instant.now(); String raw = hashing.randomToken("GDBD_"); device.rotateToken(hashing.sha256(raw), now);
        audit.record("ADMIN", "gdb-admin", device.getApplication().getId(), device.getTenant().getId(), device.getId(), "DEVICE_CREDENTIAL_ROTATED", "SUCCESS", "Previous device credential invalidated.");
        return new RotateCredentialResponse(deviceId, "Bearer", raw, now);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list() { return devices.findAllByOrderByRegisteredAtDesc().stream().map(DeviceResponse::from).toList(); }
}
