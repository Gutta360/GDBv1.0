package com.gutta.gdb.synchronization;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SyncDtosTest {
    @Test
    void genericEnvelopeRoundTripsWithoutBusinessTypes() throws Exception {
        UUID id = UUID.randomUUID();
        SyncDtos.SyncEvent event = new SyncDtos.SyncEvent(id, "ORDER", id, "CREATE", 0, 1, 1,
                JsonMapper.builder().build().readTree("{\"amount\":12}"), Instant.parse("2026-11-01T00:00:00Z"));
        SyncDtos.PushRequest request = new SyncDtos.PushRequest(1, "GDC", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(event));
        String json = JsonMapper.builder().build().writeValueAsString(request);
        assertTrue(json.contains("ORDER"));
        assertEquals(1, request.events().getFirst().entityVersion());
    }
}
