package com.cexpilot.agent;

import com.cexpilot.config.*;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessRolloutTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final RequestContext REQUEST = new RequestContext(ZoneOffset.UTC, Instant.parse("2026-11-07T00:00:00Z"));

    @Test void cohortsAreStableMonotonicAndFailClosedWithoutTrustedVisitor() {
        var policy = new HarnessRolloutConfig();
        policy.setAdaptiveQueryPercent(20); policy.setHttpAgentsPercent(20); policy.setHandoffPercent(20);
        var accepted = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String visitor = "visitor_" + i;
            var first = policy.decide(visitor);
            assertEquals(first, policy.decide(visitor));
            assertEquals(first.adaptiveQuery(), first.httpAgents());
            assertEquals(first.httpAgents(), first.handoff());
            if (first.httpAgents()) accepted.add(visitor);
        }
        assertTrue(accepted.size() > 100 && accepted.size() < 300);
        policy.setHttpAgentsPercent(50);
        for (String visitor : accepted) assertTrue(policy.decide(visitor).httpAgents());
        assertFalse(policy.decide(null).httpAgents()); assertFalse(policy.decide("bad\nidentity").httpAgents());
        policy.setHttpAgentsPercent(0); assertFalse(policy.decide("visitor").httpAgents());
        policy.setHttpAgentsPercent(100); assertTrue(policy.decide("visitor").httpAgents());
    }

    @Test void invalidPercentAndSaltFailBeforeServingRequestsAndVersionIncludesPolicy() {
        var policy = new HarnessRolloutConfig(); var version = policy.version();
        policy.setHandoffPercent(0); assertNotEquals(version, policy.version());
        policy.setHttpAgentsPercent(-1); assertThrows(IllegalArgumentException.class, policy::validate);
        policy.setHttpAgentsPercent(101); assertThrows(IllegalArgumentException.class, policy::validate);
        policy.setHttpAgentsPercent(100); policy.setAdaptiveQueryPercent(101); assertThrows(IllegalArgumentException.class, policy::validate);
        policy.setAdaptiveQueryPercent(100); policy.setHandoffPercent(-1); assertThrows(IllegalArgumentException.class, policy::validate);
        policy.setHandoffPercent(100); policy.setCohortSalt("bad:salt"); assertThrows(IllegalArgumentException.class, policy::validate);
    }

    @Test void perRequestConfigDoesNotMutateGlobalAndCannotOverrideGlobalDisable() {
        var global = new QueryExecutionConfig(); var policy = new HarnessRolloutConfig();
        policy.setAdaptiveQueryPercent(0);
        var effective = policy.queryConfig(global, policy.decide("visitor"));
        assertFalse(effective.isAdaptiveEnabled()); assertTrue(global.isAdaptiveEnabled());
        assertEquals(global.getMaxModelCalls(), effective.getMaxModelCalls());
        global.setAdaptiveEnabled(false); policy.setAdaptiveQueryPercent(100);
        assertFalse(policy.queryConfig(global, policy.decide("visitor")).isAdaptiveEnabled());
    }

    ConversationAgent agent(String route, HarnessRolloutConfig.Group group, AtomicReference<Boolean> executed) {
        return new ConversationAgent() {
            public AgentDefinition definition() { return new AgentDefinition(route, route, "测试能力"); }
            public String promptVersion() { return "test"; }
            public HarnessRolloutConfig.Group rolloutGroup() { return group; }
            public ExecutionResult execute(AgentRunContext run) {
                executed.set(QueryBudget.current().config().isAdaptiveEnabled());
                return new ExecutionResult("完成", JSON.createArrayNode(), 0, 0, 0, 0);
            }
        };
    }

    @Test void excludedHttpAgentIsHiddenFromRouterAndCannotExecuteEvenIfModelKeepsSelectingIt() {
        var calls = new ArrayList<List<ChatMessage>>();
        LlmClient llm = (messages, tools) -> { calls.add(List.copyOf(messages)); return new ChatResponse("{\"route\":\"external\"}", List.of(), 10, 5); };
        var prompts = new PromptStore(new DefaultResourceLoader());
        var policy = new HarnessRolloutConfig(); policy.setHttpAgentsPercent(0);
        var ran = new AtomicReference<Boolean>();
        var registry = new AgentRegistry(List.of(agent("query", HarnessRolloutConfig.Group.CORE, new AtomicReference<>()),
                agent("external", HarnessRolloutConfig.Group.HTTP_AGENT, ran)));
        var dispatcher = new AgentDispatcher(new IntentRouter(new PlanningLlm(llm, new LlmConfig(), prompts), prompts),
                registry, new AgentContextAssembler(), new QueryExecutionConfig(), policy, new AnalysisMonitorHandoff());
        var events = new ArrayList<TraceEvent>();
        var result = dispatcher.execute("外部问题", "", "trace", events::add, null, REQUEST, null, null,
                null, new EvidenceSession("c", "turn"), JSON.createObjectNode(), r -> {}, "visitor");
        assertEquals("clarify", result.agentType()); assertEquals(2, result.routerLlmCalls()); assertNull(ran.get());
        assertFalse(calls.get(0).get(0).content().contains("external:"));
        var rollout = events.stream().filter(e -> "HARNESS_ROLLOUT".equals(e.eventType())).findFirst().orElseThrow();
        assertTrue(rollout.outputJson().contains("\"http_agents\":false"));
        assertFalse(rollout.outputJson().contains("visitor"));
    }

    @Test void executionGuardAlsoRejectsUnavailableRouteFromInjectedRouter() {
        var router = mock(IntentRouter.class);
        when(router.route(any(), any(), any(), any(), any())).thenReturn(new IntentRouter.Decision("external", null, List.of()));
        var policy = new HarnessRolloutConfig(); policy.setHttpAgentsPercent(0);
        var ran = new AtomicReference<Boolean>();
        var dispatcher = new AgentDispatcher(router, new AgentRegistry(List.of(agent("external", HarnessRolloutConfig.Group.HTTP_AGENT, ran))),
                new AgentContextAssembler(), new QueryExecutionConfig(), policy, new AnalysisMonitorHandoff());
        assertThrows(ExecutionFailure.class, () -> dispatcher.execute("问题", "", "t", e -> {}, null, REQUEST,
                null, null, null, null, null, r -> {}, "visitor"));
        assertNull(ran.get()); assertNull(QueryBudget.current());
    }

    @Test void adaptiveGateAppliesToExecutionWithoutAffectingNextRequestOrCoreRoutes() {
        var router = mock(IntentRouter.class);
        when(router.route(any(), any(), any(), any(), any())).thenReturn(new IntentRouter.Decision("query", null, List.of()));
        var policy = new HarnessRolloutConfig(); policy.setAdaptiveQueryPercent(0); policy.setHttpAgentsPercent(0);
        var global = new QueryExecutionConfig(); var ran = new AtomicReference<Boolean>();
        var dispatcher = new AgentDispatcher(router, new AgentRegistry(List.of(agent("query", HarnessRolloutConfig.Group.CORE, ran))),
                new AgentContextAssembler(), global, policy, new AnalysisMonitorHandoff());
        dispatcher.execute("问题", "", "t1", e -> {}, null, REQUEST, null, null, null, null, null, r -> {}, "visitor");
        assertEquals(false, ran.get()); assertTrue(global.isAdaptiveEnabled()); assertNull(QueryBudget.current());
        policy.setAdaptiveQueryPercent(100);
        dispatcher.execute("问题", "", "t2", e -> {}, null, REQUEST, null, null, null, null, null, r -> {}, "visitor");
        assertEquals(true, ran.get()); assertNull(QueryBudget.current());
    }
}
