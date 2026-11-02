package com.gutta.gdb.audit;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditAdminController {
    private final AuditService service;
    public AuditAdminController(AuditService service) { this.service = service; }
    @GetMapping List<AuditLog> recent(@RequestParam(defaultValue = "50") int limit) { return service.recent(limit); }
}
