package com.cexpilot.monitor;

import com.cexpilot.agent.*;
import com.cexpilot.config.*;
import com.cexpilot.conversation.*;
import com.cexpilot.dag.PlanValidator;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.llm.*;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 脚本模型 + 真实目录/校验/定义编译/草稿事务；不执行行情请求或发信。 */
class MonitorHandoffIntegrationTest {
    MonitorTestFixture f;
    MonitorPlanRunner runner;
    MonitorConversationFlow flow;
    PromptStore prompts;
    ToolRegistry tools;
    MetricCatalog catalog;
    MonitorPlanValidator validator;
    final List<List<ChatMessage>> calls = new ArrayList<>();
    final List<TraceEvent> events = new ArrayList<>();

    @BeforeEach void setup() {
        f = new MonitorTestFixture(); f.config.setEnabled(true);
        var loader = new DefaultResourceLoader(); prompts = new PromptStore(loader);
        tools = new ToolRegistry(List.of(), List.of()); catalog = new MetricCatalog(loader);
        var provider = new MetricProvider() {
            public String name() { return KlineMetricProvider.NAME; }
            public MetricSelector selector(String name) { return KlineMetric.valueOf(name); }
            public MetricResult query(MetricQuery query, RequestContext request) { throw new AssertionError("规划/确认不能取行情"); }
        };
        var providers = new MetricProviderRegistry(List.of(provider)); var dag = new DagConfig();
        validator = new MonitorPlanValidator(catalog, tools, providers, new PlanValidator(tools, dag), dag, new LlmConfig());
        runner = new MonitorPlanRunner(catalog, tools, providers, f.clock);
        var mail = mock(EmailNotificationSender.class); when(mail.isAvailable()).thenReturn(true); when(mail.isDryRun()).thenReturn(true);
        flow = new MonitorConversationFlow(f.jdbc, f.repository, new MonitorDefinitionCompiler(validator, catalog), runner,
                f.config, f.json, f.clock, mail);
        f.jdbc.update("INSERT INTO conversation (conversation_id,visitor_id) VALUES ('c','alice')");
        f.jdbc.update("INSERT INTO conversation_state (conversation_id,running_turn_id,lease_until) VALUES ('c','t1',?)", LocalDateTime.now().plusHours(1));
    }
    @AfterEach void close() { runner.close(); f.close(); assertNull(QueryBudget.current()); }

    PlannerInput source() {
        var target = new EffectiveTarget("m1", "metric_0", "binance", ConditionOrigin.EXPLICIT, "price.change_pct", "range_statistic",
                f.mapper.createObjectNode().put("market_type", "spot").put("base", "BTC").put("quote", "USDT"),
                ConditionOrigin.DEFAULT, f.mapper.createObjectNode().put("type", "calendar_period").put("unit", "day"),
                "5m", ConditionOrigin.DEFAULT, false, ConditionOrigin.DEFAULT, null, null, null, "percent", "kline", catalog.version());
        return new PlannerInput("历史快照不应绑定到监控", new QueryFrame("source_frame", "c", 3, "source_turn", "昨天现货BTC跌了多少", "2026-10-25T06:00:00Z", "UTC",
                List.of(target), f.mapper.createObjectNode().put("value", -9), null, new QueryFrame.LastAttempt("source_turn", "SUCCEEDED")));
    }
    ExecutionResult ask(String question, String response) {
        return ask(question,response,"t1",f.mapper.createObjectNode().put("active_agent","query"));
    }
    ExecutionResult ask(String question, String response, String turn, com.fasterxml.jackson.databind.JsonNode state) {
        var responses = new ArrayDeque<>(List.of("{\"route\":\"monitor\"}", response));
        LlmClient model = (messages, toolSpecs) -> {
            calls.add(List.copyOf(messages));
            assertFalse(responses.isEmpty(), "不能额外调用模型");
            return new ChatResponse(responses.remove(), List.of(), 10, 5);
        };
        var llm = new PlanningLlm(model, new LlmConfig(), prompts); var dag = new DagConfig(); dag.setPlannerMaxRetries(0);
        var monitor = new MonitorAgent(new MonitorPlanner(llm, prompts, catalog, tools, validator, dag), prompts, flow);
        var dispatcher = new AgentDispatcher(new IntentRouter(llm, prompts), new AgentRegistry(List.of(monitor)), new AgentContextAssembler());
        var result = dispatcher.execute(question, "用户之前查询了昨天的现货涨跌幅", "trace", events::add, null,
                new RequestContext(ZoneId.of("Asia/Shanghai"), f.clock.instant()), null, null, source(), new EvidenceSession("c", turn),
                state, r -> {}, "alice");
        assertTrue(responses.isEmpty());
        return result;
    }
    ObjectNode candidate() {
        var candidate = MonitorTestFixture.candidate(1);
        ((ObjectNode)candidate.at("/plan/metrics/0/instrument")).put("market_type", "spot");
        return candidate;
    }

