package com.cexpilot.platform.plan;

import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.ToolGateway;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.cexpilot.platform.run.DurableStore.Kind.CALL;

/** 首版按依赖顺序执行获批方案；不规划、不生成额外动作，不把准备结果当成真实工具结果。 */
@Component
public class PreparedPlanExecutor {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final PreparedPlanService plans;
    private final ToolGateway gateway;
    private final DurableStore store;
    public PreparedPlanExecutor(PreparedPlanService plans, ToolGateway gateway, DurableStore store) { this.plans = plans; this.gateway = gateway; this.store = store; }

    public PlanExecutionResult execute(String id) {
        return execute(id, event -> {});
    }

    public PlanExecutionResult execute(String id, com.cexpilot.platform.observability.TraceSink sink) {
        var scope = Objects.requireNonNull(RunScope.current(), "方案执行需要持久化运行现场"); scope.checkLease();
        var run = store.require(DurableStore.Kind.RUN, scope.runId()); var entry = plans.forRun(id, run.id(), run.owner());
        if (plans.execution(entry).isTerminal()) return recordedResult(entry);
        var results = new LinkedHashMap<String, ToolResult>(); var ordered = plans.plan(entry).orderedCalls();
        var recorded = recordedResult(entry).results();
        boolean completedEffects = recorded.values().stream().anyMatch(ToolResult::ok);
        try {
            // 崩溃可能发生在最后一个结果落库与方案收尾之间；收尾不重新提交，也不重新申请执行授权。
            if (recorded.size() == ordered.size()) {
                for (var call : ordered) invokeAndTrace(entry, call, scope, sink, recorded.get(call.id()));
                return finish(id, ordered, recorded);
            }
            plans.markExecution(id, PlanExecutionStatus.RUNNING);
            for (var call : ordered) {
                var result = invokeAndTrace(entry, call, scope, sink, recorded.get(call.id())); results.put(call.id(), result);
                if (!result.ok()) return finish(id, ordered, results);
            }
            return finish(id, ordered, results);
        } catch (RunSuspended waiting) {
            plans.markExecution(id, PlanExecutionStatus.WAITING); throw waiting;
        } catch (RunLeaseLostException lost) {
            throw lost;
        } catch (RuntimeException failure) {
            var status = completedEffects || results.values().stream().anyMatch(ToolResult::ok) ? PlanExecutionStatus.PARTIAL : PlanExecutionStatus.FAILED;
            try { plans.markExecution(id, status); }
            catch (RuntimeException persistence) { if (failure != persistence) failure.addSuppressed(persistence); }
            throw failure;
        }
    }

    private ToolResult invokeAndTrace(DurableStore.Entry plan, PreparedToolCall call, RunScope scope,
                                      com.cexpilot.platform.observability.TraceSink sink, ToolResult recorded) {
        ToolResult result = recorded;
        if (result == null) {
            var collector = new com.cexpilot.platform.observability.NodeTraceCollector.Session();
            com.cexpilot.platform.observability.NodeTraceCollector.bind(collector);
            try { result = gateway.invokePrepared(plan.id(), call.id()); }
            finally {
                com.cexpilot.platform.observability.NodeTraceCollector.clear();
                for (var event : collector.freezeAndSnapshot()) sink.record(com.cexpilot.platform.observability.TraceEvent.toolCall(
                        store.require(DurableStore.Kind.RUN, plan.runId()).data().path("traceId").asText(), event.name(), event.inputJson(), event.outputJson(), event.durationMs(), event.error()));
            }
        }
        String key = "plan:trace:" + plan.id() + ":" + call.id();
        if (scope.load(key) == null) {
            var invocation = store.find(CALL, PreparedPlanService.invocationId(plan.id(), call.id(), plan.runId()));
            var input = scope.json().createObjectNode().put("preparedPlanId", plan.id()).put("preparedCallId", call.id());
            input.set("arguments", invocation == null ? call.arguments() : invocation.data().get("arguments"));
            sink.record(com.cexpilot.platform.observability.TraceEvent.toolCall(store.require(DurableStore.Kind.RUN, plan.runId()).data().path("traceId").asText(),
                    call.tool(), input.toString(), result.toMessageContent(), null, result.error()));
            scope.save(key, scope.json().createObjectNode().put("recorded", true));
        }
        return result;
    }

    private PlanExecutionResult finish(String id, List<PreparedToolCall> ordered, Map<String, ToolResult> results) {
        var status = results.size() == ordered.size() && results.values().stream().allMatch(ToolResult::ok) ? PlanExecutionStatus.SUCCEEDED
                : results.values().stream().anyMatch(ToolResult::ok) ? PlanExecutionStatus.PARTIAL : PlanExecutionStatus.FAILED;
        plans.markExecution(id, status, results);
        return new PlanExecutionResult(status, results, ordered.stream().map(PreparedToolCall::id).filter(call -> !results.containsKey(call)).toList());
    }
    private PlanExecutionResult recordedResult(DurableStore.Entry entry) {
        var results = new LinkedHashMap<String, ToolResult>(); var notStarted = new ArrayList<String>();
        for (var call : plans.plan(entry).orderedCalls()) {
            var invocation = store.find(CALL, PreparedPlanService.invocationId(entry.id(), call.id(), entry.runId()));
            if (invocation == null) notStarted.add(call.id());
            else if (invocation.data().has("result")) results.put(call.id(), JSON.convertValue(invocation.data().get("result"), ToolResult.class));
            if (entry.data().path("executionResults").has(call.id())) {
                results.putIfAbsent(call.id(), JSON.convertValue(entry.data().path("executionResults").get(call.id()), ToolResult.class));
                notStarted.remove(call.id());
            }
        }
        return new PlanExecutionResult(plans.execution(entry), results, notStarted);
    }
}
