package com.gutta.gdb.snapshot;

import com.gutta.gdb.security.GdbPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.gutta.gdb.snapshot.SnapshotDtos.*;

@RestController
@RequestMapping("/api/v1/snapshots")
public class SnapshotController {
    private final SnapshotService service;
    public SnapshotController(SnapshotService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) SnapshotResponse upload(@AuthenticationPrincipal GdbPrincipal principal, @Valid @RequestBody UploadRequest request) { return service.upload(principal, request); }
    @GetMapping List<SnapshotResponse> list(@AuthenticationPrincipal GdbPrincipal principal) { return service.list(principal); }
    @GetMapping("/{id}") ResponseEntity<byte[]> download(@AuthenticationPrincipal GdbPrincipal principal, @PathVariable UUID id) {
        SnapshotService.Download result = service.download(principal, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Snapshot-Id", id.toString()).header("X-Snapshot-Sha256", result.sha256()).body(result.bytes());
    }
}
