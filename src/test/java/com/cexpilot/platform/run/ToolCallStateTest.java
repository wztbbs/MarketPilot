package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.EngineKind;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static com.cexpilot.platform.run.DurableStore.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证持久化原状态对应的业务动作，尤其防止恢复或损坏记录被当成首次提交。 */
class ToolCallStateTest {
    @ParameterizedTest @EnumSource(value = ToolCallStatus.class, names = {"WAITING", "UNKNOWN", "EXECUTING"})
    void existingAsyncJobIsPolledWithoutSubmissionOrRecovery(ToolCallStatus previous) throws Exception {
        var external = new DurableExecutionTest.ExternalJob();
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, external, DurableExecutionTest.confirmation(), List.of(), List.of())) {
            var call = pendingCall(f);
            external.invocationId = call.id(); external.done = true;
            call.data().put("jobId", "job-1");
            setStatus(f, call, previous);
            f.gateway.process(call.id());
            assertEquals(1, external.polls.get()); assertEquals(0, external.submits.get()); assertEquals(0, external.recovers.get());
            assertEquals("SUCCEEDED", f.store.require(CALL, call.id()).status());
        }
    }

    @ParameterizedTest @EnumSource(value = ToolCallStatus.class, names = {"UNKNOWN", "EXECUTING"})
    void uncertainAsyncCallWithoutJobIdRecoversInsteadOfSubmitting(ToolCallStatus previous) throws Exception {
        var external = new DurableExecutionTest.ExternalJob();
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, external, DurableExecutionTest.confirmation(), List.of(), List.of())) {
            var call = pendingCall(f);
            setStatus(f, call, previous);
            f.gateway.process(call.id());
            assertEquals(1, external.recovers.get()); assertEquals(0, external.submits.get()); assertEquals(0, external.polls.get());
            var saved = f.store.require(CALL, call.id());
            assertEquals("WAITING", saved.status()); assertEquals("job-1", saved.data().path("jobId").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "blank", "number"})
    void waitingAsyncCallWithInvalidJobIdFailsBeforeAnyExternalCall(String invalid) throws Exception {
        var external = new DurableExecutionTest.ExternalJob();
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, external, DurableExecutionTest.confirmation(), List.of(), List.of())) {
            var call = pendingCall(f);
            if (invalid.equals("blank")) call.data().put("jobId", " ");
            if (invalid.equals("number")) call.data().put("jobId", 1);
            setStatus(f, call, ToolCallStatus.WAITING);
            f.gateway.process(call.id());
            assertInvalidState(f, call.id());
            assertEquals(0, external.submits.get()); assertEquals(0, external.polls.get()); assertEquals(0, external.recovers.get());
            f.clock.advance(61); f.gateway.process(call.id());
            assertInvalidState(f, call.id()); assertEquals(0, external.submits.get()); assertEquals(0, external.recovers.get());
        }
    }

    @Test void synchronousWaitingCallFailsWithoutExecutingEvenWhenIdempotent() throws Exception {
        var executions = new AtomicInteger();
        try (var f = syncFixture(executions, true)) {
            var call = pendingCall(f); call.data().put("jobId", "unexpected-job");
            setStatus(f, call, ToolCallStatus.WAITING);
            f.gateway.process(call.id());
            assertInvalidState(f, call.id()); assertEquals(0, executions.get());
        }
    }

    @ParameterizedTest @CsvSource({
            "UNKNOWN, true, false, true", "EXECUTING, true, false, true",
            "UNKNOWN, false, false, false", "EXECUTING, false, false, false",
            "UNKNOWN, true, true, false", "EXECUTING, true, true, false"
    })
    void syncRecoveryOnlyResubmitsWhenIdempotentAndParentActive(ToolCallStatus previous, boolean idempotent,
                                                               boolean parentEnded, boolean resubmit) throws Exception {
        var executions = new AtomicInteger();
        try (var f = syncFixture(executions, idempotent)) {
            var call = pendingCall(f);
            if (parentEnded) {
                var run = f.store.require(RUN, call.runId()); run.data().putObject("result");
                assertTrue(f.store.update(RUN, run, RunStatus.SUCCEEDED.name(), null, null, run.data()));
            }
            setStatus(f, call, previous);
            f.gateway.process(call.id());
            assertEquals(resubmit ? 1 : 0, executions.get());
            var saved = f.store.require(CALL, call.id());
            assertEquals(resubmit ? "SUCCEEDED" : "UNKNOWN", saved.status());
            assertEquals(resubmit, saved.data().has("result"));
        }
    }

    @Test void readyCallWithExistingJobIdFailsWithoutDuplicatingExternalJob() throws Exception {
        var external = new DurableExecutionTest.ExternalJob();
        try (var f = new DurableExecutionTest.Fixture(EngineKind.REACT, external, DurableExecutionTest.confirmation(), List.of(), List.of())) {
            var call = pendingCall(f); call.data().put("jobId", "existing-job");
            setStatus(f, call, ToolCallStatus.READY);
            f.gateway.process(call.id());
            assertInvalidState(f, call.id());
            assertEquals(0, external.submits.get()); assertEquals(0, external.polls.get()); assertEquals(0, external.recovers.get());
        }
    }

    private static DurableStore.Entry pendingCall(DurableExecutionTest.Fixture f) {
        var result = f.start();
        assertEquals("WAITING", result.turnStatus());
        var call = f.call(result.agentPayload().path("runId").asText());
        assertEquals("CONFIRMATION_REQUIRED", call.status());
        return call;
    }

    private static void setStatus(DurableExecutionTest.Fixture f, DurableStore.Entry call, ToolCallStatus status) {
        assertTrue(f.store.update(CALL, call, status.name(), f.clock.instant(), null, call.data()));
    }

    private static void assertInvalidState(DurableExecutionTest.Fixture f, String id) {
        var saved = f.store.require(CALL, id);
        assertEquals("FAILED", saved.status());
        assertEquals("EXECUTION_ERROR", saved.data().at("/result/status").asText());
    }

    private static DurableExecutionTest.Fixture syncFixture(AtomicInteger executions, boolean idempotent) throws Exception {
        AgentTool tool = new AgentTool() {
            public String name() { return "sample.write"; }
            public ToolResult execute(JsonNode args, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success(DurableExecutionTest.JSON.createObjectNode().put("value", 3));
            }
        };
        var control = new ToolControl(ToolControl.Kind.OPERATION, true, "测试同步操作", List.of(), idempotent, 86400);
        return new DurableExecutionTest.Fixture(EngineKind.REACT, tool, control, List.of(), List.of());
    }
}
