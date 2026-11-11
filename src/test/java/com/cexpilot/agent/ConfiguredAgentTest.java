package com.cexpilot.agent;

import com.cexpilot.config.*;
import com.cexpilot.api.*;
import com.cexpilot.conversation.*;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.trace.*;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cexpilot.evidence.*;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.cexpilot.runtime.HttpToolFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 YAML 加载、注册与 HTTP 调用；模型仅脚本响应，服务仅 localhost。 */
class ConfiguredAgentTest {
    HttpServer server;
    final AtomicInteger hits=new AtomicInteger();
    final InMemoryEvidenceRepository repository=new InMemoryEvidenceRepository();
    final EvidenceStore evidence=new EvidenceStore(repository);
    final List<TraceEvent> events=new ArrayList<>();
    final List<String> deltas=new ArrayList<>();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/lookup",e->{hits.incrementAndGet();var args=JSON.readTree(e.getRequestBody());
            assertEquals("visitor",e.getRequestHeaders().getFirst("X-Cexpilot-Visitor"));
            byte[] response=response(args.path("symbol").asText()).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);assertNull(QueryBudget.current());}
    static final class Script implements LlmClient {
        final Queue<String> outputs=new ArrayDeque<>();final List<List<ChatMessage>> calls=new ArrayList<>();
        Script(String... responses){outputs.addAll(List.of(responses));}
        public ChatResponse chat(List<ChatMessage> messages,List<ToolSpec> tools){assertNull(tools);calls.add(List.copyOf(messages));String output=outputs.remove();if(output.equals("ERROR"))throw new IllegalStateException("offline");return new ChatResponse(output,List.of(),10,5);}
    }
    ConfiguredAgentFactory factory(String agent,String tool) {
        return factory(agent,List.of(tool));
    }
    ConfiguredAgentFactory factory(String agent,List<String> definitions) {
        var resources=loader(definitions,List.of(agent));
        var tools=new ToolRegistry(List.of(),ToolDefinitionLoader.load(resources),services(server.getAddress().getPort()));
        return new ConfiguredAgentFactory(resources,tools,new PromptStore(resources));
    }
    ExecutionResult run(String agent,Script model) {
        return dispatcher(agent,model).execute(AgentExecutionRequest.builder("查BTC的模拟参考值","trace",events::add)
                .history("历史").delta(deltas::add).requestContext(new RequestContext(ZoneOffset.UTC,Instant.now()))
                .session(new EvidenceSession("conversation","turn")).routingState(JSON.createObjectNode())
                .selectedAgent(route->assertEquals("reference",route)).visitor("visitor").build());
    }
    AgentDispatcher dispatcher(String agent,Script model) {
        var resources=loader(List.of(TOOL),List.of(agent));var prompts=new PromptStore(resources);
        var tools=new ToolRegistry(List.of(),ToolDefinitionLoader.load(resources),services(server.getAddress().getPort()));
        var llm=new PlanningLlm(model,new LlmConfig(),prompts);
        var registry=new AgentRegistry(List.of(),new ConfiguredAgentFactory(resources,tools,prompts));
        return new AgentDispatcher(new IntentRouter(llm,prompts),registry,new AgentContextAssembler(),
                new QueryExecutionConfig(),new HarnessRolloutConfig(),new AnalysisMonitorHandoff(),new ReactEngine(tools,prompts,llm,evidence));
    }
    @Test void declaredAgentExecutesOnlyThroughEngine() {
        var declared=factory(AGENT,TOOL).create().get(0);
        assertEquals(EngineKind.REACT,declared.engine());
        assertThrows(IllegalStateException.class,()->declared.execute(null));
        // 未装配引擎的 dispatcher：REACT 分发必须报明确错误而非 NPE
        LlmClient model=(m,t)->new ChatResponse("{\"route\":\"reference\"}",List.of(),10,5);
        var prompts=new PromptStore(loader(List.of(),List.of()));
        var bare=new AgentDispatcher(new IntentRouter(new PlanningLlm(model,new LlmConfig(),prompts),prompts),
                new AgentRegistry(List.of(declared)),new AgentContextAssembler());
        var failure=assertThrows(ExecutionFailure.class,()->bare.execute(AgentExecutionRequest.builder("查BTC","t",e->{})
                .requestContext(new RequestContext(ZoneOffset.UTC,Instant.now())).routingState(JSON.createObjectNode()).visitor("visitor").build()));
        assertTrue(failure.getMessage().contains("ReactEngine 未装配"));
    }
    @Test void yamlOnlyAgentRoutesCallsPersistsFactsAndAnswersThroughSharedHarness() {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}","{\"answer\":\"模拟参考值为123 USDT\",\"evidence_refs\":[\"e1\"]}");
        var result=run(AGENT,model);
        assertEquals("reference",result.agentType());assertEquals(1,result.toolCallCount());assertEquals(1,result.httpRequestCount());
        assertEquals(2,result.plannerLlmCalls());assertEquals(1,result.answerLlmCalls());assertEquals(1,result.routerLlmCalls());
        assertEquals(List.of(result.answer()),deltas);assertEquals(4,model.calls.size());assertEquals(4,result.agentPayload().at("/budget/model_calls").asInt());
        assertEquals("FINISHED",result.agentPayload().path("execution_status").asText());assertEquals(1,repository.rows.size());
        var fact=repository.rows.values().iterator().next();assertEquals("http_tool",fact.identity().path("kind").asText());
        assertEquals("reference",fact.identity().path("agent").asText());assertTrue(evidence.listReusable("conversation",Instant.now()).isEmpty());
        assertTrue(evidence.loadForConversation("another",fact.factId()).isEmpty());
        assertTrue(events.stream().anyMatch(e->"http_tool_attempt".equals(e.name())));
        assertTrue(events.stream().anyMatch(e->"AGENT_FINISHED".equals(e.eventType())));
        assertFalse(events.toString().contains(SECRET));assertFalse(model.calls.toString().contains(SECRET));
    }
    @Test void unregisteredToolIsRejectedWithoutNetworkThenModelMayClarify() {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"unknown\",\"arguments\":{}}",
                "{\"action\":\"clarify\",\"clarify\":[{\"key\":\"symbol\",\"label\":\"品种\",\"reason\":\"missing\"}]}");
        var result=run(AGENT,model);assertEquals(0,hits.get());assertEquals("NEEDS_CLARIFICATION",result.turnStatus());
        assertEquals(0,result.answerLlmCalls());assertEquals(1,com.cexpilot.dag.ClarificationField.fromPayload(result.agentPayload()).size());
        assertTrue(model.calls.get(2).get(1).content().contains("未获此 Agent 授权"));
    }
    @Test void duplicateInvocationStopsWithoutDuplicateHttpOrFact() {
        String call="{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}";
        var model=new Script("{\"route\":\"reference\"}",call,call,"{\"answer\":\"仅取得一次模拟记录\",\"evidence_refs\":[\"e1\"]}");
        var result=run(AGENT,model);assertEquals(1,hits.get());assertEquals(1,repository.rows.size());
        assertEquals("NO_PROGRESS",result.agentPayload().path("stop_reason").asText());assertEquals("PARTIAL",result.agentPayload().path("execution_status").asText());
    }
    @Test void yamlCannotResetRouterUsageOrExpandGlobalBudget() {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"answer\":\"仅完成查询，预算不足以继续\",\"evidence_refs\":[\"e1\"]}");
        var result=run(AGENT.replace("max_model_calls: 6","max_model_calls: 3"),model);
        assertEquals(3,model.calls.size());assertEquals(1,result.routerLlmCalls());assertEquals(1,result.plannerLlmCalls());
        assertEquals(1,result.answerLlmCalls());assertEquals("MODEL_BUDGET",result.agentPayload().path("stop_reason").asText());
    }
    @Test void failedOrInventedAnswerReferencesNeverBecomeUserAnswer() {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}","{\"answer\":\"虚构答案\",\"evidence_refs\":[\"e99\"]}");
        var result=run(AGENT,model);assertEquals("ANSWER_INVALID",result.agentPayload().path("stop_reason").asText());assertFalse(result.answer().contains("虚构"));
    }
    @ParameterizedTest @ValueSource(strings={"engine","budget","missing_tool","duplicate_tool","missing_prompt","unknown_key","reserved_id","reserved_route","duplicate_yaml_key"})
    void invalidConfigurationFailsAtStartup(String kind) {
        String agent=switch(kind) {
            case "engine" -> AGENT.replace("engine: react","engine: arbitrary_loop");
            case "budget" -> AGENT.replace("max_tool_calls: 3","max_tool_calls: 0");
            case "missing_tool" -> AGENT.replace("[reference.lookup]","[missing]");
            case "duplicate_tool" -> AGENT.replace("[reference.lookup]","[reference.lookup, reference.lookup]");
            case "missing_prompt" -> AGENT.replace("local_reference","missing_prompt");
            case "unknown_key" -> AGENT+"custom_url: http://example.invalid\n";
            case "reserved_id" -> AGENT.replace("id: reference","id: market_analysis");
            case "reserved_route" -> AGENT.replace("route: reference","route: query");
            default -> AGENT+"enabled: false\n";
        };
        assertThrows(RuntimeException.class,()->factory(agent,TOOL).create());assertEquals(0,hits.get());
    }
    @Test void disabledAgentsAreNotAdvertisedAndVersionsAreDeterministic() {
        assertTrue(factory(AGENT.replace("enabled: true","enabled: false"),TOOL).create().isEmpty());
        assertEquals(factory(AGENT,TOOL).create().get(0).promptVersion(),factory(AGENT,TOOL).create().get(0).promptVersion());
    }
    @Test void modelFailureAfterHttpPreservesActualToolCountsUsageAndFacts() {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}","ERROR");
        var failure=assertThrows(ExecutionFailure.class,()->run(AGENT,model));
        assertEquals(1,failure.counters.toolCallCount());assertEquals(1,failure.counters.httpRequestCount());
        assertEquals(1,failure.counters.routerLlmCalls());assertEquals(1,failure.counters.answerLlmCalls());
        assertNull(LlmUsage.totalCost(failure.counters.llmUsages()));assertEquals(1,failure.evidence.size());assertEquals(1,failure.counters.savedFactIds().size());
    }
    @Test void realAskEndpointDispatchesConfiguredAgentAndKeepsQueryTaskVersionSeparate() throws Exception {
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}","{\"answer\":\"模拟参考值为123 USDT\",\"evidence_refs\":[\"e1\"]}");
        var conversations=mock(ConversationService.class);
        var contexts=mock(ConversationContextService.class);
        var legacy=mock(DagRuntime.class);
        when(conversations.getOrCreateConversation(any(),any(),any())).thenReturn("conversation");
        var turn=new ConversationService.TurnHandle(1,"turn",1);
        when(conversations.beginTurn(any(),any(),any(),any())).thenReturn(turn);
        when(conversations.renderRecentDialogue("conversation")).thenReturn("");
        when(conversations.routingState("conversation")).thenReturn(JSON.createObjectNode());
        when(contexts.buildPlanningContext("conversation")).thenReturn(new PlanningContext("",null));
        when(conversations.finishRoutedTurn(any(),any(),any(),any(),
                any(),any(),any(),any(),any(),
                any(),any(),any(),any(),any()))
                .thenAnswer(inv->{assertNull(inv.getArgument(6));assertNull(inv.getArgument(8));assertEquals("reference",inv.getArgument(12));return new ConversationService.TaskCommit(false,null,0,null);});
        var service=new AskService(conversations,contexts,legacy,mock(TraceRepository.class),
                mock(DbTraceSink.class),new LlmConfig(),Clock.systemUTC(),mock(ObjectProvider.class),dispatcher(AGENT,model));
        var mvc=standaloneSetup(new AskController(service,new SyncTaskExecutor())).build();
        mvc.perform(post("/api/ask").cookie(new Cookie("cexpilot_uid","visitor"))
                .contentType("application/json").content("{\"conversationId\":\"conversation\",\"question\":\"查BTC模拟参考值\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("reference"))
                .andExpect(jsonPath("$.httpRequestCount").value(1))
                .andExpect(jsonPath("$.planning.execution_status").value("FINISHED"));
        verifyNoInteractions(legacy);
    }

    @Test void successfulEvidenceCanSupplyNextToolParametersWithoutCopyingNumbers() {
        String agent=AGENT.replace("[reference.lookup]","[reference.lookup, reference.detail]");
        var model=new Script("{\"route\":\"reference\"}","{\"action\":\"call\",\"tool\":\"reference.lookup\",\"arguments\":{\"symbol\":\"BTC\"}}",
                "{\"action\":\"call\",\"tool\":\"reference.detail\",\"arguments\":{\"symbol\":{\"$ref\":\"e1#/symbol\"}}}",
                "{\"action\":\"finish\",\"evidence_refs\":[\"e2\"]}","{\"answer\":\"补查得到模拟记录\",\"evidence_refs\":[\"e2\"]}");
        var resources=loader(List.of(TOOL,TOOL.replace("name: reference.lookup","name: reference.detail")),List.of(agent));
        var tools=new ToolRegistry(List.of(),ToolDefinitionLoader.load(resources),services(server.getAddress().getPort()));
        var prompts=new PromptStore(resources);
        var llm=new PlanningLlm(model,new LlmConfig(),prompts);
        var registry=new AgentRegistry(List.of(),new ConfiguredAgentFactory(resources,tools,prompts));
        var dispatcher=new AgentDispatcher(new IntentRouter(llm,prompts),registry,new AgentContextAssembler(),
                new QueryExecutionConfig(),new HarnessRolloutConfig(),new AnalysisMonitorHandoff(),new ReactEngine(tools,prompts,llm,evidence));
        var result=dispatcher.execute(AgentExecutionRequest.builder("查模拟记录后补查详情","trace",events::add)
                .requestContext(new RequestContext(java.time.ZoneOffset.UTC,Instant.now()))
                .session(new EvidenceSession("conversation","turn")).routingState(JSON.createObjectNode()).visitor("visitor").build());
        assertEquals(2,hits.get());assertEquals(2,result.httpRequestCount());assertEquals(2,repository.rows.size());
        assertEquals("FINISHED",result.agentPayload().path("execution_status").asText());
    }

}