    @Test void queryFollowupCreatesOnlyDraftAndRequiresOwnedVersionedExplicitConfirmation() {
        var result = ask("那就监控这个现货，最近一小时跌超过2%时提醒我", candidate().toString());
        assertEquals("monitor", result.agentType()); assertEquals(1, result.routerLlmCalls()); assertEquals(1, result.plannerLlmCalls());
        assertEquals("PENDING_CONFIRMATION", result.agentPayload().path("status").asText());
        assertEquals("source_frame", result.agentPayload().at("/handoff/source_frame_id").asText());
        assertEquals("PENDING_CONFIRMATION", result.agentPayload().at("/handoff/outcome").asText());
        assertEquals(1, f.count("monitor_rule")); assertEquals(0, f.count("monitor_execution")); assertEquals(0, f.count("notification_delivery"));
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());
        var input = f.json.read(calls.get(1).get(1).content());
        assertEquals("spot", input.at("/context/handoff/targets/0/instrument/market_type").asText());
        assertTrue(input.at("/context/query_task").isMissingNode());
        assertFalse(input.path("context").path("handoff").toString().contains("calendar_period"));
        var card = (ObjectNode) result.agentPayload().path("confirmation"); String id = card.path("ruleId").asText();
        assertFalse(card.path("canConfirm").asBoolean());
        var request = new MonitorModels.ConfirmRequest(card.path("version").asInt(), card.path("specHash").asText());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> flow.confirm("bob", id, request, "a@example.com")).getStatusCode().value());
        f.jdbc.update("UPDATE conversation_state SET running_turn_id=NULL WHERE conversation_id='c'");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> flow.confirm("alice", id, request, "a@example.com")).getStatusCode().value());
        flow.bindEmail("alice", "a@example.com");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> flow.confirm("alice", id,
                new MonitorModels.ConfirmRequest(request.version(), "wrong_hash"), "a@example.com")).getStatusCode().value());
        assertEquals("ACTIVE", flow.confirm("alice", id, request, "a@example.com").path("status").asText());
        assertEquals(0, f.count("monitor_evaluation"));
        assertEquals(2, events.stream().filter(e -> "AGENT_HANDOFF".equals(e.eventType())).count());
    }

    @Test void missingThresholdClarifiesWithoutCreatingAnythingOrReusingOldValue() {
        var result = ask("就监控这个，满足时通知我", "{\"plan\":null,\"monitor\":null,\"needs_clarification\":true,\"reply\":\"跌幅阈值和滚动窗口分别是多少？\"}");
        assertEquals("NEEDS_CLARIFICATION", result.turnStatus());
        assertEquals("NEEDS_CLARIFICATION", result.agentPayload().at("/handoff/outcome").asText());
        assertEquals(0, f.count("monitor_rule")); assertEquals(0, result.httpRequestCount()); assertEquals(0, result.toolCallCount());
        assertFalse(calls.get(1).get(1).content().contains("\"value\":-9"));
    }

    @Test void sourceSurvivesPersistedClarificationAndNextMonitorTurnWithoutQueryTaskMutation() {
        String question="监控这个现货，满足时通知我";
        var result=ask(question,"{\"plan\":null,\"monitor\":null,\"needs_clarification\":true,\"reply\":\"跌幅阈值和滚动窗口分别是多少？\"}");
        var repository=new ConversationRepository(f.jdbc);
        repository.insertRunningTurn("c",1,"t1",null,question,f.clock.instant());
        var conversations=new ConversationService(repository,new ConversationConfig());
        conversations.finishRoutedTurn("c",new ConversationService.TurnHandle(1,"t1",0),question,result.turnStatus(),result.answer(),"trace",
                null,result.evidence(),null,f.clock.instant(),ZoneId.of("Asia/Shanghai"),result.clarify(),"monitor",result.agentPayload());
        var state=repository.routingState("c");
        assertEquals("source_frame",state.at("/monitor_context/handoff/source_frame_id").asText());
        assertEquals("source_frame",repository.allQueries("c").get(0).planning().at("/handoff/source_frame_id").asText());
        f.jdbc.update("UPDATE conversation_state SET running_turn_id='t2',lease_until=? WHERE conversation_id='c'",LocalDateTime.now().plusHours(1));
        var continued=ask("最近一小时跌超过2%时提醒我",candidate().toString(),"t2",state);
        assertEquals("PENDING_CONFIRMATION",continued.agentPayload().path("status").asText());
        var nextInput=f.json.read(calls.get(3).get(1).content());
        assertEquals("source_frame",nextInput.at("/context/monitor_context/handoff/source_frame_id").asText());
        assertEquals(1,f.count("monitor_rule"));assertEquals(0,f.count("conversation_frame"));
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());
    }

    @Test void crossExchangePlanCannotBecomeDraftAfterHandoff() {
        var invalid = candidate(); ((com.fasterxml.jackson.databind.node.ArrayNode) invalid.at("/plan/metrics/0/exchanges")).add("okx");
        var result = ask("两所都满足时通知我", invalid.toString());
        assertEquals("FAILED", result.turnStatus()); assertEquals(0, f.count("monitor_rule"));
        assertEquals("FAILED", result.agentPayload().at("/handoff/outcome").asText());
        assertTrue(events.stream().anyMatch(e -> "MONITOR_PLAN".equals(e.eventType()) && e.error() != null && e.error().contains("交易所")));
    }
}
