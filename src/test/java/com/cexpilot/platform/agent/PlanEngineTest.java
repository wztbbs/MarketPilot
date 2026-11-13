package com.cexpilot.platform.agent;
import com.cexpilot.platform.tool.HttpToolFixtures;
import com.cexpilot.business.cex.evidence.InMemoryEvidenceRepository;

import com.cexpilot.business.cex.context.AgentContextAssembler;
import com.cexpilot.business.cex.handoff.AnalysisMonitorHandoff;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.llm.LlmCallService;

import com.cexpilot.platform.tool.calculation.AvgTool;
import com.cexpilot.platform.llm.LlmConfig;
import com.cexpilot.platform.engine.dag.DagConfig;
import com.cexpilot.platform.execution.config.HarnessRolloutConfig;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.engine.dag.DagExecutor;
import com.cexpilot.business.cex.evidence.EvidenceStore;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.ToolSpec;
import com.cexpilot.platform.llm.LlmClient;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.tool.ToolDefinitionLoader;
import com.cexpilot.platform.execution.ExecutionResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cexpilot.platform.tool.HttpToolFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** PlanEngine 试点租户：YAML 声明 plan 引擎 Agent，覆盖 规划→校验→DAG 执行→回答 全链。 */
class PlanEngineTest {
    static final String PLAN_AGENT = """
            id: plan_reference
            route: plan_reference
            description: 查询参考价并做汇总计算
            enabled: true
            engine: plan
            prompt: demo_plan_agent
            allowed_tools: [reference.lookup, avg]
            execution:
              max_model_calls: 6
              max_tool_calls: 4
              timeout_ms: 10000
            """;
    static final String PLAN = """
            {"nodes":[
              {"id":"n1","tool":"reference.lookup","args":{"symbol":"BTC"}},
              {"id":"n2","tool":"reference.lookup","args":{"symbol":"ETH"}},
              {"id":"c1","tool":"avg","args":{"input":{"kind":"values","values":["{{n1.data.value}}","{{n2.data.value}}"]}}}
            ]}""";

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    final InMemoryEvidenceRepository repository = new InMemoryEvidenceRepository();
    final EvidenceStore evidence = new EvidenceStore(repository);
    final List<TraceEvent> events = new ArrayList<>();
    DagExecutor dagExecutor;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lookup", e -> { hits.incrementAndGet(); var args = JSON.readTree(e.getRequestBody());
            assertEquals("visitor", e.getRequestHeaders().getFirst("X-Cexpilot-Visitor"));
            assertEquals("plan_reference", e.getRequestHeaders().getFirst("X-Cexpilot-Agent"));
            int value = "ETH".equals(args.path("symbol").asText()) ? 200 : 100;
            byte[] body = ("{\"status\":\"OK\",\"data\":{\"symbol\":\"" + args.path("symbol").asText() + "\",\"value\":" + value + "},"
                    + "\"evidence\":{\"source\":\"local-mock\",\"observed_at\":\"" + Instant.now() + "\",\"valid_until\":\""
                    + Instant.now().plusSeconds(30) + "\",\"unit\":\"USDT\",\"definition_version\":\"mock-v1\"}}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, body.length);
            try (var out = e.getResponseBody()) { out.write(body); } });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); if (dagExecutor != null) dagExecutor.close(); assertNull(QueryBudget.current()); }

    /** 工具解析：agents/*.yml 走内存；tools/*.yml 取 classpath 中的 avg.yml 并叠加内存的 reference.lookup。无通配的根目录解析委托真实 classpath。 */
    static PathMatchingResourcePatternResolver planLoader(String agentYaml) {
        return new PathMatchingResourcePatternResolver() {
            @Override public Resource[] getResources(String pattern) throws IOException {
                if (pattern.contains("agents/") && pattern.endsWith("*.yml"))
                    return new Resource[]{ new ByteArrayResource(agentYaml.getBytes(StandardCharsets.UTF_8)) };
                if (pattern.contains("tools/") && pattern.endsWith("*.yml")) {
                    var merged = new ArrayList<Resource>();
                    for (var resource : super.getResources(pattern))
                        if (resource.getFilename() != null && resource.getFilename().equals("avg.yml")) merged.add(resource);
                    merged.add(new ByteArrayResource(TOOL.getBytes(StandardCharsets.UTF_8)));
                    return merged.toArray(Resource[]::new);
                }
                return super.getResources(pattern);
            }
        };
    }

    AgentDispatcher dispatcher(String agentYaml, LlmClient model) {
        var resources = planLoader(agentYaml);
        var tools = new ToolRegistry(List.of(new AvgTool()), ToolDefinitionLoader.load(resources), services(server.getAddress().getPort()));
        var prompts = new PromptStore(resources);
        var llm = new LlmCallService(model, new LlmConfig(), prompts);
        var registry = new AgentRegistry(List.of(), new ConfiguredAgentFactory(resources, tools, prompts));
        dagExecutor = new DagExecutor(tools, new DagConfig());
        return new AgentDispatcher(new IntentRouter(llm, prompts, new com.cexpilot.business.cex.context.CexRoutingDialect()), registry, new AgentContextAssembler(),
                new QueryExecutionConfig(), new HarnessRolloutConfig(), new AnalysisMonitorHandoff(),
                null, new PlanEngine(tools, prompts, llm, evidence, dagExecutor));
    }

    ExecutionResult run(LlmClient model) {
        return dispatcher(PLAN_AGENT, model).execute(AgentExecutionRequest.builder("查 BTC 和 ETH 的参考价并求平均值", "trace", events::add)
                .requestContext(new RequestContext(ZoneOffset.UTC, Instant.now()))
                .session(new EvidenceSession("conversation", "turn")).routingState(JSON.createObjectNode()).visitor("visitor").build());
    }

    @Test void planAgentPlansExecutesDagAndAnswersThroughEngine() {
        var model = new Script("{\"route\":\"plan_reference\"}", PLAN,
                "{\"answer\":\"BTC 和 ETH 参考价的平均值为 150 USDT\",\"evidence_refs\":[\"c1\"]}");
        var result = run(model);
        assertEquals("plan_reference", result.agentType());
        assertEquals(2, hits.get());
        assertEquals(1, result.routerLlmCalls()); assertEquals(1, result.plannerLlmCalls()); assertEquals(1, result.answerLlmCalls());
        assertEquals(3, result.toolCallCount()); assertEquals(2, result.httpRequestCount());
        assertEquals("FINISHED", result.agentPayload().path("execution_status").asText());
        assertTrue(result.answer().contains("150"));
        assertEquals(2, repository.rows.size());
        assertTrue(events.stream().anyMatch(e -> "AGENT_FINISHED".equals(e.eventType())));
    }

    @Test void invalidPlanRepairsOnceThenExecutes() {
        var model = new Script("{\"route\":\"plan_reference\"}",
                "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"reference.lookup\",\"args\":{\"symbol\":\"{{n9.data.value}}\"}}]}",
                PLAN, "{\"answer\":\"均值 150 USDT\",\"evidence_refs\":[\"c1\"]}");
        var result = run(model);
        assertEquals(2, result.plannerLlmCalls());
        assertEquals("FINISHED", result.agentPayload().path("execution_status").asText());
        assertEquals(2, hits.get());
    }

    @Test void twiceInvalidPlanDegradesWithoutExecution() {
        var model = new Script("{\"route\":\"plan_reference\"}",
                "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"avg\",\"args\":{\"input\":{\"kind\":\"values\",\"values\":[\"{{n1.data.value}}\"]}}}]}",
                "{\"nodes\":[{\"id\":\"n1\",\"tool\":\"missing.tool\",\"args\":{}}]}");
        var result = run(model);
        assertEquals(2, result.plannerLlmCalls()); assertEquals(0, result.answerLlmCalls());
        assertEquals("PLAN_INVALID", result.agentPayload().path("stop_reason").asText());
        assertEquals("PARTIAL", result.agentPayload().path("execution_status").asText());
        assertEquals(0, hits.get());
    }

    @Test void planAgentRejectsUnregisteredToolAtStartup() {
        var bad = PLAN_AGENT.replace("[reference.lookup, avg]", "[reference.lookup, missing]");
        assertThrows(IllegalArgumentException.class, () -> dispatcher(bad, new Script("{\"route\":\"plan_reference\"}")));
    }

    /** 顺序脚本模型：逐次返回预置响应。 */
    static final class Script implements LlmClient {
        final Queue<String> outputs = new ArrayDeque<>(); final List<List<ChatMessage>> calls = new ArrayList<>();
        Script(String... responses) { outputs.addAll(List.of(responses)); }
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) { assertNull(tools); calls.add(List.copyOf(messages)); return new ChatResponse(outputs.remove(), List.of(), 10, 5); }
    }
}
