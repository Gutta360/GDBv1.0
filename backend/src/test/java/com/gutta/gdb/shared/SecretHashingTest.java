package com.gutta.gdb.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretHashingTest {
    private final SecretHashing hashing = new SecretHashing();

    @Test
    void hashesAreStableAndTokensAreOpaque() {
        assertEquals(hashing.sha256("gdb"), hashing.sha256("gdb"));
        assertNotEquals(hashing.sha256("gdb"), hashing.sha256("GDB"));
        String token = hashing.randomToken("GDBD_");
        assertTrue(token.startsWith("GDBD_"));
        assertTrue(token.length() > 40);
        assertTrue(hashing.constantTimeEquals(token, token));
        assertFalse(hashing.constantTimeEquals(token, token + "x"));
    }
}
