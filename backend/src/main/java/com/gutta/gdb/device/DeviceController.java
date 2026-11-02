package com.gutta.gdb.device;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.gutta.gdb.device.DeviceDtos.*;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {
    private final DeviceService service;
    public DeviceController(DeviceService service) { this.service = service; }

    @PostMapping("/devices/register") @ResponseStatus(HttpStatus.CREATED)
    RegisterDeviceResponse register(@Valid @RequestBody RegisterDeviceRequest request) { return service.register(request); }

    @PostMapping("/admin/tenants/{tenantId}/enrollment-codes") @ResponseStatus(HttpStatus.CREATED)
    EnrollmentCodeResponse enroll(@PathVariable UUID tenantId) { return service.createEnrollmentCode(tenantId); }

    @GetMapping("/admin/devices") List<DeviceResponse> list() { return service.list(); }

    @PostMapping("/admin/devices/{deviceId}/revoke")
    RevokeDeviceResponse revoke(@PathVariable UUID deviceId) { return service.revoke(deviceId); }

    @PostMapping("/admin/devices/{deviceId}/rotate")
    RotateCredentialResponse rotate(@PathVariable UUID deviceId) { return service.rotate(deviceId); }
}
