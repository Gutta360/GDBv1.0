package com.gutta.gdb.tenant;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.gutta.gdb.tenant.TenantDtos.*;

@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantAdminController {
    private final TenantService service;
    public TenantAdminController(TenantService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) TenantResponse create(@Valid @RequestBody CreateTenantRequest request) { return service.create(request); }
    @GetMapping List<TenantResponse> list() { return service.list(); }
}
