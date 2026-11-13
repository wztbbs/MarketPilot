package com.cexpilot.platform.agent;

import com.cexpilot.platform.context.*;
import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.engine.react.ReactEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.*;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.RunLeaseLostException;
import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 只用脚本模型和内存工具，验证异常不能被错误地送进模型修复／回答降级分支。 */
class ExceptionBoundaryTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CALL = "{\"action\":\"call\",\"tool\":\"sample.read\",\"arguments\":{}}";
    static final String FINISH = "{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}";
    static final String PLAN = "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"sample.read\",\"args\":{}}]}";

    static final class Fixture implements AutoCloseable {
        final List<TraceEvent> events = new ArrayList<>();
        final Deque<Object> responses = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        final DagExecutor dag;
        final IntentRouter router;
        final AgentRegistry agents;
        final ReactEngine react;
        final PlanEngine plan;
        Fixture(EngineKind kind, RuntimeException toolFailure) {
            AgentTool tool = new AgentTool() {
                public String name() { return "sample.read"; }
                public ToolResult execute(JsonNode args, ToolContext context) {
                    if (toolFailure != null) throw toolFailure;
                    return ToolResult.success(JSON.createObjectNode().put("value", 3));
                }
            };
            var schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
            schema.set("properties", JSON.createObjectNode());
            var output = schema.deepCopy();
            output.withObject("/properties").putObject("value").put("type", "number");
            var tools = new ToolRegistry(List.of(tool), List.of(new ToolDefinition(tool.name(), true, "读取", schema, output, null)));
            var prompts = new PromptStore(new DefaultResourceLoader());
            var llm = new LlmCallService((messages, specs) -> {
                calls.incrementAndGet();
                Object response = responses.removeFirst();
                if (response instanceof RuntimeException failure) throw failure;
                return new ChatResponse((String) response, List.of(), 10, 5);
            }, new LlmConfig(), prompts);
            var agent = new DeclaredAgent(new ConfiguredAgentDefinition(new AgentDefinition("sample", "sample", "示例"), true,
                    kind, "plan_agent_answer", Set.of(tool.name()), 4, new QueryExecutionConfig()), tools, prompts);
            agents = new AgentRegistry(List.of(agent)); router = new IntentRouter(llm, prompts);
            dag = new DagExecutor(tools, new DagConfig());
            react = new ReactEngine(tools, prompts, llm, null); plan = new PlanEngine(tools, prompts, llm, null, dag);
            responses.add("{\"route\":\"sample\"}");
        }
        AgentDispatcher dispatcher(AgentContextProvider contexts, DispatchLifecycle lifecycle) {
            return new AgentDispatcher(router, agents, contexts, new QueryExecutionConfig(), new HarnessRolloutConfig(), lifecycle, react, plan);
        }
        AgentExecutionRequest.Builder request() {
            return AgentExecutionRequest.builder("读取", "boundary-trace", events::add)
                    .requestContext(new RequestContext(ZoneOffset.UTC, Instant.now())).visitor("alice");
        }
        ExecutionResult execute() { return dispatcher(new BasicAgentContextProvider(), DispatchLifecycle.none()).execute(request().build()); }
        public void close() { dag.close(); assertNull(QueryBudget.current()); }
    }

    @Test void reactToolArgumentExceptionPropagatesWithoutModelRepair() {
        var original = new IllegalArgumentException("business executor failed");
        try (var f = new Fixture(EngineKind.REACT, original)) {
            f.responses.add(CALL);
            var failure = assertThrows(ExecutionFailure.class, f::execute);
            assertSame(original, failure.getCause());
            assertEquals(2, f.calls.get()); assertEquals(1, failure.counters.toolCallCount());
            assertEquals(1, failure.counters.routerLlmCalls());
            assertTrue(f.events.stream().noneMatch(e -> "AGENT_REJECTED".equals(e.eventType())));
        }
    }

    @Test void invalidClarificationRemainsARepairableModelError() {
        try (var f = new Fixture(EngineKind.REACT, null)) {
            f.responses.add("{\"action\":\"clarify\",\"clarify\":[{\"label\":\"missing key\"}]}");
            f.responses.add("{\"action\":\"clarify\",\"clarify\":[{\"key\":\"choice\",\"label\":\"选择\",\"reason\":\"missing\"}]}");
            assertEquals("NEEDS_CLARIFICATION", f.execute().turnStatus());
            assertEquals(3, f.calls.get());
            assertEquals(1, f.events.stream().filter(e -> "AGENT_REJECTED".equals(e.eventType())).count());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void answerTransportArgumentExceptionPropagatesWithAllUsage(EngineKind kind) {
        var original = new IllegalArgumentException("LLM client configuration failed");
        try (var f = new Fixture(kind, null)) {
            f.responses.add(kind == EngineKind.REACT ? CALL : PLAN);
            if (kind == EngineKind.REACT) f.responses.add(FINISH);
            f.responses.add(original);
            var failure = assertThrows(ExecutionFailure.class, f::execute);
            assertSame(original, failure.getCause());
            assertEquals(1, failure.counters.routerLlmCalls()); assertEquals(1, failure.counters.answerLlmCalls());
            assertEquals(kind == EngineKind.REACT ? 2 : 1, failure.counters.plannerLlmCalls());
            assertEquals(1, failure.counters.toolCallCount());
            assertEquals(f.calls.get(), failure.counters.llmUsages().size());
            assertTrue(f.events.stream().noneMatch(e -> "AGENT_FINISHED".equals(e.eventType())));
        }
    }

    @Test void progressCallbackFailureIsNotFedBackAsModelError() {
        var original = new IllegalArgumentException("progress callback failed");
        try (var f = new Fixture(EngineKind.REACT, null)) {
            f.responses.add(CALL);
            var req = f.request().progress(message -> { if (message.contains("调用")) throw original; }).build();
            var failure = assertThrows(ExecutionFailure.class, () -> f.dispatcher(new BasicAgentContextProvider(), DispatchLifecycle.none()).execute(req));
            assertSame(original, failure.getCause()); assertEquals(2, f.calls.get());
            assertTrue(f.events.stream().noneMatch(e -> "AGENT_REJECTED".equals(e.eventType())));
        }
    }

    @Test void contextFailureStillIncludesCompletedRouterUsage() {
        var original = new IllegalStateException("context unavailable");
        try (var f = new Fixture(EngineKind.REACT, null)) {
            var contexts = new AgentContextProvider() {
                public ObjectNode routerView(String h, JsonNode s, RequestContext r) { return JSON.createObjectNode(); }
                public ObjectNode executionContext(String h, AgentInput i, JsonNode s, RequestContext r) { throw original; }
            };
            var failure = assertThrows(ExecutionFailure.class, () -> f.dispatcher(contexts, DispatchLifecycle.none()).execute(f.request().build()));
            assertSame(original, failure.getCause()); assertEquals(1, failure.counters.routerLlmCalls());
            assertEquals(1, failure.counters.llmUsages().size());
        }
    }

    @Test void handoffCleanupFailureSurvivesRouterUsageEnrichment() {
        var original = new IllegalStateException("executor failed");
        var cleanup = new IllegalStateException("handoff cleanup failed");
        try (var f = new Fixture(EngineKind.REACT, original)) {
            f.responses.add(CALL);
            var lifecycle = new DispatchLifecycle() {
                public void failed(ObjectNode state, String trace, TraceSink sink) { throw cleanup; }
            };
            var failure = assertThrows(ExecutionFailure.class, () -> f.dispatcher(new BasicAgentContextProvider(), lifecycle).execute(f.request().build()));
            assertSame(original, failure.getCause()); assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void lostLeaseStopsEngineAndBypassesHandoffFailure(EngineKind kind) {
        var lost = new RunLeaseLostException("successor owns run"); var failures = new AtomicInteger();
        try (var f = new Fixture(kind, lost)) {
            f.responses.add(kind == EngineKind.REACT ? CALL : PLAN);
            var lifecycle = new DispatchLifecycle() {
                public void failed(ObjectNode state, String trace, TraceSink sink) { failures.incrementAndGet(); }
            };
            assertSame(lost, assertThrows(RunLeaseLostException.class,
                    () -> f.dispatcher(new BasicAgentContextProvider(), lifecycle).execute(f.request().build())));
            assertEquals(0, failures.get());
        }
    }

    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void lostLeaseDuringAnswerIsNotWrappedAsExecutionFailure(EngineKind kind) {
        var lost = new RunLeaseLostException("lease lost before answer");
        try (var f = new Fixture(kind, null)) {
            f.responses.add(kind == EngineKind.REACT ? CALL : PLAN);
            if (kind == EngineKind.REACT) f.responses.add(FINISH);
            f.responses.add(lost);
            assertSame(lost, assertThrows(RunLeaseLostException.class, f::execute));
        }
    }
}
