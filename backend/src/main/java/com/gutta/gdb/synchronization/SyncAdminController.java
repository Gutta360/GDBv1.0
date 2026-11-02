package com.gutta.gdb.synchronization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.gutta.gdb.synchronization.SyncSessionDtos.Session;

@RestController
@RequestMapping("/api/v1/admin/synchronization")
public class SyncAdminController {
    private final JdbcTemplate jdbc;
    public SyncAdminController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping List<Session> recent(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("SELECT session_id,app_id,tenant_id,device_id,started_at,completed_at,uploaded_count,downloaded_count,rejected_count,conflict_count,outcome,error_category FROM gdb_core.sync_sessions ORDER BY started_at DESC LIMIT ?",
                (rs, row) -> new Session(rs.getObject(1, java.util.UUID.class), rs.getObject(2, java.util.UUID.class), rs.getObject(3, java.util.UUID.class), rs.getObject(4, java.util.UUID.class),
                        instant(rs, 5), instant(rs, 6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getString(11), rs.getString(12)), bounded);
    }

    private static Instant instant(ResultSet resultSet, int column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
