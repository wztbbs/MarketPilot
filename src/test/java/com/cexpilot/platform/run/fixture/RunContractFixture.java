package com.cexpilot.platform.run.fixture;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 非行情业务的测试接入；不加载 CEX、真实 LLM、交易所或邮件服务。 */
public final class RunContractFixture implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static final String TOOL = "contract.export";
    public record Input(String documentId, int version, Runnable requestCallback) implements AgentRunContext.PrivateInput {}
    public record Output(String documentId, String receipt, int nextVersion) implements AgentOutput {}

    public static final class BusinessAgent implements ToolBoundAgent {
        public AgentDefinition definition() { return new AgentDefinition("contract_business", "contract", "自驱动业务接入测试"); }
        public EngineKind engine() { return EngineKind.PLAN; }
        public String promptVersion() { return "contract-machine-v1"; }
        public Set<String> allowedTools() { return Set.of(TOOL); }
        public ExecutionResult execute(AgentRunContext context) { throw new AssertionError("应由业务运行适配器执行片段"); }
    }

    public static final class ExternalTool implements AsyncAgentTool {
        private final URI base;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        public ExternalTool(URI base) {
            if (!Set.of("127.0.0.1", "localhost").contains(base.getHost())) throw new IllegalArgumentException("测试只允许本地模拟服务");
            this.base = base;
        }
        public String name() { return TOOL; }
        public ToolExecution submit(JsonNode args, ToolContext context) {
            var request = HttpRequest.newBuilder(base.resolve("/jobs")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Idempotency-Key", context.invocationId())
                    .header("X-Agent", context.agentId()).header("X-Principal", context.visitorId())
                    .POST(HttpRequest.BodyPublishers.ofString(args.toString())).build();
            var response = send(request);
            return ToolExecution.accepted(response.path("jobId").asText(), 200);
        }
        public ToolExecution poll(String jobId, ToolContext context) {
            var response = send(HttpRequest.newBuilder(base.resolve("/jobs/" + jobId)).timeout(Duration.ofSeconds(3)).GET().build());
            return response.path("done").asBoolean()
                    ? ToolExecution.completed(ToolResult.success(response.path("data")))
                    : ToolExecution.accepted(jobId, 200);
        }
        public ToolExecution recover(JsonNode args, ToolContext context) {
            var response = send(HttpRequest.newBuilder(base.resolve("/by-key/" + context.invocationId())).timeout(Duration.ofSeconds(3)).GET().build());
            return response.hasNonNull("jobId") ? ToolExecution.accepted(response.path("jobId").asText(), 200) : ToolExecution.unknown(1000);
        }
        JsonNode plan(String documentId) {
            return send(HttpRequest.newBuilder(base.resolve("/plan/" + documentId)).timeout(Duration.ofSeconds(3)).GET().build());
        }
        private JsonNode send(HttpRequest request) {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("模拟服务返回错误");
                return JSON.readTree(response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("本地调用中断", interrupted);
            } catch (IOException error) { throw new IllegalStateException("本地调用失败", error); }
        }
    }

    public class BusinessAdapter implements AgentRunAdapter {
        private final Runnable afterCommit;
        public final AtomicInteger segmentCalls = new AtomicInteger();
        public BusinessAdapter(Runnable afterCommit) { this.afterCommit = afterCommit; }
        public String id() { return "contract-business"; }
        public String version() { return "business-state-v1"; }
        public boolean supports(ConversationAgent agent) { return agent instanceof BusinessAgent; }
        public QueryExecutionConfig limits(ConversationAgent agent) {
            var limits = new QueryExecutionConfig(); limits.setTimeoutMs(10000); return limits;
        }
        public JsonNode captureInput(ConversationAgent agent, AgentRunContext context) {
            var input = (Input) context.privateInput();
            return JSON.createObjectNode().put("documentId", input.documentId()).put("version", input.version());
        }
        public AgentRunContext.PrivateInput restoreInput(ConversationAgent agent, JsonNode snapshot) {
            if (!snapshot.path("documentId").isTextual() || !snapshot.path("version").isInt()) throw new IllegalArgumentException("业务输入快照不合法");
            return new Input(snapshot.path("documentId").asText(), snapshot.path("version").asInt(), null);
        }
        public RunStep executeSegment(ConversationAgent agent, RunSegment segment) {
            segmentCalls.incrementAndGet();
            var run = segment.context(); var input = (Input) run.privateInput();
            if (input.requestCallback() != null) throw new AssertionError("回调不应被序列化并恢复");
            var checkpoint = segment.checkpoint();
            if (checkpoint == null) {
                var plan = external.plan(input.documentId());
                segment.checkpoint(new RunCheckpoint("WAITING_EXPORT", JSON.createObjectNode()
                        .put("documentId", input.documentId()).put("version", input.version()).set("acceptedPlan", plan)));
                checkpoint = segment.checkpoint();
            }
            if ("EXPORT_COMPLETE".equals(checkpoint.phase())) {
                var output=JSON.convertValue(checkpoint.state(),Output.class);
                return RunStep.completed(new ExecutionResult("导出完成",JSON.createArrayNode(),1,1,0,0).withOutput(output));
            }
            if (!"WAITING_EXPORT".equals(checkpoint.phase())) throw new IllegalStateException("业务阶段不匹配");
            var args = JSON.createObjectNode().put("documentId", checkpoint.state().path("documentId").asText())
                    .put("version", checkpoint.state().path("version").asInt());
            ToolResult result;
            try {
                result = tools.invoke(TOOL, args, new ToolContext(run.traceId(), run.session() == null ? null : run.session().conversationId(),
                        run.request().userZone(), run.request().requestTime(), agent.definition().id(), run.visitor(), ((ToolBoundAgent) agent).allowedTools()), "export-document");
            } catch (RunSuspended waiting) { return RunStep.waiting(waiting.invocationId()); }
            if (!result.ok()) return RunStep.completed(new ExecutionResult("导出未完成", JSON.createArrayNode(), 1, 1, 0, 0, "FAILED", null));
            var output = new Output(input.documentId(), result.data().path("receipt").asText(), input.version() + 1);
            segment.checkpoint(new RunCheckpoint("EXPORT_COMPLETE", JSON.valueToTree(output)));
            return RunStep.completed(new ExecutionResult("导出完成", JSON.createArrayNode(), 1, 1, 0, 0).withOutput(output));
        }
        public JsonNode captureOutput(AgentOutput output) { return output == null ? null : JSON.valueToTree((Output) output); }
        public AgentOutput restoreOutput(JsonNode snapshot) { return snapshot == null || snapshot.isNull() ? null : JSON.convertValue(snapshot, Output.class); }
        public boolean commit(RunCompletion completion) {
            if (completion.failed()) return true;
            var output = (Output) completion.result().output(); var input = (Input) completion.context().privateInput();
            if (output == null || input.requestCallback() != null || !"EXPORT_COMPLETE".equals(completion.checkpoint().phase()))
                throw new AssertionError("业务提交必须获得恢复后的私有输入、输出与检查点");
            new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).executeWithoutResult(tx -> {
                jdbc.update("INSERT INTO run_contract_commit_attempt(run_id) VALUES (?)", completion.runId());
                try {
                    jdbc.update("INSERT INTO run_contract_business_result(run_id,document_id,receipt,next_version,principal_id,request_time) VALUES (?,?,?,?,?,?)",
                            completion.runId(), output.documentId(), output.receipt(), output.nextVersion(), completion.context().visitor(), completion.context().request().requestTime().toString());
                } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                    var prior = jdbc.queryForMap("SELECT * FROM run_contract_business_result WHERE run_id=?", completion.runId());
                    if (!output.documentId().equals(prior.get("document_id")) || !output.receipt().equals(prior.get("receipt")))
                        throw new IllegalStateException("重复提交的业务内容不一致");
                }
            });
            afterCommit.run(); return true;
        }
    }

    public final JdbcTemplate jdbc;
    public final DurableStore store;
    public final ExternalTool external;
    public final ToolRegistry tools;
    public final ToolGateway gateway;
    public final BusinessAgent agent = new BusinessAgent();
    public final BusinessAdapter adapter;
    public final RunCoordinator runs;
    public final RunReconciler reconciler;
    private final Clock clock;
    public RunContractFixture(JdbcTemplate jdbc, URI service, Clock clock, Runnable afterCommit, boolean confirmation) {
        this.jdbc = jdbc; this.clock = clock; store = new DurableStore(jdbc, JSON); external = new ExternalTool(service);
        var definition = new ToolDefinition(TOOL, true, "异步导出文档", parse("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"documentId\",\"version\"],\"properties\":{\"documentId\":{\"type\":\"string\"},\"version\":{\"type\":\"integer\"}}}"),
                parse("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"receipt\"],\"properties\":{\"receipt\":{\"type\":\"string\"}}}"), null,
                new ToolControl(ToolControl.Kind.OPERATION, confirmation, "导出指定版本文档", List.of(), true, 86400));
        tools = new ToolRegistry(List.of(external), List.of(definition));
        var agents = new AgentRegistry(List.of(agent)); var factory = new DefaultListableBeanFactory(); factory.registerSingleton("agents", agents);
        gateway = new ToolGateway(tools, store, JSON, clock, List.of(new RegisteredAgentToolPolicy(factory.getBeanProvider(AgentRegistry.class))), List.of());
        factory.registerSingleton("gateway", gateway); ReflectionTestUtils.setField(tools, "gateway", factory.getBeanProvider(ToolGateway.class));
        adapter = new BusinessAdapter(afterCommit);
        runs = new RunCoordinator(store, agents, new RunAdapterRegistry(List.of(adapter)), JSON, clock, e -> {}, List.of());
        reconciler = new RunReconciler(store, gateway, runs, clock);
    }
    public ExecutionResult start(String documentId) {
        return runs.execute(agent, new AgentRunContext("导出文档", "原业务历史", "trace-" + documentId, e -> {}, null,
                new RequestContext(ZoneId.of("Asia/Shanghai"), clock.instant()), null, null, JSON.createObjectNode(), JSON.createObjectNode(),
                "alice", new Input(documentId, 7, () -> { throw new AssertionError("请求回调不能进入后台运行"); })));
    }
    public static void schema(JdbcTemplate jdbc) {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/schema.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.execute("CREATE TABLE IF NOT EXISTS run_contract_business_result (run_id VARCHAR(64) PRIMARY KEY,document_id VARCHAR(64) NOT NULL,receipt VARCHAR(100) NOT NULL,next_version INT NOT NULL,principal_id VARCHAR(64) NOT NULL,request_time VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS run_contract_commit_attempt (id BIGINT AUTO_INCREMENT PRIMARY KEY,run_id VARCHAR(64) NOT NULL)");
    }
    private static JsonNode parse(String text) { try { return JSON.readTree(text); } catch (IOException e) { throw new IllegalArgumentException(e); } }
    public void close() { reconciler.close(); gateway.close(); }
}
