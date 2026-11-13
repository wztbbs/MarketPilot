package com.cexpilot.platform.plan;

import com.cexpilot.platform.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedPlanExecutionTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recoveryAfterGrantExpiryDistinguishesCompletedEffectsFromUnfinishedWork(boolean allCallsCompleted) {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId); approve(f, runId);
            try (var scope = f.attach(runId)) {
                f.plans.markExecution(plan.id(), PlanExecutionStatus.RUNNING);
                assertThrows(RunSuspended.class, () -> f.gateway.invokePrepared(plan.id(), "create"));
                f.external.done = true; f.external.clock.now = f.external.clock.now.plusSeconds(1);
                f.gateway.process(f.store.calls(runId).get(0).id());
                if (allCallsCompleted) assertTrue(f.gateway.invokePrepared(plan.id(), "assign").ok());
            }
            // 模拟结果已落库但方案尚未收尾的退出窗口，以及晚于授权有效期的恢复。
            f.external.clock.now = f.external.clock.now.plusSeconds(86401);
            try (var scope = f.attach(runId)) {
                if (allCallsCompleted) assertEquals(PlanExecutionStatus.SUCCEEDED, f.executor.execute(plan.id()).status());
                else assertThrows(SecurityException.class, () -> f.executor.execute(plan.id()));
            }
            assertEquals(allCallsCompleted ? "SUCCEEDED" : "PARTIAL", f.plan(runId).data().path("executionStatus").asText());
            assertEquals(1, f.external.submits.get()); assertEquals(allCallsCompleted ? 1 : 0, f.external.assigns.get());
        }
    }

    @Test void approvalRequirementStillComesFromToolDefinitions() {
        var external = new PreparedPlanFixture.Environment(); external.confirmationRequired = false;
        try (var f = new PreparedPlanFixture(PreparedPlanFixture.database(), external)) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId);
            assertEquals("APPROVED", plan.status()); assertEquals("TOOL_POLICY", plan.data().at("/decision/mode").asText());
            assertEquals(1, external.submits.get());
            assertEquals("TOOL_CALL", f.store.require(RUN, runId).data().at("/waitTarget/kind").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"arguments", "callId", "planFingerprint", "identity"})
    void backgroundProcessingChecksApprovedMembershipEvenIfCallHashWasRecomputed(String tamper) {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); approve(f, runId); f.runs.drive(runId);
            var call = f.store.calls(runId).get(0); var data = call.data();
            switch (tamper) {
                case "arguments" -> ((com.fasterxml.jackson.databind.node.ObjectNode) data.get("arguments")).put("name", "unapproved name");
                case "callId" -> data.put("preparedCallId", "assign");
                case "planFingerprint" -> data.put("preparedPlanFingerprint", "forged");
                case "identity" -> ((com.fasterxml.jackson.databind.node.ObjectNode) data.get("context")).put("visitorId", "bob");
                default -> throw new AssertionError(tamper);
            }
            String hash = org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.gateway, "fingerprint", data);
            data.put("fingerprint", hash);
            assertTrue(f.store.update(CALL, call, call.status(), f.external.clock.instant(), null, data));
            f.gateway.process(call.id());
            assertEquals("FAILED", f.store.require(CALL, call.id()).status());
            assertEquals(0, f.external.polls.get()); assertEquals(1, f.external.submits.get()); assertEquals(0, f.external.assigns.get());
        }
    }

    @Test void oneApprovalTwoOperationsRestartOneHourAndBusinessCommit() {
        var db = PreparedPlanFixture.database(); var external = new PreparedPlanFixture.Environment(); String runId, planId, firstCall;
        try (var first = new PreparedPlanFixture(db, external)) {
            var waiting = first.start(); runId = waiting.agentPayload().path("runId").asText(); var plan = first.plan(runId); planId = plan.id();
            assertEquals("WAITING", waiting.turnStatus()); assertEquals(0, external.submits.get()); assertTrue(first.store.calls(runId).isEmpty());
            assertEquals("PLAN_APPROVAL", waiting.agentPayload().at("/waitTarget/kind").asText());
            assertEquals(planId, first.store.require(RUN, runId).data().at("/waitTarget/id").asText());
            first.plans.confirm(planId, "alice", plan.data().path("fingerprint").asText(), true);
            first.plans.confirm(planId, "alice", plan.data().path("fingerprint").asText(), true);
            assertEquals(0, external.submits.get(), "审批接口不能执行工具");
            first.runs.drive(runId);
            var call = first.store.calls(runId).get(0); firstCall = call.id();
            assertEquals("WAITING", call.status()); assertEquals(planId, call.data().path("preparedPlanId").asText());
            assertEquals("WAITING", first.plan(runId).data().path("executionStatus").asText());
            assertEquals(1, external.submits.get()); assertEquals(0, external.assigns.get());
        }
        external.clock.now = external.clock.now.plusSeconds(3600); external.done = true;
        try (var resumed = new PreparedPlanFixture(db, external)) {
            resumed.gateway.process(firstCall); resumed.runs.drive(runId); resumed.runs.drive(runId);
            assertEquals("SUCCEEDED", resumed.store.require(RUN, runId).status());
            assertEquals("APPROVED", resumed.plan(runId).status()); assertEquals("SUCCEEDED", resumed.plan(runId).data().path("executionStatus").asText());
            assertEquals(1, external.submits.get()); assertEquals(1, external.jobs.size()); assertEquals(1, external.assigns.get());
            assertEquals("ticket-1", external.assignedArguments.path("ticketId").asText());
            assertTrue(external.assignedArguments.path("expectedVersion").isIntegralNumber()); assertEquals(1, external.assignedArguments.path("expectedVersion").asInt());
            assertEquals(1, external.preparations.get()); assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM prepared_plan_receipt WHERE run_id=?", Integer.class, runId));
            assertEquals(2, resumed.store.require(RUN, runId).data().at("/budget/tool_nodes").asInt(), "恢复不能重复扣工具预算");
            assertTrue(resumed.store.calls(runId).stream().noneMatch(call -> call.status().equals("CONFIRMATION_REQUIRED")));
            assertEquals(2, resumed.runs.view(runId, "alice").at("/plans/0/calls").size());
        }
    }

    @Test void acceptedButLostResponseRecoversByOriginalInvocationInsteadOfSubmittingAgain() {
        var db = PreparedPlanFixture.database(); var external = new PreparedPlanFixture.Environment(); external.loseSubmitResponse = true;
        String runId, callId;
        try (var first = new PreparedPlanFixture(db, external)) {
            runId = first.start().agentPayload().path("runId").asText(); approve(first, runId); first.runs.drive(runId);
            var call = first.store.calls(runId).get(0); callId = call.id(); assertEquals("UNKNOWN", call.status()); assertFalse(call.data().has("jobId"));
            assertEquals(1, external.jobs.size());
        }
        external.clock.now = external.clock.now.plusSeconds(61);
        try (var resumed = new PreparedPlanFixture(db, external)) {
            resumed.gateway.process(callId); assertEquals(1, external.recovers.get()); assertEquals(1, external.submits.get());
            external.done = true; external.clock.now = external.clock.now.plusSeconds(1);
            resumed.gateway.process(callId); resumed.runs.drive(runId);
            assertEquals("SUCCEEDED", resumed.store.require(RUN, runId).status()); assertEquals(1, external.assigns.get());
        }
    }

    @Test void failureStopsRemainingCallsAndPreservesSuccessfulExternalEffects() {
        var external = new PreparedPlanFixture.Environment(); external.includeNotify = true; external.failAssign = true;
        try (var f = new PreparedPlanFixture(PreparedPlanFixture.database(), external)) {
            String runId = f.start().agentPayload().path("runId").asText(); approve(f, runId); f.runs.drive(runId);
            external.done = true; external.clock.now = external.clock.now.plusSeconds(1);
            f.gateway.process(f.store.calls(runId).get(0).id()); f.runs.drive(runId);
            assertEquals("FAILED", f.store.require(RUN, runId).status()); assertEquals("PARTIAL", f.plan(runId).data().path("executionStatus").asText());
            assertEquals(1, external.jobs.size()); assertEquals(1, external.assigns.get()); assertEquals(0, external.notifies.get());
            assertEquals(2, f.store.calls(runId).size()); assertEquals("NOT_STARTED", f.runs.view(runId, "alice").at("/plans/0/calls/2/status").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reject", "expire"})
    void rejectedOrExpiredPlanResumesWithoutExecutingAnyOperation(String action) {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId);
            if (action.equals("reject")) f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), false);
            else { f.external.clock.now = f.external.clock.now.plusSeconds(86401); f.plans.advance(plan.id()); }
            f.runs.drive(runId);
            assertEquals(action.equals("reject") ? "REJECTED" : "EXPIRED", f.plan(runId).status());
            assertEquals("FAILED", f.store.require(RUN, runId).status()); assertTrue(f.store.calls(runId).isEmpty()); assertEquals(0, f.external.submits.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"permission", "version", "contract"})
    void approvalDoesNotBypassRevalidationAtExecution(String change) {
        var db = PreparedPlanFixture.database(); var external = new PreparedPlanFixture.Environment(); String runId;
        try (var f = new PreparedPlanFixture(db, external)) {
            runId = f.start().agentPayload().path("runId").asText(); approve(f, runId);
        }
        if (change.equals("permission")) external.revoked = true;
        if (change.equals("version")) external.version.set(2);
        if (change.equals("contract")) external.contract = "v2";
        try (var resumed = new PreparedPlanFixture(db, external)) {
            resumed.runs.drive(runId);
            assertEquals("FAILED", resumed.store.require(RUN, runId).status());
            assertEquals(0, external.submits.get()); assertEquals(0, external.assigns.get());
        }
    }

    @Test void legacyToolEntryCannotExecuteAnOperationInPlanApprovalMode() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText();
            try (var scope = f.attach(runId)) {
                var context = new com.cexpilot.platform.tool.ToolContext("prepared-trace", null, java.time.ZoneOffset.UTC, f.external.clock.instant(), "sample", "alice", f.agent.allowedTools());
                assertThrows(SecurityException.class, () -> f.gateway.invoke("sample.create", PreparedPlanFixture.json("{\"name\":\"bypass\",\"version\":1}"), context, "forged"));
                var read = f.gateway.invoke("sample.read", PreparedPlanFixture.json("{}"), context, "prepare-read"); assertTrue(read.ok());
                assertThrows(SecurityException.class, () -> f.gateway.invokePrepared(f.plan(runId).id(), "create"));
            }
            assertEquals(0, f.external.submits.get());
        }
    }

    static void approve(PreparedPlanFixture f, String runId) {
        var plan = f.plan(runId); f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), true);
    }
}
