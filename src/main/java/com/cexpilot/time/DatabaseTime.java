package com.cexpilot.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Legacy conversation/trace DATETIME columns hold database wall time, not UTC instants.
 * Read them without java.sql.Timestamp/JVM timezone conversion; attach their storage zone at the API boundary.
 * Monitor tables use their own explicit UTC mapping and must not use this mapper.
 */
@Component
public final class DatabaseTime {
    private final ZoneId zone;
    public DatabaseTime(@Value("${app.database-time-zone:Asia/Shanghai}") String zone) {
        this.zone = ZoneId.of(zone);
    }
    public static DatabaseTime legacyDefault() { return new DatabaseTime("Asia/Shanghai"); }
    public OffsetDateTime offset(LocalDateTime value) { return value == null ? null : value.atZone(zone).toOffsetDateTime(); }
    public ColumnMapRowMapper rowMapper() {
        return new ColumnMapRowMapper() {
            @Override protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
                int type = rs.getMetaData().getColumnType(index);
                if (type == Types.TIMESTAMP) {
                    OffsetDateTime value = offset(rs.getObject(index, LocalDateTime.class));
                    return value == null ? null : value.toString();
                }
                return super.getColumnValue(rs, index);
            }
        };
    }
}
