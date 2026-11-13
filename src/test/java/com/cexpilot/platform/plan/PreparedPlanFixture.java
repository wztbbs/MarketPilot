package com.cexpilot.platform.plan;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 两个业务操作：异步创建工单、按创建结果指派人员；全部使用本地 H2 和内存外部任务。 */
final class PreparedPlanFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final class ClockControl extends Clock {
        volatile Instant now = Instant.parse("2026-11-13T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static final class Environment {
        final ClockControl clock = new ClockControl();
        final AtomicInteger submits = new AtomicInteger(), polls = new AtomicInteger(), recovers = new AtomicInteger(), assigns = new AtomicInteger(), notifies = new AtomicInteger(), preparations = new AtomicInteger(), reads = new AtomicInteger(), version = new AtomicInteger(1);
        final Map<String, String> jobs = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean done, revoked, failAssign, loseSubmitResponse;
        volatile String contract = "v1";
        volatile JsonNode assignedArguments, submittedArguments;
        volatile java.math.BigDecimal scalar;
        volatile boolean includeNotify;
        volatile boolean confirmationRequired = true;
        JsonNode currentVersion() { return scalar == null ? JSON.valueToTree(version.get()) : JSON.valueToTree(scalar); }
    }
    final Environment external;
    final JdbcTemplate jdbc;
    final DurableStore store;
    final ToolRegistry tools;
    final PreparedPlanService plans;
    final ToolGateway gateway;
    final PreparedPlanExecutor executor;
    final RunCoordinator runs;
    final RunReconciler reconciler;
    final ToolBoundAgent agent;
    final AgentRunAdapter adapter;

