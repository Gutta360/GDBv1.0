package com.gutta.gdb.snapshot;

import org.springframework.web.bind.annotation.*;
import java.util.List;

import static com.gutta.gdb.snapshot.SnapshotDtos.SnapshotResponse;

@RestController
@RequestMapping("/api/v1/admin/snapshots")
public class SnapshotAdminController {
    private final SnapshotRepository snapshots;
    public SnapshotAdminController(SnapshotRepository snapshots) { this.snapshots = snapshots; }
    @GetMapping List<SnapshotResponse> list() {
        return snapshots.findAll().stream().filter(item -> "AVAILABLE".equals(item.getStatus())).map(SnapshotResponse::from).toList();
    }
}
