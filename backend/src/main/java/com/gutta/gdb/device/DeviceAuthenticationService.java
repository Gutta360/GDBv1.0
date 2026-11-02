package com.gutta.gdb.device;

import com.gutta.gdb.security.GdbPrincipal;
import com.gutta.gdb.shared.SecretHashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class DeviceAuthenticationService {
    private final DeviceRepository devices;
    private final SecretHashing hashing;
    public DeviceAuthenticationService(DeviceRepository devices, SecretHashing hashing) { this.devices = devices; this.hashing = hashing; }

    @Transactional
    public Optional<GdbPrincipal> authenticate(String rawToken) {
        return devices.findByTokenHashAndStatus(hashing.sha256(rawToken), "ACTIVE")
                .filter(Device::isActive)
                .map(device -> { device.touch(Instant.now()); return new GdbPrincipal(device.getApplication().getId(), device.getApplication().getCode(),
                        device.getTenant().getId(), device.getId(), device.getName()); });
    }
}
