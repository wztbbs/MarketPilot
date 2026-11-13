package com.cexpilot.platform.plan;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.prompt.PromptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

class PreparedEngineBoundaryTest {
    @Test void invalidPlanCannotBeReportedAsSuccessfulExecution() {
        try (var f = new PreparedEngineFixture(EngineKind.PLAN, new PreparedEngineFixture.Script("{\"nodes\":[]}", "{\"nodes\":[]}"))) {
            var result = f.start(); assertEquals("FAILED", result.turnStatus());
            assertTrue(f.base.store.plans(result.agentPayload().path("runId").asText()).isEmpty()); assertEquals(0, f.base.external.submits.get());
        }
    }

    @Test void noProgressDuringReadPreparationDoesNotClaimTheOperationCompleted() {
        var script = new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.READ, "{\"answer\":\"只查询了版本，未执行操作\",\"evidence_refs\":[\"e1\"]}");
        try (var f = new PreparedEngineFixture(EngineKind.REACT, script)) {
            var result = f.start(); assertEquals("FAILED", result.turnStatus());
            assertEquals("NO_PROGRESS", result.agentPayload().path("stop_reason").asText()); assertEquals(1, f.base.external.reads.get()); assertEquals(0, f.base.external.submits.get());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void operationFailureKeepsPartialEffectsAndStopsFurtherPreparation(EngineKind kind) {
        var external = new PreparedPlanFixture.Environment(); external.failAssign = true;
        var script = kind == EngineKind.REACT
                ? new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.PROPOSE, "{\"answer\":\"工单已创建，指派失败\",\"evidence_refs\":[\"e2\"]}")
                : new PreparedEngineFixture.Script(PreparedEngineFixture.PLAN, "{\"answer\":\"工单已创建，指派失败\",\"evidence_refs\":[\"create\"]}");
        try (var f = new PreparedEngineFixture(PreparedPlanFixture.database(), external, kind, script)) {
            String runId = f.start().agentPayload().path("runId").asText(); f.approve(runId); f.runs.drive(runId);
            external.done = true; external.clock.now = external.clock.now.plusSeconds(1);
            var create = f.base.store.calls(runId).stream().filter(call -> call.data().path("tool").asText().equals("sample.create")).findFirst().orElseThrow();
            f.base.gateway.process(create.id()); f.runs.drive(runId);
            assertEquals("FAILED", f.base.store.require(RUN, runId).status()); assertEquals("PARTIAL", f.base.plan(runId).data().path("executionStatus").asText());
            assertEquals(1, external.submits.get()); assertEquals(1, external.assigns.get()); assertEquals(0, external.notifies.get());
            assertEquals(1, f.base.store.plans(runId).size()); assertTrue(script.outputs.isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void changingApprovalModeCannotDowngradeAnExistingRun(EngineKind kind) {
        var db = PreparedPlanFixture.database(); var external = new PreparedPlanFixture.Environment();
        var script = kind == EngineKind.REACT ? new PreparedEngineFixture.Script(PreparedEngineFixture.READ, PreparedEngineFixture.PROPOSE) : new PreparedEngineFixture.Script(PreparedEngineFixture.PLAN);
        String id;
        try (var f = new PreparedEngineFixture(db, external, kind, script)) { id = f.start().agentPayload().path("runId").asText(); f.approve(id); }
        int before = script.calls.get();
        try (var changed = new PreparedEngineFixture(db, external, kind, script, ToolApprovalMode.PER_CALL)) {
            changed.runs.drive(id); assertEquals("FAILED", changed.base.store.require(RUN, id).status());
            assertEquals(0, external.submits.get()); assertEquals(before, script.calls.get());
        }
    }

    @Test void planExplicitDependencyIsRespectedEvenWithoutParameterReference() {
        String plan = "{\"nodes\":[{\"id\":\"notify\",\"tool\":\"sample.notify\",\"args\":{},\"depends_on\":[\"create\"]},{\"id\":\"create\",\"tool\":\"sample.create\",\"args\":{\"name\":\"任务\",\"version\":1}}]}";
        var script = new PreparedEngineFixture.Script(plan, "{\"answer\":\"工单已创建并通知\",\"evidence_refs\":[\"notify\"]}");
        try (var f = new PreparedEngineFixture(EngineKind.PLAN, script)) {
            String id = f.start().agentPayload().path("runId").asText(); f.approve(id); f.runs.drive(id);
            assertEquals(0, f.base.external.notifies.get()); var call = f.base.store.calls(id).get(0);
            assertEquals("sample.create", call.data().path("tool").asText());
            f.base.external.done = true; f.base.external.clock.now = f.base.external.clock.now.plusSeconds(1);
            f.base.gateway.process(call.id()); f.runs.drive(id);
            assertEquals("SUCCEEDED", f.base.store.require(RUN, id).status()); assertEquals(1, f.base.external.notifies.get());
        }
    }

    @Test void prepareClientFailureIsNotARepairableModelOutputError() {
        var original = new IllegalArgumentException("client configuration failed");
        var script = new PreparedEngineFixture.Script(original);
        try (var f = new PreparedEngineFixture(EngineKind.REACT, script)) {
            var result = f.start(); String id = result.agentPayload().path("runId").asText();
            assertEquals("FAILED", result.turnStatus()); assertTrue(f.base.store.plans(id).isEmpty()); assertEquals(0, f.base.external.submits.get());
            assertTrue(f.events.stream().noneMatch(event -> event.eventType().equals("AGENT_REJECTED")));
            assertEquals(1, script.calls.get());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void preparedProtocolTemplatesAreIncludedInAgentVersion(EngineKind kind) {
        try (var f = new PreparedEngineFixture(kind, new PreparedEngineFixture.Script())) {
            String template = kind == EngineKind.REACT ? "react_base_prepare" : "plan_base_prepare";
            var prompts = new PromptStore(new DefaultResourceLoader() {
                @Override public Resource getResource(String path) {
                    return path.equals("classpath:prompts/" + template + ".txt") ? new ByteArrayResource((f.prompts.render(template, Map.of()) + "\n新执行约束").getBytes(StandardCharsets.UTF_8))
                            : path.equals("classpath:prompts/sample_business.txt") ? new ByteArrayResource(f.prompts.render("sample_business", Map.of()).getBytes(StandardCharsets.UTF_8)) : super.getResource(path);
                }
            });
            assertNotEquals(f.agent.promptVersion(), new DeclaredAgent(f.agent.configured(), f.base.tools, prompts).promptVersion());
        }
    }

    @Test void yamlDefaultsToPerCallAndRequiresExplicitPreparedMode() {
        String yaml = "id: sample\nroute: sample\ndescription: sample\nenabled: true\nengine: react\nprompt: sample_business\nallowed_tools: [sample.read]\nexecution: {max_steps: 4}\n";
        var defaults = AgentDefinitionLoader.load(com.cexpilot.platform.tool.HttpToolFixtures.loader(List.of(), List.of(yaml))).get(0);
        assertEquals(ToolApprovalMode.PER_CALL, defaults.toolApprovalMode());
        var prepared = AgentDefinitionLoader.load(com.cexpilot.platform.tool.HttpToolFixtures.loader(List.of(), List.of(yaml + "tool_approval: prepared_plan\n"))).get(0);
        assertEquals(ToolApprovalMode.PREPARED_PLAN, prepared.toolApprovalMode());
        for (String invalid : List.of("none", "false", "''")) assertThrows(IllegalStateException.class,
                () -> AgentDefinitionLoader.load(com.cexpilot.platform.tool.HttpToolFixtures.loader(List.of(), List.of(yaml + "tool_approval: " + invalid + "\n"))));
    }
}
