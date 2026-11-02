package com.gutta.gdb.conflict;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/conflicts")
public class ConflictAdminController {
    private final ConflictService service;
    public ConflictAdminController(ConflictService service) { this.service = service; }
    @GetMapping List<Conflict> list(@RequestParam(defaultValue = "50") int limit) { return service.open(limit); }
}
