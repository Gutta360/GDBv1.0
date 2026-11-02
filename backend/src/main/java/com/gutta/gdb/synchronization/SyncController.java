package com.gutta.gdb.synchronization;

import com.gutta.gdb.security.GdbPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.gutta.gdb.synchronization.SyncDtos.*;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {
    private final SyncService service;
    public SyncController(SyncService service) { this.service = service; }
    @PostMapping("/push") PushResponse push(@AuthenticationPrincipal GdbPrincipal principal, @Valid @RequestBody PushRequest request) { return service.push(principal, request); }
    @GetMapping("/pull") PullResponse pull(@AuthenticationPrincipal GdbPrincipal principal,
                                             @RequestParam(defaultValue = "0") long after,
                                             @RequestParam(defaultValue = "100") int limit) { return service.pull(principal, after, limit); }
    @GetMapping("/status") StatusResponse status(@AuthenticationPrincipal GdbPrincipal principal) { return service.status(principal); }
}
