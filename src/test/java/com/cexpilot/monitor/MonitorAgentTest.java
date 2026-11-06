package com.cexpilot.monitor;

import com.cexpilot.agent.AgentRunContext;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorAgentTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MonitorPlanner planner=mock(MonitorPlanner.class);
    private final MonitorConversationFlow flow=mock(MonitorConversationFlow.class);
    private final MonitorAgent agent=new MonitorAgent(planner,mock(PromptStore.class),flow);
    private final List<LlmUsage> usages=List.of(new LlmUsage("normal","fake",10,5,0.0));
    private AgentRunContext request() {
        return new AgentRunContext("监控BTC","历史","t",e->{},null,
                new RequestContext(ZoneId.of("Asia/Shanghai"),Instant.parse("2026-11-06T00:00:00Z")),
                null,new EvidenceSession("c","turn"),JSON.createObjectNode(),JSON.createObjectNode(),"visitor",null);
    }
    @Test void clarificationDoesNotCreateDraftOrQueryMarket() {
        when(planner.plan(any(),any(),any(),any())).thenReturn(new MonitorPlanner.Outcome("NEEDS_CLARIFICATION","什么阈值？",null,usages));
        var result=agent.execute(request());
        assertEquals("NEEDS_CLARIFICATION",result.turnStatus()); assertEquals("monitor",result.agentType());
        assertEquals(1,result.plannerLlmCalls()); assertEquals(0,result.toolCallCount());
        verifyNoInteractions(flow);
    }
    @Test void draftPersistenceFailureRetainsMonitorUsageForHarnessAggregation() {
        when(planner.plan(any(),any(),any(),any())).thenReturn(new MonitorPlanner.Outcome("PLANNED","候选",JSON.createObjectNode(),usages));
        when(flow.saveDraft(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("database unavailable"));
        var failure=assertThrows(ExecutionFailure.class,()->agent.execute(request()));
        assertEquals(usages,failure.counters.llmUsages()); assertEquals(1,failure.counters.plannerLlmCalls());
        assertEquals(0,failure.counters.routerLlmCalls()); assertEquals(0,failure.counters.metricFetchCount());
    }
    @Test void plannerFailurePreservesUnknownUsageAndNeverCreatesDraft() {
        var unknown=List.of(new LlmUsage("normal","fake",null,null,null));
        when(planner.plan(any(),any(),any(),any())).thenThrow(new MonitorPlanner.Failure(new IllegalStateException("timeout"),unknown));
        var failure=assertThrows(ExecutionFailure.class,()->agent.execute(request()));
        assertNull(LlmUsage.totalCost(failure.counters.llmUsages())); assertEquals(1,failure.counters.plannerLlmCalls());
        verifyNoInteractions(flow);
    }
}
