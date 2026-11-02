package com.gutta.gdb.snapshot;

import com.gutta.gdb.config.ApiException;
import com.gutta.gdb.config.GdbProperties;
import com.gutta.gdb.security.GdbPrincipal;
import com.gutta.gdb.shared.SecretHashing;
import com.gutta.gdb.device.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static com.gutta.gdb.snapshot.SnapshotDtos.*;

@Service
public class SnapshotService {
    private final SnapshotRepository snapshots;
    private final SnapshotStorage storage;
    private final SecretHashing hashing;
    private final GdbProperties properties;
    private final DeviceRepository devices;
    public SnapshotService(SnapshotRepository snapshots, SnapshotStorage storage, SecretHashing hashing,
                           GdbProperties properties, DeviceRepository devices) {
        this.snapshots = snapshots; this.storage = storage; this.hashing = hashing; this.properties = properties; this.devices = devices;
    }

    @Transactional
    public SnapshotResponse upload(GdbPrincipal principal, UploadRequest request) {
        if (request.formatVersion() != 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SNAPSHOT_FORMAT", "Only snapshot format version 1 is supported.");
        byte[] content;
        try { content = Base64.getDecoder().decode(request.payload()); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SNAPSHOT_ENCODING", "Snapshot payload must be base64."); }
        if (content.length == 0 || content.length != request.contentLength() || content.length > properties.maxSnapshotBytes())
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SNAPSHOT_TOO_LARGE", "Snapshot size is invalid or exceeds the configured limit.");
        if (!hashing.constantTimeEquals(hashing.sha256(content), request.sha256()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH", "Snapshot checksum does not match the uploaded bytes.");
        UUID id = UUID.randomUUID(); String key = storage.store(principal.tenantId(), id, content);
        try {
            Snapshot saved = snapshots.saveAndFlush(new Snapshot(id, principal.appId(), principal.tenantId(), principal.deviceId(), request.formatVersion(),
                    request.createdAt(), content.length, request.sha256(), key, request.recordCounts() == null || request.recordCounts().isBlank() ? "{}" : request.recordCounts(),
                    Instant.now().plus(java.time.Duration.ofDays(30))));
            enforceRetention(principal.tenantId());
            return SnapshotResponse.from(saved);
        } catch (RuntimeException error) { storage.delete(key); throw error; }
    }

    @Transactional(readOnly = true)
    public List<SnapshotResponse> list(GdbPrincipal principal) {
        return snapshots.findAllByTenantIdAndStatusOrderByUploadedAtDesc(principal.tenantId(), "AVAILABLE").stream().map(SnapshotResponse::from).toList();
    }

    @Transactional
    public Download download(GdbPrincipal principal, UUID snapshotId) {
        Snapshot snapshot = snapshots.findByIdAndTenantIdAndStatus(snapshotId, principal.tenantId(), "AVAILABLE")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND", "Snapshot was not found."));
        byte[] bytes = storage.load(snapshot.getStorageKey(), principal.tenantId(), snapshotId);
        String actual = hashing.sha256(bytes);
        if (!hashing.constantTimeEquals(actual, snapshot.getSha256())) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORED_CHECKSUM_MISMATCH", "Stored snapshot integrity verification failed.");
        return new Download(bytes, snapshot.getSha256());
    }

    private void enforceRetention(UUID tenantId) {
        List<Snapshot> all = snapshots.findAllByTenantIdAndStatusOrderByUploadedAtDesc(tenantId, "AVAILABLE");
        all.stream().skip(properties.snapshotRetentionCount()).forEach(snapshot -> { storage.delete(snapshot.getStorageKey()); snapshot.markDeleted(); });
    }
    public record Download(byte[] bytes, String sha256) {}
}
