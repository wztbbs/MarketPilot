package com.cexpilot.platform.evidence;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolEvidenceCheckpointTest {
    static final ObjectMapper JSON = new ObjectMapper();
    AgentRunContext run() {
        return new AgentRunContext("question", "", "evidence-trace", event -> {}, null,
                new RequestContext(ZoneOffset.UTC, Instant.now()), null, new EvidenceSession("conversation", "turn"),
                null, JSON.createObjectNode(), "alice", null);
    }
    ToolResult result() { return new ToolResult(true, JSON.createObjectNode(), null, "OK", JSON.createObjectNode()); }

    @Test void optionalStorageOutageIsLoggedWithCauseAndContext() {
        var outage = new DataAccessResourceFailureException("database unavailable");
        ToolEvidenceWriter writer = (c, t, tr, a, tool, d, p, now) -> { throw outage; };
        var logger = (Logger) LoggerFactory.getLogger(ToolEvidenceCheckpoint.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var saved = ToolEvidenceCheckpoint.saveOptional(writer, run(), "sample", "sample.read", "e1", result());
            assertTrue(saved.unavailable()); assertTrue(saved.factId().isEmpty());
            assertEquals(1, logs.list.size());
            var log = logs.list.get(0); assertTrue(log.getFormattedMessage().contains("evidence-trace"));
            assertTrue(log.getFormattedMessage().contains("sample.read"));
            assertEquals(DataAccessResourceFailureException.class.getName(), log.getThrowableProxy().getCause().getClassName());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void programmingFailureIsNotOptionalStorageDegradation() {
        var original = new IllegalArgumentException("writer contract bug");
        ToolEvidenceWriter writer = (c, t, tr, a, tool, d, p, now) -> { throw original; };
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> ToolEvidenceCheckpoint.saveOptional(writer, run(), "sample", "sample.read", "e1", result())));
    }

    @Test void checkpointLeaseLossCannotBeSwallowedByOptionalEvidenceSave() {
        var store = mock(DurableStore.class); var lost = new RunLeaseLostException("lease lost while journaling");
        doThrow(lost).when(store).checkpoint(anyString(), anyLong(), anyString(), any(), any());
        ToolEvidenceWriter writer = (c, t, tr, a, tool, d, p, now) -> Optional.of("fact");
        try (var scope = new RunScope(store, JSON, Clock.systemUTC(), "run", 1)) {
            assertSame(lost, assertThrows(RunLeaseLostException.class,
                    () -> ToolEvidenceCheckpoint.saveOptional(writer, run(), "sample", "sample.read", "e1", result())));
        }
        assertNull(RunScope.current());
    }
}
