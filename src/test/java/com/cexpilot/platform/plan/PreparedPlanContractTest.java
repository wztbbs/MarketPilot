package com.cexpilot.platform.plan;

import com.cexpilot.platform.run.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

class PreparedPlanContractTest {
    @Test void nonexistentOutputFieldIsRejectedBeforeApproval() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText();
            var calls = new java.util.ArrayList<>(PreparedPlanFixture.proposal(false).calls());
            var args = (ObjectNode) calls.get(1).arguments(); args.put("ticketId", "{{create.data.noSuchField}}");
            calls.set(1, new PreparedToolCall("assign", "sample.assign", args, List.of("create")));
            try (var scope = f.attach(runId)) {
                assertThrows(IllegalArgumentException.class, () -> f.plans.prepare("invalid", new PreparedPlan("invalid reference", calls, 3600)));
            }
            assertEquals(1, f.store.plans(runId).size()); assertEquals(0, f.external.submits.get());
        }
    }

    @Test void nestedReferencesAreAllowedOnlyDuringPreparation() {
        var schema = PreparedPlanFixture.json("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"options\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"version\":{\"type\":\"integer\"},\"values\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}}}}");
        var args = PreparedPlanFixture.json("{\"options\":{\"version\":\"{{create.data.version}}\",\"values\":[\"{{create.data.version}}\"]}}");
        assertTrue(com.cexpilot.platform.tool.ToolArguments.validate(args, schema, true).isEmpty());
        assertFalse(com.cexpilot.platform.tool.ToolArguments.validate(args, schema, false).isEmpty());
    }

    @Test void rejectsCyclesUnknownReferencesAndUndeclaredDependencies() {
        var args = PreparedPlanFixture.json("{}");
        assertThrows(IllegalArgumentException.class, () -> new PreparedPlan("循环", List.of(new PreparedToolCall("a", "x", args, List.of("b")), new PreparedToolCall("b", "x", args, List.of("a"))), 3600));
        assertThrows(IllegalArgumentException.class, () -> new PreparedPlan("未知依赖", List.of(new PreparedToolCall("a", "x", args, List.of("missing"))), 3600));
        assertThrows(IllegalArgumentException.class, () -> new PreparedPlan("未声明引用", List.of(new PreparedToolCall("a", "x", PreparedPlanFixture.json("{\"id\":\"{{b.data.id}}\"}"), List.of()), new PreparedToolCall("b", "x", args, List.of())), 3600));
    }
    @Test void argumentsCannotBeChangedThroughTheOriginalNodeOrAccessor() {
        ObjectNode original = PreparedPlanFixture.JSON.createObjectNode().put("value", 1);
        var call = new PreparedToolCall("a", "sample.read", original, List.of());
        original.put("value", 2); ((ObjectNode) call.arguments()).put("value", 3);
        assertEquals(1, call.arguments().path("value").asInt());
    }
    @Test void sameSlotReusesFrozenProposalButRejectsChanges() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var before = f.plan(runId);
            try (var scope = f.attach(runId)) {
                var replay = f.plans.prepare("stage_1", PreparedPlanFixture.proposal(false)); assertEquals(before.id(), replay.id()); assertEquals(before.data().path("fingerprint"), replay.data().path("fingerprint"));
                var changed = new PreparedPlan("changed", PreparedPlanFixture.proposal(false).calls(), 86400);
                assertThrows(IllegalStateException.class, () -> f.plans.prepare("stage_1", changed));
            }
            assertEquals(1, f.store.plans(runId).size());
        }
    }
    @Test void changedProposalRequiresNewApprovalAndCannotReuseOldDecision() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var old = f.plan(runId);
            PreparedPlanExecutionTest.approve(f, runId);
            try (var scope = f.attach(runId)) {
                f.plans.supersede(old.id());
                var replacement = f.plans.prepare("stage_2", new PreparedPlan("修改后的方案", PreparedPlanFixture.proposal(false).calls(), 86400));
                assertEquals("PENDING", replacement.status()); assertNotEquals(old.data().path("fingerprint"), replacement.data().path("fingerprint"));
                assertThrows(SecurityException.class, () -> f.gateway.invokePrepared(old.id(), "create"));
                assertThrows(SecurityException.class, () -> f.gateway.invokePrepared(replacement.id(), "create"));
            }
            assertEquals("SUPERSEDED", f.store.require(PLAN, old.id()).status()); assertEquals(0, f.external.submits.get());
        }
    }
    @Test void contentTamperingAndWrongApprovalFingerprintAreRejected() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId);
            assertThrows(IllegalArgumentException.class, () -> f.plans.confirm(plan.id(), "alice", "forged", true));
            ((ObjectNode) plan.data().at("/frozen/plan/calls/0/arguments")).put("name", "tampered");
            assertTrue(f.store.update(PLAN, plan, plan.status(), plan.nextAttempt(), null, plan.data()));
            assertThrows(SecurityException.class, () -> f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), true));
            assertEquals(0, f.external.submits.get());
        }
    }
    @Test void startedPlanCannotBeReplacedAndUnknownCallCannotBeAdded() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId); PreparedPlanExecutionTest.approve(f, runId); f.runs.drive(runId);
            try (var scope = f.attach(runId)) {
                assertThrows(IllegalStateException.class, () -> f.plans.supersede(plan.id()));
                assertThrows(SecurityException.class, () -> f.gateway.invokePrepared(plan.id(), "not-approved"));
            }
            assertEquals(1, f.external.submits.get()); assertEquals(0, f.external.notifies.get());
        }
    }
    @Test void waitingTargetSeparatesApprovalFromToolResult() {
        var waiting = RunStep.waitingForApproval("plan");
        assertEquals(RunWaitTarget.Kind.PLAN_APPROVAL, waiting.target().kind()); assertThrows(IllegalStateException.class, waiting::invocationId);
        assertEquals("call", RunStep.waiting("call").invocationId());
    }
}
