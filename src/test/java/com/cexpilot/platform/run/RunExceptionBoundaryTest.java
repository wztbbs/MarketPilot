package com.cexpilot.platform.run;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cexpilot.platform.agent.EngineKind;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.ToolControl;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunExceptionBoundaryTest {
    @Test void systemFailureBeforeSubmissionIsFailedRatherThanDeniedOrUnknown() throws Exception {
        var executions = new AtomicInteger(); var authorizations = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            public String name() { return "sample.write"; }
            public ToolResult execute(JsonNode args, ToolContext context) { executions.incrementAndGet(); return ToolResult.success(DurableExecutionTest.JSON.createObjectNode()); }
        };
        var control = new ToolControl(ToolControl.Kind.OPERATION, false, "操作", List.of(), true, 86400);
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, tool, control, List.of((d, a, c) -> {
            if (authorizations.incrementAndGet() == 2) throw new IllegalStateException("policy service unavailable");
        }), List.of())) {
            f.responses.clear();
            f.responses.add("{\"action\":\"call\",\"tool\":\"sample.write\",\"arguments\":{\"version\":1}}");
            f.responses.add("{\"action\":\"clarify\",\"clarify\":[{\"key\":\"choice\",\"label\":\"选择\",\"reason\":\"missing\"}]}");
            var result = f.start(); var call = f.call(result.agentPayload().path("runId").asText());
            assertEquals("FAILED", call.status()); assertEquals("EXECUTION_ERROR", call.data().at("/result/status").asText());
            assertEquals(0, executions.get());
        }
    }

    @Test void exceptionAfterExternalSubmissionRemainsUnknownWithOriginalCauseLogged() throws Exception {
        var original = new IllegalArgumentException("remote executor failed after accepting");
        AgentTool tool = new AgentTool() {
            public String name() { return "sample.write"; }
            public ToolResult execute(JsonNode args, ToolContext context) { throw original; }
        };
        var control = new ToolControl(ToolControl.Kind.OPERATION, false, "操作", List.of(), false, 86400);
        var logger = (Logger) LoggerFactory.getLogger(com.cexpilot.platform.tool.control.ToolGateway.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, tool, control, List.of(), List.of())) {
            var result = f.start(); var call = f.call(result.agentPayload().path("runId").asText());
            assertEquals("UNKNOWN", call.status()); assertFalse(call.data().has("result"));
            var log = logs.list.stream().filter(e -> e.getThrowableProxy() != null).findFirst().orElseThrow();
            assertTrue(log.getFormattedMessage().contains(call.id()));
            assertEquals(original.getClass().getName(), log.getThrowableProxy().getCause().getClassName());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void failureWhileSavingFailureCannotReplaceOriginalExecutionException() {
        var store = mock(DurableStore.class); var clock = Clock.systemUTC();
        var original = new IllegalStateException("parent run lookup failed");
        var secondary = new IllegalArgumentException("call lookup failed while handling failure");
        var data = DurableExecutionTest.JSON.createObjectNode().put("expiresAt", clock.instant().plusSeconds(3600).toString());
        var entry = new DurableStore.Entry("call", "alice", "run", "READY", 0, 1, clock.instant(), null, data);
        when(store.claim(eq(DurableStore.Kind.CALL), eq("call"), any(), any())).thenReturn(entry);
        when(store.require(DurableStore.Kind.RUN, "run")).thenThrow(original);
        when(store.require(DurableStore.Kind.CALL, "call")).thenThrow(secondary);
        try (var gateway = new com.cexpilot.platform.tool.control.ToolGateway(mock(ToolRegistry.class), store,
                DurableExecutionTest.JSON, clock, List.of(), List.of())) {
            assertSame(original, assertThrows(IllegalStateException.class, () -> gateway.process("call")));
            assertArrayEquals(new Throwable[]{secondary}, original.getSuppressed());
        }
    }

    @Test void backgroundFailureIsLoggedWithIdentityAndDoesNotBlockNextScan() throws Exception {
        var store = mock(DurableStore.class); var runs = mock(RunCoordinator.class);
        var tools = mock(com.cexpilot.platform.tool.control.ToolGateway.class);
        when(store.due(eq(DurableStore.Kind.RUN), any(), anyInt())).thenReturn(List.of("retry-run"));
        when(store.due(eq(DurableStore.Kind.CALL), any(), anyInt())).thenReturn(List.of());
        var attempts = new AtomicInteger();
        var original = new IllegalStateException("database temporarily unavailable");
        doAnswer(invocation -> { if (attempts.incrementAndGet() == 1) throw original; return null; }).when(runs).drive("retry-run");
        var logger = (Logger) LoggerFactory.getLogger(RunReconciler.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try (var reconciler = new RunReconciler(store, tools, runs, Clock.systemUTC())) {
            reconciler.scan();
            await(() -> attempts.get() == 1 && ((java.util.Set<?>) org.springframework.test.util.ReflectionTestUtils.getField(reconciler, "local")).isEmpty());
            assertEquals(1, logs.list.size()); assertTrue(logs.list.get(0).getFormattedMessage().contains("retry-run"));
            assertEquals(original.getClass().getName(), logs.list.get(0).getThrowableProxy().getClassName());
            reconciler.scan(); await(() -> attempts.get() == 2);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
