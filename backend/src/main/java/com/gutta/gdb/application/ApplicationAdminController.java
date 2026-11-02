package com.gutta.gdb.application;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.gutta.gdb.application.ApplicationDtos.*;

@RestController
@RequestMapping("/api/v1/admin/applications")
public class ApplicationAdminController {
    private final ApplicationService service;
    public ApplicationAdminController(ApplicationService service) { this.service = service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ApplicationResponse create(@Valid @RequestBody CreateApplicationRequest request) { return service.create(request); }
    @GetMapping List<ApplicationResponse> list() { return service.list(); }
    @PostMapping("/{id}/status") ApplicationResponse status(@PathVariable UUID id, @RequestParam String value) {
        return service.setStatus(id, value);
    }
}
