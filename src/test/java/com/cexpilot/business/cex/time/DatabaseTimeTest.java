package com.cexpilot.business.cex.time;

import com.cexpilot.business.cex.conversation.ConversationRepository;
import com.cexpilot.business.cex.trace.TraceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTimeTest {
    @Test void legacyDateTimesAreStableAcrossJvmTimeZonesAndHaveExplicitOffsets() {
        TimeZone original=TimeZone.getDefault();
        try {
            for(String jvm:java.util.List.of("UTC","Asia/Shanghai","America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvm));
                var source=new DriverManagerDataSource("jdbc:h2:mem:time_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
                var jdbc=new JdbcTemplate(source);
                jdbc.execute("CREATE TABLE conversation(conversation_id VARCHAR,visitor_id VARCHAR,title VARCHAR,created_at DATETIME,last_active_at DATETIME)");
                jdbc.execute("CREATE TABLE conversation_query(conversation_id VARCHAR,query_no INT,question VARCHAR,answer VARCHAR,trace_id VARCHAR,created_at DATETIME,turn_id VARCHAR,turn_status VARCHAR,agent_type VARCHAR,agent_payload_json VARCHAR)");
                jdbc.execute("CREATE TABLE ask_trace(trace_id VARCHAR,created_at DATETIME,finished_at DATETIME)");
                jdbc.execute("CREATE TABLE trace_event(trace_id VARCHAR,seq INT,created_at DATETIME)");
                jdbc.execute("CREATE TABLE feedback(trace_id VARCHAR,id INT,created_at DATETIME)");
                jdbc.execute("INSERT INTO conversation VALUES('c','v','title',TIMESTAMP '2026-11-03 19:35:43',TIMESTAMP '2026-11-03 19:35:43')");
                jdbc.execute("INSERT INTO conversation_query(conversation_id,query_no,created_at) VALUES('c',1,TIMESTAMP '2026-11-03 19:35:43')");
                jdbc.execute("INSERT INTO ask_trace VALUES('t',TIMESTAMP '2026-11-03 19:35:43',NULL)");
                jdbc.execute("INSERT INTO trace_event VALUES('t',1,TIMESTAMP '2026-11-03 19:35:43')");
                jdbc.execute("INSERT INTO feedback VALUES('t',1,TIMESTAMP '2026-11-03 19:35:43')");
                var expected=OffsetDateTime.parse("2026-11-03T19:35:43+08:00");
                var conversations=new ConversationRepository(jdbc);
                assertEquals(expected,conversations.list("v","",10,0).get(0).lastActiveAt(),jvm);
                assertEquals(LocalDateTime.parse("2026-11-03T19:35:43"),conversations.allQueries("c").get(0).createdAt(),jvm);
                var traces=new TraceRepository(jdbc);
                assertEquals(expected.toString(),traces.findTrace("t").get("created_at"),jvm);
                assertNull(traces.findTrace("t").get("finished_at"));
                assertEquals(expected.toString(),traces.findEvents("t").get(0).get("created_at"));
                assertEquals(expected.toString(),traces.findEventsByTraceIds(java.util.List.of("t")).get("t").get(0).get("created_at"));
                assertEquals(expected.toString(),traces.findFeedbackByTraceIds(java.util.List.of("t")).get("t").get(0).get("created_at"));
                assertEquals(ZoneOffset.ofHours(8),traces.dbNow().getOffset());
                jdbc.execute("SHUTDOWN");
            }
        } finally { TimeZone.setDefault(original); }
    }
    @Test void storageZoneIsConfigurableWithoutReinterpretingMonitorDates() {
        assertEquals("2026-11-03T11:35:43Z",new DatabaseTime("UTC").offset(LocalDateTime.parse("2026-11-03T11:35:43")).toString());
    }
}
