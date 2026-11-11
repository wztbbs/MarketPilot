package com.cexpilot.agent;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.PlanningContext;
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

/** 注册与公共执行协议，全部使用脚本模型，不访问网络。 */
class AgentRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RequestContext REQUEST = new RequestContext(ZoneOffset.UTC,Instant.parse("2026-11-06T00:00:00Z"));
    private ConversationAgent agent(String id,String route) {
        return new ConversationAgent() {
            public AgentDefinition definition() { return new AgentDefinition(id,route,"测试能力"); }
            public String promptVersion() { return "v1"; }
            public ExecutionResult execute(AgentRunContext context) { return new ExecutionResult("完成",JSON.createArrayNode(),0,0,0,0); }
        };
    }
    @Test void duplicateIdsRoutesAndReservedRoutesFailBeforeServingRequests() {
        assertThrows(IllegalStateException.class,()->new AgentRegistry(List.of()));
        assertThrows(IllegalStateException.class,()->new AgentRegistry(List.of(agent("a","a"),agent("a","b"))));
        assertThrows(IllegalStateException.class,()->new AgentRegistry(List.of(agent("a","a"),agent("b","a"))));
        assertThrows(IllegalArgumentException.class,()->agent("a","clarify").definition());
        var registry=new AgentRegistry(List.of(agent("a","a")));
        assertThrows(IllegalArgumentException.class,()->registry.require("missing"));
    }
    @Test void registrationOrderDoesNotChangePromptFingerprint() {
        var a=agent("a","a"); var b=agent("b","b");
        assertEquals(new AgentRegistry(List.of(a,b)).promptVersion(),new AgentRegistry(List.of(b,a)).promptVersion());
    }
    @Test void additionalRegisteredAgentRoutesAndDeliversOnceWithoutQueryPrivateState() {
        var seen=new AtomicReference<AgentRunContext>();
        var extra=new ConversationAgent() {
            public AgentDefinition definition() { return new AgentDefinition("example","example","本地扩展能力"); }
            public String promptVersion() { return "v1"; }
            public ExecutionResult execute(AgentRunContext context) {
                seen.set(context);
                return new ExecutionResult("扩展回复",JSON.createArrayNode(),0,0,0,0);
            }
        };
        var calls=new ArrayList<List<ChatMessage>>();
        LlmClient client=(messages,tools)->{
            calls.add(messages);
            return new ChatResponse("{\"route\":\"example\"}",List.of(),10,5);
        };
        var prompts=new PromptStore(new DefaultResourceLoader());
        var router=new IntentRouter(new PlanningLlm(client,new LlmConfig(),prompts),prompts);
        var dispatcher=new AgentDispatcher(router,new AgentRegistry(List.of(extra)),new AgentContextAssembler());
        var deltas=new ArrayList<String>(); var events=new ArrayList<TraceEvent>(); var selected=new AtomicReference<String>();
        var result=dispatcher.execute(AgentExecutionRequest.builder("问题","trace",events::add)
                .history("历史").delta(deltas::add).requestContext(REQUEST)
                .planningContext(new PlanningContext("Query 私有数据",null)).session(new EvidenceSession("c","turn"))
                .routingState(JSON.createObjectNode()).selectedAgent(selected::set).visitor("visitor").build());
        assertEquals("example",selected.get()); assertEquals("example",result.agentType());
        assertEquals(List.of("扩展回复"),deltas); assertEquals(1,result.routerLlmCalls()); assertEquals(1,calls.size());
        assertNull(seen.get().privateInput()); assertEquals("visitor",seen.get().visitor());
        assertFalse(calls.get(0).get(0).content().contains("{{agent_routes}}"));
        assertTrue(calls.get(0).get(0).content().contains("example: 本地扩展能力"));
        assertTrue(events.stream().anyMatch(e->e.eventType().equals("AGENT_DISPATCH") && e.outputJson().contains("example")));
    }
    @Test void unregisteredModelRouteRepairsThenClarifiesWithoutExecutingAgent() {
        var calls=new int[1];
        LlmClient client=(messages,tools)->{ calls[0]++; return new ChatResponse("{\"route\":\"unknown\"}",List.of(),10,5); };
        var prompts=new PromptStore(new DefaultResourceLoader());
        var router=new IntentRouter(new PlanningLlm(client,new LlmConfig(),prompts),prompts);
        var decision=router.route("问题",JSON.createObjectNode(),"t",e->{},List.of(agent("a","a").definition()));
        assertEquals("clarify",decision.route()); assertEquals(2,calls[0]);
    }
    @Test void deliveryFailureKeepsRouterUsageAndExistingEvidence() {
        LlmClient client=(messages,tools)->new ChatResponse("{\"route\":\"example\"}",List.of(),10,5);
        var prompts=new PromptStore(new DefaultResourceLoader());
        var dispatcher=new AgentDispatcher(new IntentRouter(new PlanningLlm(client,new LlmConfig(),prompts),prompts),
                new AgentRegistry(List.of(agent("example","example"))),new AgentContextAssembler());
        var failure=assertThrows(ExecutionFailure.class,()->dispatcher.execute(AgentExecutionRequest.builder("问题","t",e->{})
                .requestContext(REQUEST).delta(delta->{throw new IllegalStateException("closed");}).build()));
        assertEquals("closed",failure.getMessage()); assertEquals(1,failure.counters.routerLlmCalls());
        assertEquals(1,failure.counters.llmUsages().size()); assertNotNull(failure.evidence);
    }
}
