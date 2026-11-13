package com.cexpilot.business.cex.handoff;
import com.cexpilot.business.cex.conversation.ContextFixtures;

import com.cexpilot.business.cex.context.AgentContextAssembler;
import com.cexpilot.platform.agent.AgentDefinition;
import com.cexpilot.platform.agent.AgentDispatcher;
import com.cexpilot.platform.agent.AgentExecutionRequest;
import com.cexpilot.platform.agent.AgentRegistry;
import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.agent.ConversationAgent;
import com.cexpilot.platform.agent.EngineKind;
import com.cexpilot.platform.agent.IntentRouter;

import com.cexpilot.platform.execution.config.HarnessRolloutConfig;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.business.cex.conversation.PlanningContext;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.business.cex.metric.EffectiveTarget;
import com.cexpilot.business.cex.metric.ConditionOrigin;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.platform.execution.TurnCounters;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.observability.TraceSink;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisMonitorHandoffTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final AnalysisMonitorHandoff handoffs = new AnalysisMonitorHandoff();
    final EvidenceSession session = new EvidenceSession("c", "new_turn");
    PlanningContext input(String conversation, int count) {
        var targets = new ArrayList<EffectiveTarget>();
        for (int i = 0; i < count; i++) targets.add(new EffectiveTarget("m" + i, "metric_" + i, i % 2 == 0 ? "binance" : "okx",
                ConditionOrigin.EXPLICIT, "price.close", "range_statistic",
                JSON.createObjectNode().put("market_type", "spot").put("base", "BTC").put("quote", "USDT"),
                ConditionOrigin.DEFAULT, JSON.createObjectNode().put("type", "absolute_range").put("start", "historical_date"),
                "5m", ConditionOrigin.DEFAULT, false, ConditionOrigin.DEFAULT, null, null, null, "USDT", "test", "v1"));
        var frame = com.cexpilot.business.cex.conversation.ContextFixtures.version("frame", conversation, 7, "source_turn", "历史价格多少", "2026-11-06T00:00:00Z", "UTC", targets, JSON.createObjectNode().put("value", 123456), "SUCCEEDED");
        return new PlanningContext("不应交接的历史事实", frame);
    }
    ObjectNode context() {
        var context = JSON.createObjectNode().put("current_agent", "query");
        context.putObject("query_task").put("query_context", "private_historical_context");
        return context;
    }

    @Test void handoffProjectsOnlyConditionsAndPreservesSourceVersionWithoutMutableAliases() {
        var context = context(); var input = input("c", 2); var events = new ArrayList<TraceEvent>();
        var handoff = handoffs.prepare("monitor", input, session, context, "trace", events::add);
        assertEquals("frame", handoff.path("source_task_id").asText()); assertEquals(7, handoff.path("source_task_version").asLong());
        assertEquals("source_turn", handoff.path("source_turn_id").asText()); assertTrue(handoff.path("requires_confirmation").asBoolean());
        assertEquals(2, handoff.path("targets").size()); assertEquals("spot", handoff.at("/targets/0/instrument/market_type").asText());
        assertFalse(handoff.toString().contains("historical_date")); assertFalse(handoff.toString().contains("123456"));
        assertFalse(handoff.toString().contains("private_historical_context")); assertFalse(context.has("query_task"));
        ((ObjectNode)handoff.at("/targets/0/instrument")).put("base", "ETH");
        assertEquals("BTC", context.at("/handoff/targets/0/instrument/base").asText());
        assertEquals("BTC", input.activeDefinition().targets().get(0).instrument().path("base").asText());
        assertEquals("AGENT_HANDOFF", events.get(0).eventType());
    }

    @Test void foreignConversationCannotSupplyConditionsAndLargeSelectionIsExplicitlyTruncated() {
        var context = context(); var events = new ArrayList<TraceEvent>();
        assertThrows(IllegalArgumentException.class, () -> handoffs.prepare("monitor", input("foreign", 1), session, context, "t", events::add));
        assertFalse(context.has("query_task")); assertFalse(context.has("handoff"));
        assertTrue(events.get(0).outputJson().contains("CONVERSATION_MISMATCH"));
        var bounded = handoffs.prepare("monitor", input("c", 10), session, context(), "t", e -> {});
        assertEquals(10, bounded.path("target_count").asInt()); assertTrue(bounded.path("targets_truncated").asBoolean());
        assertEquals(8, bounded.path("targets").size());
    }

    @Test void noHandoffForQueryMonitorContinuationOrMissingSource() {
        assertNull(handoffs.prepare("query", input("c", 1), session, context(), "t", e -> fail()));
        assertNull(handoffs.prepare("monitor", input("c", 1), session, context().put("current_agent", "monitor"), "t", e -> fail()));
        assertNull(handoffs.prepare("monitor", null, session, context(), "t", e -> fail()));
    }

    @Test void completionKeepsCardEvidenceAndUsageAndRecordsClarificationAsAnOutcome() {
        var handoff = handoffs.prepare("monitor", input("c", 1), session, context(), "t", e -> {});
        var card = JSON.createObjectNode().put("status", "PENDING_CONFIRMATION");
        card.putObject("confirmation").put("ruleId", "rule").put("specHash", "hash");
        var base = new ExecutionResult("核对草稿", JSON.createArrayNode(), 0, 0, 10, 5).withAgent("monitor", card);
        var completed = handoffs.complete(base, handoff, "t", e -> {});
        assertEquals(card.path("confirmation"), completed.agentPayload().path("confirmation"));
        assertEquals("PENDING_CONFIRMATION", completed.agentPayload().at("/handoff/outcome").asText());
        assertFalse(card.has("handoff")); assertEquals(base.promptTokens(), completed.promptTokens());
        var clarify = new ExecutionResult("什么阈值？", JSON.createArrayNode(), 0, 0, 10, 5, "NEEDS_CLARIFICATION", "什么阈值？")
                .withAgent("monitor", JSON.createObjectNode().put("status", "NEEDS_CLARIFICATION"));
        assertEquals("NEEDS_CLARIFICATION", handoffs.complete(clarify, handoff, "t", e -> {}).agentPayload().at("/handoff/outcome").asText());
    }

    @Test void dispatcherPassesOriginalRequestToMonitorAndAttachesHandoffOnlyWhenEnabled() {
        for (int percent : List.of(0, 100)) {
            var seen = new AtomicReference<AgentRunContext>(); var events = new ArrayList<TraceEvent>();
            var monitor = new ConversationAgent() {
                public AgentDefinition definition() { return com.cexpilot.business.cex.monitor.MonitorAgent.DEFINITION; }
                public String promptVersion() { return "v1"; }
                public ExecutionResult execute(AgentRunContext run) {
                    seen.set(run);
                    return new ExecutionResult("什么阈值？", JSON.createArrayNode(), 0, 0, 0, 0, "NEEDS_CLARIFICATION", "什么阈值？")
                            .withAgent("monitor", JSON.createObjectNode().put("status", "NEEDS_CLARIFICATION"));
                }
            };
            var router = mock(IntentRouter.class);
            when(router.route(any(), any(), any(), any(), any())).thenReturn(new IntentRouter.Decision("monitor", null, List.of()));
            var policy = new HarnessRolloutConfig(); policy.setHandoffPercent(percent);
            var dispatcher = new AgentDispatcher(router, new AgentRegistry(List.of(monitor)), new AgentContextAssembler(),
                    new QueryExecutionConfig(), policy, handoffs);
            var result = dispatcher.execute(AgentExecutionRequest.builder("价格超过指定值时提醒我","trace",events::add)
                    .history("历史").requestContext(new RequestContext(ZoneOffset.UTC, Instant.parse("2026-11-07T00:00:00Z")))
                    .input(new com.cexpilot.business.cex.query.CexAgentInput(input("c", 1), null)).session(session)
                    .routingState(JSON.createObjectNode().put("active_agent", "query")).visitor("visitor").build());
            assertEquals(percent == 100, seen.get().conversationContext().has("handoff"));
            assertEquals(percent == 100, result.agentPayload().has("handoff"));
            assertEquals(percent == 100 ? 2 : 0, events.stream().filter(e -> "AGENT_HANDOFF".equals(e.eventType())).count());
            assertEquals("价格超过指定值时提醒我", seen.get().question()); assertNull(seen.get().privateInput());
            assertEquals("NEEDS_CLARIFICATION", result.turnStatus());
        }
    }

    @Test void monitorFailureAndFailureTraceErrorDoNotLoseOriginalUsageOrEvidence() {
        for(boolean breakTrace:List.of(false,true)) {
            var original=new ExecutionFailure(new IllegalStateException("monitor unavailable"),
                    new TurnCounters(1,0,0,0,0,0,List.of("saved"))
                            .withLlmUsages(List.of(new com.cexpilot.platform.llm.LlmUsage("normal","test",null,null,null))));
            var monitor=mock(ConversationAgent.class);
            when(monitor.definition()).thenReturn(com.cexpilot.business.cex.monitor.MonitorAgent.DEFINITION);
            when(monitor.engine()).thenReturn(EngineKind.BUILTIN);
            when(monitor.execute(any())).thenThrow(original);
            var router=mock(IntentRouter.class);
            when(router.route(any(),any(),any(),any(),any())).thenReturn(new IntentRouter.Decision("monitor",null,
                    List.of(new com.cexpilot.platform.llm.LlmUsage("normal","test",10,5,0.0))));
            var events=new ArrayList<TraceEvent>();
            TraceSink sink=event->{events.add(event);if(breakTrace && event.outputJson()!=null && event.outputJson().contains("MONITOR_EXECUTION_FAILED"))throw new IllegalStateException("trace unavailable");};
            var dispatcher=new AgentDispatcher(router,new AgentRegistry(List.of(monitor)),new AgentContextAssembler(),new com.cexpilot.platform.execution.config.QueryExecutionConfig(),new HarnessRolloutConfig(),new AnalysisMonitorHandoff());
            var failed=assertThrows(ExecutionFailure.class,()->dispatcher.execute(AgentExecutionRequest.builder("监控它","t",sink)
                    .requestContext(new RequestContext(ZoneOffset.UTC,Instant.parse("2026-11-07T00:00:00Z")))
                    .input(new com.cexpilot.business.cex.query.CexAgentInput(input("c",1), null)).session(session)
                    .routingState(JSON.createObjectNode().put("active_agent","query")).visitor("visitor").build()));
            assertEquals("monitor unavailable",failed.getMessage());assertEquals(1,failed.counters.plannerLlmCalls());
            assertEquals(1,failed.counters.routerLlmCalls());assertEquals(2,failed.counters.llmUsages().size());
            assertNull(com.cexpilot.platform.llm.LlmUsage.totalCost(failed.counters.llmUsages()));
            assertEquals(List.of("saved"),failed.counters.savedFactIds());
            assertTrue(events.stream().anyMatch(e->e.outputJson()!=null && e.outputJson().contains("MONITOR_EXECUTION_FAILED")));
            assertEquals(breakTrace?1:0,original.getSuppressed().length);
        }
    }

    @Test void handoffCompletionTraceFailureKeepsCompletedExecutionCounters() {
        var monitor=mock(ConversationAgent.class);
        when(monitor.definition()).thenReturn(com.cexpilot.business.cex.monitor.MonitorAgent.DEFINITION);
        when(monitor.engine()).thenReturn(EngineKind.BUILTIN);
        var result=new ExecutionResult("草稿",JSON.createArrayNode(),2,1,10,5,"SUCCEEDED",null,0,1,0,0,0,2,List.of("saved"))
                .withLlmUsages(List.of(new com.cexpilot.platform.llm.LlmUsage("normal","test",10,5,0.0)))
                .withAgent("monitor",JSON.createObjectNode().put("status","PENDING_CONFIRMATION"));
        when(monitor.execute(any())).thenReturn(result);
        var router=mock(IntentRouter.class);
        when(router.route(any(),any(),any(),any(),any())).thenReturn(new IntentRouter.Decision("monitor",null,List.of()));
        var dispatcher=new AgentDispatcher(router,new AgentRegistry(List.of(monitor)),new AgentContextAssembler(),new com.cexpilot.platform.execution.config.QueryExecutionConfig(),new HarnessRolloutConfig(),new AnalysisMonitorHandoff());
        TraceSink sink=event->{if(event.outputJson()!=null && event.outputJson().contains("DELIVERED_TO_MONITOR"))throw new IllegalStateException("trace unavailable");};
        var failed=assertThrows(ExecutionFailure.class,()->dispatcher.execute(AgentExecutionRequest.builder("监控它","t",sink)
                .requestContext(new RequestContext(ZoneOffset.UTC,Instant.parse("2026-11-07T00:00:00Z")))
                .input(new com.cexpilot.business.cex.query.CexAgentInput(input("c",1), null)).session(session)
                .routingState(JSON.createObjectNode().put("active_agent","query")).visitor("visitor").build()));
        assertEquals(1,failed.counters.plannerLlmCalls());assertEquals(2,failed.counters.toolCallCount());
        assertEquals(2,failed.counters.httpRequestCount());assertEquals(List.of("saved"),failed.counters.savedFactIds());
        assertEquals(result.evidence(),failed.evidence);
    }
}
