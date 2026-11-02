package com.gutta.gdb.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseSchemaProvisioner {
    private final JdbcTemplate jdbc;
    public DatabaseSchemaProvisioner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String schemaFor(String appCode) {
        String code = appCode.toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("^[A-Z][A-Z0-9_]{1,39}$")) throw new IllegalArgumentException("Invalid application code");
        return "gdb_app_" + code.toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional
    public void provision(String schema) {
        if (!schema.matches("^gdb_app_[a-z][a-z0-9_]{1,39}$")) throw new IllegalArgumentException("Invalid managed schema");
        jdbc.query("SELECT gdb_core.provision_application_schema(?)", statement -> statement.setString(1, schema), result -> null);
    }

    public static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
