package com.gutta.gdb.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSchemaProvisionerTest {
    @Test
    void derivesOnlyControlledSchemaNames() {
        assertEquals("gdb_app_gdc", DatabaseSchemaProvisioner.schemaFor("GDC"));
        assertEquals("gdb_app_laboratory_2", DatabaseSchemaProvisioner.schemaFor("LABORATORY_2"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseSchemaProvisioner.schemaFor("GDC;DROP SCHEMA"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseSchemaProvisioner.schemaFor("1GDC"));
    }
}
