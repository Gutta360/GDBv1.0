package com.gutta.gdb.monitoring;

import com.gutta.gdb.application.ApplicationRepository;
import com.gutta.gdb.audit.AuditService;
import com.gutta.gdb.conflict.ConflictService;
import com.gutta.gdb.device.DeviceRepository;
import com.gutta.gdb.snapshot.SnapshotRepository;
import com.gutta.gdb.tenant.TenantRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/monitoring")
public class MonitoringController {
    private final ApplicationRepository applications;
    private final TenantRepository tenants;
    private final DeviceRepository devices;
    private final SnapshotRepository snapshots;
    private final ConflictService conflicts;
    private final AuditService audit;
    public MonitoringController(ApplicationRepository applications, TenantRepository tenants, DeviceRepository devices,
                                SnapshotRepository snapshots, ConflictService conflicts, AuditService audit) {
        this.applications = applications; this.tenants = tenants; this.devices = devices; this.snapshots = snapshots; this.conflicts = conflicts; this.audit = audit;
    }
    @GetMapping("/dashboard") Dashboard dashboard() {
        return new Dashboard(applications.count(), tenants.countByStatus("ACTIVE"), devices.count(), devices.countByStatus("REVOKED"),
                snapshots.countByStatus("AVAILABLE"), conflicts.openCount(), Instant.now(), "Database health is reported by /actuator/health.");
    }
    @GetMapping("/health") Health health() { return new Health("UP", "Use /actuator/health for database detail."); }
    public record Dashboard(long registeredApplications, long activeTenants, long registeredDevices, long revokedDevices,
                            long availableSnapshots, long openConflicts, Instant generatedAt, String note) {}
    public record Health(String status, String note) {}
}
