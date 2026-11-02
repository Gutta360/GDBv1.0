package com.gutta.gdb.snapshot;

import com.gutta.gdb.config.GdbProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Component
public class SnapshotStorage {
    private static final byte[] MAGIC = {'G','D','B','S','1'};
    private final GdbProperties properties;
    private final SecureRandom random = new SecureRandom();
    private SecretKey key;
    private Path root;
    public SnapshotStorage(GdbProperties properties) { this.properties = properties; }

    @PostConstruct
    void initialize() {
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.snapshotMasterKey());
            if (decoded.length != 32) throw new IllegalStateException("GDB_SNAPSHOT_MASTER_KEY must decode to 32 bytes");
            key = new SecretKeySpec(decoded, "AES");
            root = properties.snapshotStorage().toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IllegalArgumentException | IOException error) { throw new IllegalStateException("Snapshot storage could not be initialized", error); }
    }

    public String store(UUID tenantId, UUID snapshotId, byte[] plaintext) {
        String storageKey = tenantId + "/" + snapshotId + ".gdbsnapshot";
        Path target = resolve(storageKey);
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, iv, tenantId, snapshotId).doFinal(plaintext);
            ByteBuffer blob = ByteBuffer.allocate(MAGIC.length + iv.length + encrypted.length).put(MAGIC).put(iv).put(encrypted);
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), snapshotId.toString(), ".tmp");
            try { Files.write(temporary, blob.array()); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary); }
            return storageKey;
        } catch (IOException | java.security.GeneralSecurityException error) { throw new IllegalStateException("Snapshot storage failed", error); }
    }

    public byte[] load(String storageKey, UUID tenantId, UUID snapshotId) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(resolve(storageKey)));
            if (buffer.remaining() < MAGIC.length + 12 + 16) throw new IllegalStateException("Snapshot file is truncated");
            for (byte expected : MAGIC) if (buffer.get() != expected) throw new IllegalStateException("Snapshot file format is invalid");
            byte[] iv = new byte[12]; buffer.get(iv); byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            return cipher(Cipher.DECRYPT_MODE, iv, tenantId, snapshotId).doFinal(encrypted);
        } catch (IOException | java.security.GeneralSecurityException error) { throw new IllegalStateException("Snapshot load failed", error); }
    }
    public void delete(String storageKey) { try { Files.deleteIfExists(resolve(storageKey)); } catch (IOException error) { throw new IllegalStateException("Snapshot cleanup failed", error); } }
    private Cipher cipher(int mode, byte[] iv, UUID tenantId, UUID snapshotId) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD((tenantId + ":" + snapshotId).getBytes(java.nio.charset.StandardCharsets.UTF_8)); return cipher;
    }
    private Path resolve(String key) { Path resolved = root.resolve(key).normalize(); if (!resolved.startsWith(root)) throw new IllegalArgumentException("Invalid snapshot storage key"); return resolved; }
}
