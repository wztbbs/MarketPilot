package com.cexpilot.monitor;

import com.cexpilot.agent.*;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.*;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Monitor 经 PlanEngine 执行的契约：澄清不建草稿、草稿失败与规划失败都保留规划用量。 */
class MonitorAgentTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MonitorConversationFlow flow=mock(MonitorConversationFlow.class);
    private final MonitorPlanValidator validator=mock(MonitorPlanValidator.class);
    private final PromptStore prompts=new PromptStore(new DefaultResourceLoader());

    private ExecutionResult run(LlmClient model) {
        var hooks=new MonitorPlanHooks(prompts,new MetricCatalog(new DefaultResourceLoader()),
                new ToolRegistry(List.of(),List.of()),validator,new com.cexpilot.config.DagConfig(),flow);
        var engine=new PlanEngine(null,prompts,new PlanningLlm(model,new LlmConfig(),prompts),null,null);
        return engine.execute(new MonitorAgent(hooks,prompts,flow),
                new AgentRunContext("监控BTC","历史","t",e->{},null,
                        new RequestContext(ZoneId.of("Asia/Shanghai"),Instant.parse("2026-11-06T00:00:00Z")),
                        null,new EvidenceSession("c","turn"),JSON.createObjectNode(),JSON.createObjectNode(),"visitor",null));
    }

    @Test void clarificationDoesNotCreateDraftOrQueryMarket() {
        var result=run((m,t)->new ChatResponse("{\"needs_clarification\":true,\"reply\":\"什么阈值？\"}",List.of(),10,5));
        assertEquals("NEEDS_CLARIFICATION",result.turnStatus()); assertEquals("monitor",result.agentType());
        assertEquals(1,result.plannerLlmCalls()); assertEquals(0,result.toolCallCount());
        verifyNoInteractions(flow);
    }

    @Test void draftPersistenceFailureRetainsMonitorUsageForHarnessAggregation() {
        when(validator.validate(any())).thenReturn(new MonitorPlanValidator.Validated(JSON.createObjectNode(),null,Map.of()));
        when(flow.saveDraft(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("database unavailable"));
        var failure=assertThrows(ExecutionFailure.class,()->run((m,t)->new ChatResponse(
                "{\"plan\":{},\"monitor\":{\"action\":\"create\",\"conditions\":[{}]}}",List.of(),10,5)));
        assertEquals(1,failure.counters.llmUsages().size()); assertEquals(1,failure.counters.plannerLlmCalls());
        assertEquals(0,failure.counters.routerLlmCalls()); assertEquals(0,failure.counters.metricFetchCount());
    }

    @Test void plannerFailurePreservesUnknownUsageAndNeverCreatesDraft() {
        LlmClient offline=(m,t)->{ throw new IllegalStateException("timeout"); };
        var failure=assertThrows(ExecutionFailure.class,()->run(offline));
        assertNull(LlmUsage.totalCost(failure.counters.llmUsages())); assertEquals(1,failure.counters.plannerLlmCalls());
        verifyNoInteractions(flow);
    }
}
