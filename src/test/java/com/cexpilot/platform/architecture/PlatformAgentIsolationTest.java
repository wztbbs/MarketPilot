package com.cexpilot.platform.architecture;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.context.*;
import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.engine.react.ReactEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.*;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.config.HttpToolServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 非行情示例仅导入 platform 契约，真实走注册、路由、引擎、本地 HTTP 工具和答案交付。 */
class PlatformAgentIsolationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @ParameterizedTest @EnumSource(value = EngineKind.class, names = {"REACT", "PLAN"})
    void sampleAgentRunsWithoutAnyCexInputOutputOrServices(EngineKind kind) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notes", exchange -> {
            var body = JSON.createObjectNode().put("status", "OK");
            body.putObject("data").put("value", 3);
            body.putObject("evidence").put("source", "local-notes").put("observed_at", Instant.now().toString())
                    .put("valid_until", Instant.now().plusSeconds(60).toString()).put("unit", "items").put("definition_version", "1");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var config = new DagConfig();
        var services = new HttpToolServices(); var service = new HttpToolServices.Service();
        service.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()); services.setServices(Map.of("notes", service));
        var definition = new ToolDefinition("notes.read", true, "读取笔记数量",
                JSON.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
                JSON.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"number\"}}}"),
                new HttpToolDefinition("notes", "GET", "/notes", 3000, 1, 8192, 60000));
        var tools = new ToolRegistry(List.of(), List.of(definition), services);
        var dag = new DagExecutor(tools, config);
        try {
            var responses = new ArrayDeque<String>(); responses.add("{\"route\":\"sample\"}");
            if (kind == EngineKind.REACT) {
                responses.add("{\"action\":\"call\",\"tool\":\"notes.read\",\"arguments\":{}}");
                responses.add("{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}");
                responses.add("{\"answer\":\"共有3条笔记\",\"evidence_refs\":[\"e1\"]}");
            } else {
                responses.add("{\"nodes\":[{\"id\":\"n1\",\"tool\":\"notes.read\",\"args\":{}}]}");
                responses.add("{\"answer\":\"共有3条笔记\",\"evidence_refs\":[\"n1\"]}");
            }
            var modelInputs = new ArrayList<List<ChatMessage>>();
            LlmClient client = (messages, specs) -> {
                modelInputs.add(List.copyOf(messages));
                return new ChatResponse(responses.removeFirst(), List.of(), 10, 5);
            };
            String businessPrompt = "你是笔记助手，只读取笔记数量；回答使用条目单位，不能更改笔记。";
            var resources = new DefaultResourceLoader() {
                @Override public Resource getResource(String location) {
                    return "classpath:prompts/notes_agent.txt".equals(location)
                            ? new ByteArrayResource(businessPrompt.getBytes(StandardCharsets.UTF_8)) : super.getResource(location);
                }
            };
            var prompts = new PromptStore(resources);
            var llm = new LlmCallService(client, new LlmConfig(), prompts);
            var limits = new QueryExecutionConfig();
            var agent = new DeclaredAgent(new ConfiguredAgentDefinition(new AgentDefinition("sample", "sample", "读取笔记数量"),
                    true, kind, "notes_agent", Set.of("notes.read"), 4, limits), tools, prompts);
            var dispatcher = new AgentDispatcher(new IntentRouter(llm, prompts), new AgentRegistry(List.of(agent)),
                    new BasicAgentContextProvider(), limits, new HarnessRolloutConfig(), DispatchLifecycle.none(),
                    new ReactEngine(tools, prompts, llm, null), new PlanEngine(tools, prompts, llm, null, dag));
            var events = new ArrayList<TraceEvent>(); var deltas = new ArrayList<String>();
            var result = dispatcher.execute(AgentExecutionRequest.builder("有几条笔记", "trace", events::add)
                    .requestContext(new RequestContext(ZoneOffset.UTC, Instant.now())).session(new EvidenceSession("session", "turn"))
                    .visitor("operator").delta(deltas::add).build());
            assertEquals("sample", result.agentType()); assertEquals("共有3条笔记", result.answer());
            assertEquals(List.of("共有3条笔记"), deltas); assertEquals(1, result.toolCallCount());
            assertNull(result.output()); assertTrue(responses.isEmpty());
            assertTrue(events.stream().anyMatch(e -> "AGENT_DISPATCH".equals(e.eventType())));
            if (kind == EngineKind.REACT) {
                assertEquals(4, modelInputs.size()); // Router、两次决策、最终回答。
                assertTrue(modelInputs.get(0).get(0).content().contains("读取笔记数量"));
                assertFalse(modelInputs.get(0).get(0).content().contains(businessPrompt), "Router 只接收 description");
                for (var messages : modelInputs.subList(1, modelInputs.size())) {
                    var system = messages.get(0).content();
                    assertTrue(system.contains(businessPrompt), "决策和回答必须注入同一业务说明");
                    assertFalse(system.contains("{{agent_prompt}}"));
                    for (String cexTerm : List.of("行情", "交易所", "品种")) assertFalse(system.contains(cexTerm));
                }
                assertTrue(modelInputs.get(1).get(0).content().contains("notes.read"));
                assertTrue(modelInputs.get(3).get(1).content().contains("facts"));
                assertTrue(modelInputs.get(3).get(0).content().contains("evidence_refs"));
                for (var event : events) {
                    if (!"LLM_CALL".equals(event.eventType()) || !Set.of("agent_plan", "agent_answer").contains(event.name())) continue;
                    String base = "agent_plan".equals(event.name()) ? "react_base_decision" : "react_base_answer";
                    assertEquals(prompts.version(base), JSON.readTree(event.inputJson()).path("prompt_version").asText());
                }
                // 三层中的任一提示词变化都必须改变运行版本，禁止恢复时静默换协议或业务说明。
                for (String template : List.of("notes_agent", "react_base_decision", "react_base_answer")) {
                    var changed = new DefaultResourceLoader() {
                        @Override public Resource getResource(String location) {
                            return ("classpath:prompts/" + template + ".txt").equals(location)
                                    ? new ByteArrayResource((prompts.render(template, Map.of()) + "\n补充业务约束").getBytes(StandardCharsets.UTF_8))
                                    : resources.getResource(location);
                        }
                    };
                    assertNotEquals(agent.promptVersion(), new DeclaredAgent(agent.configured(), tools, new PromptStore(changed)).promptVersion());
                }
            }
        } finally { dag.close(); server.stop(0); }
    }
}
