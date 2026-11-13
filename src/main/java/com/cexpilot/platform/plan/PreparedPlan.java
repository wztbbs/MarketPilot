package com.cexpilot.platform.plan;

import com.cexpilot.platform.engine.dag.ReferenceResolver;
import java.util.*;

/** 一份不可变、可审批的工具调用方案；变更必须创建新方案，不能修改已经批准的内容。 */
public record PreparedPlan(String summary, List<PreparedToolCall> calls, long expiresAfterSeconds, PlanFailurePolicy failurePolicy) {
    public PreparedPlan(String summary, List<PreparedToolCall> calls, long expiresAfterSeconds) {
        this(summary, calls, expiresAfterSeconds, PlanFailurePolicy.STOP_ON_FAILURE);
    }
    public PreparedPlan {
        if (summary == null || summary.isBlank() || summary.length() > 1000 || calls == null || calls.isEmpty() || calls.size() > 64
                || expiresAfterSeconds < 60 || expiresAfterSeconds > 604800 || failurePolicy != PlanFailurePolicy.STOP_ON_FAILURE)
            throw new IllegalArgumentException("非法审批方案");
        calls = List.copyOf(calls);
        var ids = new HashSet<String>();
        for (var call : calls) if (!ids.add(call.id())) throw new IllegalArgumentException("方案调用 ID 重复");
        for (var call : calls) {
            if (call.dependsOn().contains(call.id()) || !ids.containsAll(call.dependsOn())) throw new IllegalArgumentException("方案依赖目标无效");
            var errors = ReferenceResolver.syntaxErrors(call.arguments());
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
            for (var ref : ReferenceResolver.findRefs(call.arguments()))
                if (!call.dependsOn().contains(ref.nodeId())) throw new IllegalArgumentException("参数引用必须声明对应依赖: " + ref.nodeId());
        }
        order(calls);
    }
    public List<PreparedToolCall> orderedCalls() { return order(calls); }
    private static List<PreparedToolCall> order(List<PreparedToolCall> calls) {
        var done = new HashSet<String>(); var ordered = new ArrayList<PreparedToolCall>();
        while (ordered.size() < calls.size()) {
            int before = ordered.size();
            for (var call : calls) if (!done.contains(call.id()) && done.containsAll(call.dependsOn())) {
                ordered.add(call); done.add(call.id());
            }
            if (before == ordered.size()) throw new IllegalArgumentException("方案调用存在循环依赖");
        }
        return List.copyOf(ordered);
    }
}
