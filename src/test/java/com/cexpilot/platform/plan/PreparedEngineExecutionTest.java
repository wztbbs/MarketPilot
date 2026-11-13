package com.cexpilot.platform.plan;

import com.cexpilot.platform.agent.EngineKind;
import com.cexpilot.platform.run.DurableStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

class PreparedEngineExecutionTest {
    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void frozenAndDependentArgumentsKeepDecimalPrecisionAcrossRestart(EngineKind kind) {
        var external = new PreparedPlanFixture.Environment(); external.scalar = new java.math.BigDecimal("1.12345678901234567890123456789");
        var db = PreparedPlanFixture.database();
        var script = kind == EngineKind.REACT
                ? new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.PROPOSE, "{\"action\":\"finish\",\"evidence_refs\":[\"e3\"]}", "{\"answer\":\"完成\",\"evidence_refs\":[\"e3\"]}")
                : new PreparedEngineFixture.Script(PreparedEngineFixture.PLAN.replace("\"version\":1", "\"version\":" + external.scalar.toPlainString()), "{\"answer\":\"完成\",\"evidence_refs\":[\"assign\"]}");
        String runId, callId;
        try (var first = new PreparedEngineFixture(db, external, kind, script)) {
            runId = first.start().agentPayload().path("runId").asText(); first.approve(runId); first.runs.drive(runId);
            assertEquals(0, external.scalar.compareTo(external.submittedArguments.path("version").decimalValue()));
            callId = first.base.store.calls(runId).stream().filter(call -> call.data().path("tool").asText().equals("sample.create")).findFirst().orElseThrow().id();
        }
        external.done = true; external.clock.now = external.clock.now.plusSeconds(3600);
        try (var resumed = new PreparedEngineFixture(db, external, kind, script)) {
            resumed.base.gateway.process(callId); resumed.runs.drive(runId);
            assertEquals("SUCCEEDED", resumed.base.store.require(RUN, runId).status());
            assertEquals(0, external.scalar.compareTo(external.assignedArguments.path("expectedVersion").decimalValue()));
            var factInput = script.messages.get(kind == EngineKind.REACT ? 2 : 1).get(1).content();
            assertTrue(factInput.contains(external.scalar.toPlainString()), "交给模型的真实结果也必须保留十进制精度");
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void enginesFreezeWholePlanAndResumeWithoutReplanningOrRepeatingReads(EngineKind kind) {
        var db = PreparedPlanFixture.database(); var external = new PreparedPlanFixture.Environment();
        var script = kind == EngineKind.REACT
                ? new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.PROPOSE, "{\"action\":\"finish\",\"evidence_refs\":[\"e3\"]}", "{\"answer\":\"工单已创建并指派\",\"evidence_refs\":[\"e3\"]}")
                : new PreparedEngineFixture.Script(PreparedEngineFixture.PLAN, "{\"answer\":\"工单已创建并指派\",\"evidence_refs\":[\"assign\"]}");
        String runId, firstCall;
        try (var first = new PreparedEngineFixture(db, external, kind, script)) {
            var result = first.start(); assertEquals("WAITING", result.turnStatus()); runId = result.agentPayload().path("runId").asText();
            var frozen = first.base.plan(runId); assertEquals("PENDING", frozen.status()); assertEquals(2, first.base.plans.plan(frozen).calls().size());
            assertEquals(0, external.submits.get()); assertEquals(0, external.assigns.get());
            assertEquals(kind == EngineKind.REACT ? 2 : 1, script.calls.get()); assertEquals(kind == EngineKind.REACT ? 1 : 0, external.reads.get());
            first.approve(runId); first.runs.drive(runId);
            assertEquals(1, external.submits.get()); assertEquals(kind == EngineKind.REACT ? 2 : 1, script.calls.get());
            firstCall = first.base.store.calls(runId).stream().filter(call -> call.data().path("tool").asText().equals("sample.create")).findFirst().orElseThrow().id();
            assertEquals("WAITING", first.base.store.require(CALL, firstCall).status());
        }
        external.done = true; external.clock.now = external.clock.now.plusSeconds(3600);
        try (var resumed = new PreparedEngineFixture(db, external, kind, script)) {
            resumed.base.gateway.process(firstCall); resumed.runs.drive(runId);
            var result = resumed.runs.view(runId, "alice"); assertEquals("SUCCEEDED", result.path("status").asText(), result.toPrettyString());
            assertEquals("工单已创建并指派", result.at("/result/answer").asText());
            assertEquals(1, external.submits.get()); assertEquals(1, external.assigns.get()); assertEquals(kind == EngineKind.REACT ? 1 : 0, external.reads.get());
            assertEquals(kind == EngineKind.REACT ? 4 : 2, script.calls.get()); assertTrue(script.outputs.isEmpty());
            assertEquals(kind == EngineKind.REACT ? 3 : 2, resumed.base.store.require(RUN, runId).data().at("/budget/tool_nodes").asInt());
            assertEquals(script.calls.get(), resumed.base.store.require(RUN, runId).data().at("/budget/model_calls").asInt());
            assertEquals(1, resumed.base.store.plans(runId).size());
            assertTrue(script.messages.get(0).get(0).content().contains("只处理模拟工单"));
            if (kind == EngineKind.REACT) assertTrue(script.messages.get(2).get(1).content().contains("ticket-1"), "下一阶段只能观察真实操作结果");
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void rejectingPlanDoesNotExecuteOperationsOrCallModelAgain(EngineKind kind) {
        var script = kind == EngineKind.REACT ? new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.PROPOSE) : new PreparedEngineFixture.Script(PreparedEngineFixture.PLAN);
        try (var f = new PreparedEngineFixture(kind, script)) {
            var initial = f.start(); String runId = initial.agentPayload().path("runId").asText(); var frozen = f.base.plan(runId); int calls = script.calls.get();
            f.base.plans.confirm(frozen.id(), "alice", frozen.data().path("fingerprint").asText(), false); f.runs.drive(runId);
            assertEquals("FAILED", f.base.store.require(RUN, runId).status()); assertEquals(0, f.base.external.submits.get()); assertEquals(calls, script.calls.get());
            assertTrue(f.runs.view(runId, "alice").at("/result/answer").asText().contains("拒绝"));
        }
    }

    @Test void reactAdditionalOperationNeedsAnotherPlanApprovalAfterRealResult() {
        String first = "{\"action\":\"propose\",\"summary\":\"先创建工单\",\"calls\":[{\"id\":\"create\",\"tool\":\"sample.create\",\"arguments\":{\"name\":\"任务\",\"version\":1}}]}";
        String second = "{\"action\":\"propose\",\"summary\":\"根据真实工单指派人员\",\"calls\":[{\"id\":\"assign\",\"tool\":\"sample.assign\",\"arguments\":{\"ticketId\":{\"$ref\":\"e1#/id\"},\"expectedVersion\":{\"$ref\":\"e1#/version\"},\"assignee\":\"张三\"}}]}";
        var script = new PreparedEngineFixture.Script(first, second, "{\"action\":\"finish\",\"evidence_refs\":[\"e2\"]}", "{\"answer\":\"已创建并指派\",\"evidence_refs\":[\"e2\"]}");
        try (var f = new PreparedEngineFixture(EngineKind.REACT, script)) {
            String runId = f.start().agentPayload().path("runId").asText(); f.approve(runId); f.runs.drive(runId);
            f.base.external.done = true; f.base.external.clock.now = f.base.external.clock.now.plusSeconds(1);
            var create = f.base.store.calls(runId).get(0); f.base.gateway.process(create.id()); f.runs.drive(runId);
            assertEquals(2, f.base.store.plans(runId).size()); assertEquals("WAITING", f.base.store.require(RUN, runId).status());
            assertEquals(0, f.base.external.assigns.get()); assertEquals(1, f.base.external.submits.get()); assertEquals(2, script.calls.get());
            f.approve(runId); f.runs.drive(runId);
            assertEquals("SUCCEEDED", f.base.store.require(RUN, runId).status(), f.runs.view(runId, "alice").toPrettyString());
            assertEquals(1, f.base.external.assigns.get()); assertEquals(1, f.base.external.submits.get()); assertEquals("ticket-1", f.base.external.assignedArguments.path("ticketId").asText());
            assertTrue(f.base.store.plans(runId).stream().allMatch(plan -> plan.status().equals("APPROVED") && plan.data().path("executionStatus").asText().equals("SUCCEEDED")));
        }
    }

    @Test void reactOperationCallDuringPreparationIsRejectedBeforeAnySideEffect() {
        String invalid = "{\"action\":\"call\",\"tool\":\"sample.create\",\"arguments\":{\"name\":\"任务\",\"version\":1}}";
        String valid = "{\"action\":\"propose\",\"summary\":\"创建工单\",\"calls\":[{\"id\":\"create\",\"tool\":\"sample.create\",\"arguments\":{\"name\":\"任务\",\"version\":1}}]}";
        var script = new PreparedEngineFixture.Script(invalid, valid);
        try (var f = new PreparedEngineFixture(EngineKind.REACT, script)) {
            var result = f.start(); assertEquals("WAITING", result.turnStatus()); assertEquals(0, f.base.external.submits.get());
            assertEquals(1, f.events.stream().filter(event -> event.eventType().equals("AGENT_REJECTED")).count());
            assertTrue(f.base.store.calls(result.agentPayload().path("runId").asText()).isEmpty());
        }
    }
}