    PreparedPlanFixture() { this(database(), new Environment()); }
    PreparedPlanFixture(JdbcTemplate jdbc, Environment external) {
        this.jdbc = jdbc; this.external = external;
        com.cexpilot.platform.run.fixture.RunContractFixture.schema(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS prepared_plan_receipt (run_id VARCHAR(64) PRIMARY KEY, result TEXT NOT NULL)");
        store = new DurableStore(jdbc, JSON);
        AgentTool create = new AsyncAgentTool() {
            public String name() { return "sample.create"; }
            public ToolExecution submit(JsonNode args, ToolContext context) {
                external.submits.incrementAndGet();
                external.submittedArguments = args.deepCopy();
                if (!"alice".equals(context.visitorId()) || !"sample".equals(context.agentId()) || context.invocationId() == null) throw new AssertionError("调用身份丢失");
                String job = external.jobs.computeIfAbsent(context.invocationId(), key -> "job-" + key);
                if (external.loseSubmitResponse) { external.loseSubmitResponse = false; throw new IllegalStateException("外部已受理，响应丢失"); }
                return ToolExecution.accepted(job, 100);
            }
            public ToolExecution poll(String job, ToolContext context) {
                external.polls.incrementAndGet();
                if (!job.equals(external.jobs.get(context.invocationId()))) throw new AssertionError("异步任务归属错误");
                var result = JSON.createObjectNode().put("id", "ticket-1"); result.set("version", external.currentVersion());
                return external.done ? ToolExecution.completed(ToolResult.success(result)) : ToolExecution.accepted(job, 100);
            }
            public ToolExecution recover(JsonNode args, ToolContext context) {
                external.recovers.incrementAndGet(); var job = external.jobs.get(context.invocationId());
                return job == null ? ToolExecution.unknown(100) : ToolExecution.accepted(job, 100);
            }
        };
        AgentTool assign = new AgentTool() {
            public String name() { return "sample.assign"; }
            public ToolResult execute(JsonNode args, ToolContext context) {
                external.assigns.incrementAndGet(); external.assignedArguments = args.deepCopy();
                if (external.failAssign) return ToolResult.failure("assignment refused");
                return ToolResult.success(JSON.createObjectNode().put("assigned", true));
            }
        };
        AgentTool notify = new AgentTool() {
            public String name() { return "sample.notify"; }
            public ToolResult execute(JsonNode args, ToolContext context) { external.notifies.incrementAndGet(); return ToolResult.success(JSON.createObjectNode().put("sent", true)); }
        };
        AgentTool read = new AgentTool() {
            public String name() { return "sample.read"; }
            public ToolResult execute(JsonNode args, ToolContext context) { external.reads.incrementAndGet(); var data = JSON.createObjectNode(); data.set("version", external.currentVersion()); return ToolResult.success(data); }
        };
        tools = new ToolRegistry(List.of(create, assign, notify, read), List.of(
                definition("sample.create", "{\"name\":{\"type\":\"string\"},\"version\":{\"type\":\"integer\"}}", "[\"name\",\"version\"]", "{\"id\":{\"type\":\"string\"},\"version\":{\"type\":\"integer\"}}", List.of("/version"), external.contract),
                definition("sample.assign", "{\"ticketId\":{\"type\":\"string\"},\"expectedVersion\":{\"type\":\"integer\"},\"assignee\":{\"type\":\"string\"}}", "[\"ticketId\",\"expectedVersion\",\"assignee\"]", "{\"assigned\":{\"type\":\"boolean\"}}", List.of("/expectedVersion"), "v1"),
                definition("sample.notify", "{}", "[]", "{\"sent\":{\"type\":\"boolean\"}}", List.of(), "v1"),
                new ToolDefinition("sample.read", true, "读取版本", schema("{}", "[]"), schema("{\"version\":{\"type\":\"" + (external.scalar == null ? "integer" : "number") + "\"}}", "[]"), null)));
        agent = new ToolBoundAgent() {
            public AgentDefinition definition() { return new AgentDefinition("sample", "sample", "示例业务"); }
            public EngineKind engine() { return EngineKind.PLAN; }
            public String promptVersion() { return "sample-prepared-v1"; }
            public Set<String> allowedTools() { return external.revoked ? Set.of("sample.read") : Set.of("sample.create", "sample.assign", "sample.notify", "sample.read"); }
            public ExecutionResult execute(AgentRunContext run) { throw new AssertionError("业务必须走运行适配器"); }
        };
        var agents = new AgentRegistry(List.of(agent)); var factory = new DefaultListableBeanFactory(); factory.registerSingleton("agents", agents);
        List<ToolAccessPolicy> policies = List.of(new RegisteredAgentToolPolicy(factory.getBeanProvider(AgentRegistry.class)));
        ToolResourceGuard versions = new ToolResourceGuard() {
            public boolean supports(String tool) { return tool.equals("sample.create") || tool.equals("sample.assign"); }
            public Map<String, JsonNode> currentVersions(String tool, JsonNode args, ToolContext context) {
                return Map.of(tool.equals("sample.create") ? "/version" : "/expectedVersion", external.currentVersion());
            }
        };
        plans = new PreparedPlanService(tools, store, JSON, external.clock, policies, List.of(versions));
        gateway = new ToolGateway(tools, store, JSON, external.clock, policies, List.of(versions), plans);
        factory.registerSingleton("gateway", gateway); ReflectionTestUtils.setField(tools, "gateway", factory.getBeanProvider(ToolGateway.class));
        executor = new PreparedPlanExecutor(plans, gateway, store);
        adapter = new AgentRunAdapter() {
            public String id() { return "sample-prepared"; }
            public String version() { return "v1"; }
            public boolean supports(ConversationAgent candidate) { return candidate.definition().id().equals("sample"); }
            public QueryExecutionConfig limits(ConversationAgent candidate) { return new QueryExecutionConfig(); }
            public ToolApprovalMode toolApprovalMode(ConversationAgent candidate) { return ToolApprovalMode.PREPARED_PLAN; }
            public JsonNode captureInput(ConversationAgent candidate, AgentRunContext run) { return JSON.createObjectNode(); }
            public AgentRunContext.PrivateInput restoreInput(ConversationAgent candidate, JsonNode input) { return null; }
            public RunStep executeSegment(ConversationAgent candidate, RunSegment segment) {
                var checkpoint = segment.checkpoint();
                if (checkpoint != null && checkpoint.phase().equals("COMPLETE")) return RunStep.completed(JSON.convertValue(checkpoint.state().get("result"), ExecutionResult.class));
                DurableStore.Entry proposal;
                if (checkpoint == null) {
                    external.preparations.incrementAndGet(); proposal = plans.prepare("stage_1", proposal(external.includeNotify));
                    segment.checkpoint(new RunCheckpoint("PLAN_READY", JSON.createObjectNode().put("planId", proposal.id())));
                } else proposal = plans.forRun(checkpoint.state().path("planId").asText(), segment.runId(), "alice");
                if (plans.approval(proposal) == PlanApprovalStatus.PENDING) return RunStep.waitingForApproval(proposal.id());
                if (plans.approval(proposal) != PlanApprovalStatus.APPROVED) return RunStep.completed(result("方案未执行", true));
                var outcome = executor.execute(proposal.id());
                var result = result(outcome.status() == PlanExecutionStatus.SUCCEEDED ? "工单已创建并指派" : "方案部分执行，后续已停止", outcome.status() != PlanExecutionStatus.SUCCEEDED);
                var state = JSON.createObjectNode(); state.set("result", JSON.valueToTree(result));
                segment.checkpoint(new RunCheckpoint("COMPLETE", state)); return RunStep.completed(result);
            }
            public boolean commit(RunCompletion completion) {
                jdbc.update("INSERT IGNORE INTO prepared_plan_receipt(run_id,result) VALUES (?,?)", completion.runId(), JSON.valueToTree(completion.result()).toString());
                return true;
            }
        };
        runs = new RunCoordinator(store, agents, new RunAdapterRegistry(List.of(adapter)), JSON, external.clock, event -> {}, List.of(), plans);
        reconciler = new RunReconciler(store, gateway, runs, external.clock, plans);
    }
    ExecutionResult start() {
        return runs.execute(agent, new AgentRunContext("创建工单并指派给张三", "", "prepared-trace", event -> {}, null,
                new RequestContext(ZoneOffset.UTC, external.clock.instant()), null, null, JSON.createObjectNode(), JSON.createObjectNode(), "alice", null));
    }
    DurableStore.Entry plan(String runId) { return store.plans(runId).get(0); }
    RunScope attach(String runId) {
        var now = external.clock.instant();
        store.wake(runId, now);
        var entry = store.claim(DurableStore.Kind.RUN, runId, now, now.plusSeconds(60));
        if (entry == null) throw new AssertionError("测试无法领取运行");
        if (!store.update(DurableStore.Kind.RUN, entry, RunStatus.RUNNING.name(), entry.nextAttempt(), entry.leaseUntil(), entry.data())) throw new AssertionError("无法进入运行态");
        return new RunScope(store, JSON, external.clock, runId, entry.fence());
    }
    static PreparedPlan proposal(boolean includeNotify) {
        var calls = new ArrayList<PreparedToolCall>();
        calls.add(new PreparedToolCall("create", "sample.create", json("{\"name\":\"待处理工单\",\"version\":1}"), List.of()));
        calls.add(new PreparedToolCall("assign", "sample.assign", json("{\"ticketId\":\"{{create.data.id}}\",\"expectedVersion\":\"{{create.data.version}}\",\"assignee\":\"张三\"}"), List.of("create")));
        if (includeNotify) calls.add(new PreparedToolCall("notify", "sample.notify", json("{}"), List.of("assign")));
        return new PreparedPlan("创建工单并指派人员", calls, 86400);
    }
    static JdbcTemplate database() { return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")); }
    static JsonNode json(String text) { try { return JSON.readTree(text); } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException(failure); } }
    private ToolDefinition definition(String name, String input, String required, String output, List<String> versions, String contract) {
        if (external.scalar != null) { input = input.replace("\"integer\"", "\"number\""); output = output.replace("\"integer\"", "\"number\""); }
        return new ToolDefinition(name, true, name, schema(input, required), schema(output, "[]"), null,
                new ToolControl(ToolControl.Kind.OPERATION, external.confirmationRequired, name + ":" + contract, versions, true, 86400));
    }
    private static JsonNode schema(String properties, String required) { return json("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":" + properties + ",\"required\":" + required + "}"); }
    private static ExecutionResult result(String answer, boolean failed) { return new ExecutionResult(answer, JSON.createArrayNode(), 0, 0, 0, 0, failed ? "FAILED" : null, null); }
    public void close() { reconciler.close(); gateway.close(); }
}
