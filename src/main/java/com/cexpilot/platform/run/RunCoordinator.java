package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.plan.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.cexpilot.platform.run.DurableStore.Kind.RUN;

/** 持久化运行协调器。执行方式、私有状态编解码及业务提交均由已注册的适配器提供。 */
@Component
public class RunCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunCoordinator.class);
    public static final String PROTOCOL = "agent-run-v2";
    private final DurableStore store;
    private final AgentRegistry agents;
    private final RunAdapterRegistry adapters;
    private final ObjectMapper json;
    private final Clock clock;
    private final TraceSink sink;
    private final List<RunCompletionListener> listeners;
    private final PreparedPlanService plans;

    public RunCoordinator(DurableStore store, AgentRegistry agents, RunAdapterRegistry adapters,
                          ObjectMapper json, Clock clock, TraceSink sink, List<RunCompletionListener> listeners) {
        this(store, agents, adapters, json, clock, sink, listeners, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RunCoordinator(DurableStore store, AgentRegistry agents, RunAdapterRegistry adapters,
                          ObjectMapper json, Clock clock, TraceSink sink, List<RunCompletionListener> listeners, PreparedPlanService plans) {
        this.store = store; this.agents = agents; this.adapters = adapters;
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); this.clock = clock; this.sink = sink; this.listeners = List.copyOf(listeners);
        this.plans = plans;
    }
    public boolean supports(ConversationAgent agent) { return adapters.find(agent).isPresent(); }

    public ExecutionResult execute(ConversationAgent agent, AgentRunContext context) {
        if (context.visitor() == null || !context.visitor().matches("[A-Za-z0-9_-]{1,64}"))
            throw new SecurityException("可恢复运行需要可信用户身份");
        var registered = agents.requireAllowed(agent.definition().route(), context.visitor());
        if (!registered.definition().id().equals(agent.definition().id())) throw new SecurityException("Agent 注册身份不一致");
        var adapter = adapters.find(registered).orElseThrow(() -> new IllegalArgumentException("Agent 没有持久化运行适配器"));
        if (adapter.toolApprovalMode(registered) == ToolApprovalMode.PREPARED_PLAN && plans == null)
            throw new IllegalStateException("方案审批服务未装配");
        var limits = adapter.limits(registered); limits.validate();
        var budget = QueryBudget.current(); if (budget != null) budget.tighten(limits);
        // 先捕获业务数据；回调与运行对象不得进入输入快照，首段也从快照恢复以尽早验证编解码。
        var input = adapter.captureInput(registered, context);
        String id = UUID.randomUUID().toString();
        var data = json.createObjectNode().put("protocol", PROTOCOL)
                .put("adapterId", adapter.id()).put("adapterVersion", adapter.version())
                .put("agentId", registered.definition().id()).put("route", registered.definition().route())
                .put("agentVersion", registered.promptVersion()).put("engine", registered.engine().name())
                .put("toolApprovalMode", adapter.toolApprovalMode(registered).name())
                .put("question", context.question()).put("history", context.history()).put("traceId", context.traceId())
                .put("timezone", context.request().userZone() == null ? "UTC" : context.request().userZone().getId())
                .put("requestTime", context.request().requestTime().toString()).put("createdAt", clock.instant().toString())
                .put("expiresAt", clock.instant().plusSeconds(604800).toString());
        data.set("privateInput", input == null ? json.nullNode() : input.deepCopy());
        data.set("allowedTools", json.valueToTree(registered instanceof ToolBoundAgent bound ? bound.allowedTools() : Set.of()));
        data.set("session", json.valueToTree(context.session())); data.set("routingState", context.routingState());
        data.set("conversationContext", context.conversationContext());
        data.set("limits", json.valueToTree(budget == null ? limits : budget.config()));
        if (budget != null) data.set("budget", budget.snapshot());
        store.create(RUN, id, context.visitor(), null, RunStatus.READY.name(), clock.instant(), data);
        drive(id, context);
        var entry = store.require(RUN, id);
        return entry.data().has("result") ? result(entry) : waiting(entry);
    }

    public void drive(String id) { drive(id,null); }
    private void drive(String id, AgentRunContext live) {
        var before = store.require(RUN, id);
        long timeout = before.data().path("limits").path("timeoutMs").asLong(180000);
        var entry = store.claim(RUN, id, clock.instant(), clock.instant().plusMillis(timeout + 60000));
        if (entry == null) return;
        if (entry.data().has("result")) { publish(entry); return; }
        AgentRunAdapter adapter = null;
        try {
            var data = entry.data();
            if (!PROTOCOL.equals(data.path("protocol").asText())) throw new IllegalStateException("运行协议版本不匹配");
            if (!clock.instant().isBefore(Instant.parse(data.path("expiresAt").asText()))) throw new IllegalStateException("运行已过期");
            var agent = agent(entry); adapter = adapter(entry, agent);
            if (!adapter.toolApprovalMode(agent).name().equals(data.path("toolApprovalMode").asText(ToolApprovalMode.PER_CALL.name())))
                throw new IllegalStateException("运行审批方式已变化，拒绝恢复执行");
            if (!agent.promptVersion().equals(data.path("agentVersion").asText()))
                throw new IllegalStateException("Agent 版本已变化，拒绝恢复执行");
            if (awaitingPlanApproval(entry) || store.calls(id).stream().anyMatch(call -> !call.data().has("result"))) {
                store.update(RUN, entry, RunStatus.WAITING.name(), clock.instant().plusSeconds(15), null, data); return;
            }
            data.remove(List.of("waitTarget", "waitingInvocationId"));
            if (!store.update(RUN, entry, RunStatus.RUNNING.name(), clock.instant(), entry.leaseUntil(), data)) return;
            var running=store.require(RUN,id);if(running.fence()!=entry.fence())return;entry=running;
            var limits = json.convertValue(data.path("limits"), QueryExecutionConfig.class);
            try (var budget = QueryBudget.resume(limits, data.get("budget")); var scope = new RunScope(store, json, clock, id, entry.fence())) {
                var step = Objects.requireNonNull(adapter.executeSegment(agent, new RunSegment(context(entry, adapter, agent, live), scope)), "运行片段必须返回结论");
                scope.saveBudget();
                if (step instanceof RunStep.Completed completed) {
                    if(store.calls(id).stream().anyMatch(call->!call.data().has("result")))
                        throw new IllegalStateException("运行片段返回完成时仍有未完成调用");
                    if (plans != null && plans.hasUnfinishedPlans(id)) throw new IllegalStateException("运行返回完成时仍有未完成方案");
                    complete(entry, completed.result(), "FAILED".equals(completed.result().turnStatus()) ? RunStatus.FAILED : RunStatus.SUCCEEDED, adapter);
                } else if (step instanceof RunStep.Waiting waiting) {
                    suspend(entry, waiting.target());
                }
            }
        } catch (RunLeaseLostException lost) {
            log.debug("运行已被接管，停止旧执行者 runId={} fence={}", id, entry.fence());
        } catch (RunSuspended waiting) {
            // 现有声明式引擎以控制信号退出调用栈；业务适配器也可显式返回 RunStep.Waiting。
            try { suspend(entry, waiting.target()); }
            catch (RuntimeException invalid) { fail(entry, invalid, adapter); }
        } catch (RuntimeException failure) { fail(entry, failure, adapter); }
    }

    private boolean awaitingPlanApproval(DurableStore.Entry entry) {
        var value = entry.data().get("waitTarget");
        if (value == null) return false;
        var target = json.convertValue(value, RunWaitTarget.class);
        return target.kind() == RunWaitTarget.Kind.PLAN_APPROVAL && requirePlans().awaitingApproval(target.id(), entry.id(), entry.owner());
    }

    private void suspend(DurableStore.Entry claimed, RunWaitTarget target) {
        if (target.kind() == RunWaitTarget.Kind.PLAN_APPROVAL) requirePlans().forRun(target.id(), claimed.id(), claimed.owner());
        else {
            var call = store.require(DurableStore.Kind.CALL, target.id());
            if (!claimed.id().equals(call.runId()) || !claimed.owner().equals(call.owner())) throw new SecurityException("等待调用不属于本次运行");
        }
        var entry = store.require(RUN, claimed.id());
        if (entry.fence() != claimed.fence() || entry.data().has("result")) return;
        var data = entry.data(); data.set("waitTarget", json.valueToTree(target));
        if (target.kind() == RunWaitTarget.Kind.TOOL_CALL) data.put("waitingInvocationId", target.id());
        else data.remove("waitingInvocationId");
        store.update(RUN, entry, RunStatus.WAITING.name(), clock.instant().plusSeconds(15), null, data);
    }

    private PreparedPlanService requirePlans() {
        if (plans == null) throw new IllegalStateException("方案审批服务未装配");
        return plans;
    }

    private void fail(DurableStore.Entry entry, RuntimeException failure, AgentRunAdapter adapter) {
        log.error("运行执行失败 runId={} traceId={}", entry.id(), entry.data().path("traceId").asText(), failure);
        org.slf4j.LoggerFactory.getLogger(RunCoordinator.class).warn("运行 {} 无法继续", entry.id(), failure);
        var payload = json.createObjectNode().put("status", "FAILED").put("runId", entry.id())
                .put("error", "运行无法继续，请查看执行记录或重新发起请求");
        ExecutionResult failed;
        if (failure instanceof ExecutionFailure counted) {
            var c = counted.counters; var usages = c.llmUsages();
            failed = new ExecutionResult("本次运行未完成，请查看执行记录。", counted.evidence == null ? json.createArrayNode() : counted.evidence,
                    c.toolCallCount(), c.plannerLlmCalls() + c.answerLlmCalls(), usages == null ? 0 : com.cexpilot.platform.llm.LlmUsage.knownPromptTokens(usages),
                    usages == null ? 0 : com.cexpilot.platform.llm.LlmUsage.knownCompletionTokens(usages), "FAILED", null, c.reusedFactCount(),
                    c.plannerLlmCalls(), c.answerLlmCalls(), c.metricFetchCount(), c.calculationCount(), c.httpRequestCount(), c.savedFactIds()).withLlmUsages(usages).withOutput(counted.output);
        } else failed = new ExecutionResult("本次运行未完成，请查看执行记录。", json.createArrayNode(), 0, 0, 0, 0, "FAILED", null);
        complete(entry, failed.withAgent(entry.data().path("route").asText(), payload), RunStatus.FAILED, adapter);
    }

    private void complete(DurableStore.Entry claimed, ExecutionResult result, RunStatus status, AgentRunAdapter adapter) {
        var entry = store.require(RUN, claimed.id());
        if (entry.fence() != claimed.fence() || entry.leaseUntil() == null || !entry.leaseUntil().isAfter(clock.instant())) return;
        var calls = store.calls(entry.id());
        result = result.withToolCounts(Math.max(result.toolCallCount(),calls.size()),Math.max(result.httpRequestCount(), calls.stream().mapToInt(call -> call.data().path("httpRequests").asInt()).sum()));
        var data = entry.data();
        var payload = result.agentPayload() == null ? json.createObjectNode() : (ObjectNode) result.agentPayload().deepCopy();
        payload.put("runId", entry.id());
        data.set("privateOutput", adapter == null ? json.nullNode() : adapter.captureOutput(result.output()));
        // 公共结果不携带任意业务接口类型；私有结果只能由原适配器显式恢复。
        data.set("result", json.valueToTree(result.withOutput(null).withAgent(entry.data().path("route").asText(), payload)));
        data.put("completedAt", clock.instant().toString());
        store.update(RUN, entry, status.name(), clock.instant(), null, data);
    }

    private void publish(DurableStore.Entry claimed) {
        var entry = claimed;
        try {
            var agent = agent(entry); var adapter = adapter(entry, agent); var result = result(entry);
            if (!entry.data().path("businessCommitted").asBoolean()) {
                var checkpoint = checkpoint(entry);
                if (!adapter.commit(new RunCompletion(entry.id(), context(entry, adapter, agent), checkpoint, result, RunStatus.valueOf(entry.status()) == RunStatus.FAILED))) {
                    retryPublication(entry); return;
                }
                var data = entry.data(); data.put("businessCommitted", true);
                if (!store.update(RUN, entry, entry.status(), entry.nextAttempt(), entry.leaseUntil(), data)) return;
                var updated=store.require(RUN,entry.id());if(updated.fence()!=claimed.fence())return;entry=updated;
            }
            for (var listener : listeners) if (!listener.completed(entry, result)) { retryPublication(entry); return; }
            var data = entry.data(); data.put("completionPublished", true);
            store.update(RUN, entry, entry.status(), null, null, data);
        } catch (RuntimeException retry) {
            log.warn("运行结果提交失败，等待重试 runId={} traceId={}", entry.id(), entry.data().path("traceId").asText(), retry);
            retryPublication(entry);
        }
    }
    private void retryPublication(DurableStore.Entry entry) {
        store.update(RUN, entry, entry.status(), clock.instant().plusSeconds(15), null, entry.data());
    }
    private ConversationAgent agent(DurableStore.Entry entry) {
        var agent = agents.requireAllowed(entry.data().path("route").asText(), entry.owner());
        if (!agent.definition().id().equals(entry.data().path("agentId").asText())) throw new SecurityException("运行的 Agent 身份已变化");
        return agent;
    }
    private AgentRunAdapter adapter(DurableStore.Entry entry, ConversationAgent agent) {
        return adapters.require(entry.data().path("adapterId").asText(), entry.data().path("adapterVersion").asText(), agent);
    }
    private AgentRunContext context(DurableStore.Entry entry, AgentRunAdapter adapter, ConversationAgent agent) { return context(entry,adapter,agent,null); }
    private AgentRunContext context(DurableStore.Entry entry, AgentRunAdapter adapter, ConversationAgent agent, AgentRunContext live) {
        var data = entry.data();
        return new AgentRunContext(data.path("question").asText(), data.path("history").asText(null), data.path("traceId").asText(), sink,
                live==null?null:live.delta(), new RequestContext(ZoneId.of(data.path("timezone").asText()), Instant.parse(data.path("requestTime").asText())), live==null?null:live.progress(),
                data.path("session").isNull() ? null : json.convertValue(data.get("session"), EvidenceSession.class), data.get("routingState"),
                data.get("conversationContext"), entry.owner(), adapter.restoreInput(agent, data.get("privateInput")));
    }
    private RunCheckpoint checkpoint(DurableStore.Entry entry) {
        var checkpoint = entry.data().get("checkpoint");
        return checkpoint == null || checkpoint.isNull() ? null : json.convertValue(checkpoint, RunCheckpoint.class);
    }
    private ExecutionResult result(DurableStore.Entry entry) {
        var result = json.convertValue(entry.data().get("result"), ExecutionResult.class);
        var privateOutput = entry.data().get("privateOutput");
        return privateOutput == null || privateOutput.isNull() ? result
                : result.withOutput(adapter(entry, agent(entry)).restoreOutput(privateOutput));
    }
    public DurableStore.Entry owned(String id, String owner) {
        var entry = store.find(RUN, id);
        if (entry == null || owner == null || !owner.equals(entry.owner())) throw new NoSuchElementException("运行不存在");
        return entry;
    }
    public ObjectNode view(String id, String owner) {
        var entry = owned(id, owner); var view = json.createObjectNode().put("runId", id).put("status", entry.status());
        if (entry.data().has("waitTarget")) view.set("waitTarget", entry.data().get("waitTarget"));
        view.put("businessCommitted",entry.data().path("businessCommitted").asBoolean());
        if(entry.data().path("session").isObject())view.put("turnId",entry.data().at("/session/turnId").asText());
        if (entry.data().has("result")) view.set("result", entry.data().get("result"));
        var calls = view.putArray("calls");
        for (var call : store.calls(id)) {
            var item = calls.addObject().put("invocationId", call.id()).put("status", call.status());
            for (String field : List.of("tool", "arguments", "resourceVersions", "summary", "fingerprint", "expiresAt", "decision", "result"))
                if (call.data().has(field)) item.set(field, call.data().get(field));
            if (call.data().has("preparedPlanId")) item.set("preparedPlanId", call.data().get("preparedPlanId"));
        }
        var prepared = view.putArray("plans");
        if (plans != null) for (var plan : store.plans(id)) prepared.add(plans.view(plan));
        return view;
    }
    private ExecutionResult waiting(DurableStore.Entry entry) {
        var payload = view(entry.id(), entry.owner()); payload.put("execution_status", "WAITING");
        return new ExecutionResult("任务仍在执行，可在此查看进度并处理所需确认。", json.createArrayNode(), 0, 0, 0, 0, "WAITING", null)
                .withAgent(entry.data().path("route").asText(), payload);
    }
}
