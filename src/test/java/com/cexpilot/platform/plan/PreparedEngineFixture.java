package com.cexpilot.platform.plan;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.engine.react.ReactEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用真实声明式引擎、真实运行协调器；模型为脚本，工具为本地模拟。 */
final class PreparedEngineFixture implements AutoCloseable {
    static final String READ = "{\"action\":\"call\",\"tool\":\"sample.read\",\"arguments\":{}}";
    static final String PROPOSE = "{\"action\":\"propose\",\"summary\":\"创建并指派工单\",\"calls\":[{\"id\":\"create\",\"tool\":\"sample.create\",\"arguments\":{\"name\":\"任务\",\"version\":{\"$ref\":\"e1#/version\"}},\"depends_on\":[]},{\"id\":\"assign\",\"tool\":\"sample.assign\",\"arguments\":{\"ticketId\":\"{{create.data.id}}\",\"expectedVersion\":\"{{create.data.version}}\",\"assignee\":\"张三\"},\"depends_on\":[\"create\"]}]}";
    static final String PLAN = "{\"nodes\":[{\"id\":\"create\",\"tool\":\"sample.create\",\"args\":{\"name\":\"任务\",\"version\":1}},{\"id\":\"assign\",\"tool\":\"sample.assign\",\"args\":{\"ticketId\":\"{{create.data.id}}\",\"expectedVersion\":\"{{create.data.version}}\",\"assignee\":\"张三\"}}]}";
    static final class Script {
        final Deque<Object> outputs = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final List<List<ChatMessage>> messages = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        Script(Object... responses) { outputs.addAll(List.of(responses)); }
    }
    final PreparedPlanFixture base;
    final Script script;
    final DeclaredAgent agent;
    final DagExecutor dag;
    final RunCoordinator runs;
    final RunReconciler reconciler;
    final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
    final PromptStore prompts;
    PreparedEngineFixture(EngineKind kind, Script script) { this(PreparedPlanFixture.database(), new PreparedPlanFixture.Environment(), kind, script); }
    PreparedEngineFixture(JdbcTemplate db, PreparedPlanFixture.Environment external, EngineKind kind, Script script) {
        this(db, external, kind, script, ToolApprovalMode.PREPARED_PLAN);
    }
    PreparedEngineFixture(JdbcTemplate db, PreparedPlanFixture.Environment external, EngineKind kind, Script script, ToolApprovalMode approvalMode) {
        base = new PreparedPlanFixture(db, external); this.script = script;
        prompts = new PromptStore(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                return location.equals("classpath:prompts/sample_business.txt") ? new ByteArrayResource("只处理模拟工单，按用户请求创建并指派，不能擅自通知。".getBytes(StandardCharsets.UTF_8)) : super.getResource(location);
            }
        });
        var llm = new LlmCallService((messages, specs) -> {
            script.calls.incrementAndGet(); script.messages.add(List.copyOf(messages)); Object output = script.outputs.removeFirst();
            if (output instanceof RuntimeException failure) throw failure;
            return new ChatResponse((String) output, List.of(), 10, 5);
        }, new LlmConfig(), prompts);
        var limits = new QueryExecutionConfig(); limits.setMaxModelCalls(16); limits.setMaxToolCalls(16);
        agent = new DeclaredAgent(new ConfiguredAgentDefinition(new AgentDefinition("sample", "sample", "模拟工单"), true, kind,
                "sample_business", Set.of("sample.create", "sample.assign", "sample.notify", "sample.read"), 8, limits, approvalMode), base.tools, prompts);
        dag = new DagExecutor(base.tools, new DagConfig());
        var react = new ReactEngine(base.tools, prompts, llm, null, base.plans, base.executor);
        var plan = new PlanEngine(base.tools, prompts, llm, null, dag, base.plans, base.executor);
        runs = new RunCoordinator(base.store, new AgentRegistry(List.of(agent)), new RunAdapterRegistry(List.of(new DeclaredAgentRunAdapter(react, plan))),
                PreparedPlanFixture.JSON, external.clock, events::add, List.of(), base.plans);
        reconciler = new RunReconciler(base.store, base.gateway, runs, external.clock, base.plans);
    }
    ExecutionResult start() {
        return runs.execute(agent, new AgentRunContext("创建一个工单并指派给张三", "", "engine-prepared-trace", events::add, null,
                new RequestContext(ZoneOffset.UTC, base.external.clock.instant()), null, null, PreparedPlanFixture.JSON.createObjectNode(), PreparedPlanFixture.JSON.createObjectNode(), "alice", null));
    }
    void approve(String runId) {
        var entry = base.store.plans(runId).stream().filter(plan -> plan.status().equals("PENDING")).findFirst().orElseThrow();
        base.plans.confirm(entry.id(), "alice", entry.data().path("fingerprint").asText(), true);
    }
    public void close() { reconciler.close(); dag.close(); base.close(); }
}
