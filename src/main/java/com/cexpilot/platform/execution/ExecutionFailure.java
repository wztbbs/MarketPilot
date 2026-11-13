package com.cexpilot.platform.execution;

import com.fasterxml.jackson.databind.JsonNode;

/** 包装执行期异常并携带 {@link TurnCounters}；编排层解包后用原始异常落 trace。 */
public final class ExecutionFailure extends RuntimeException {

    /** 失败时已累计的运行计数（含 LLM 用量）。 */
    public final TurnCounters counters;
    /** 失败时的业务私有结果；可为 null。 */
    public final com.cexpilot.platform.agent.AgentOutput output;
    /** 失败时已累积的证据数组；可为 null，构造时深拷贝。 */
    public final JsonNode evidence;

    public ExecutionFailure(Throwable cause, TurnCounters counters) { this(cause, counters, null); }
    public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.platform.agent.AgentOutput output) {
        this(cause, counters, output, null);
    }
    public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.platform.agent.AgentOutput output, JsonNode evidence) {
        super(cause.getMessage(), cause);
        this.counters = counters;
        this.output = output;
        this.evidence = evidence == null ? null : evidence.deepCopy();
    }

    /** 编排边界只补充路由用量，保留原始原因以及收尾失败的 suppressed 异常。 */
    public ExecutionFailure withRouterUsages(java.util.List<com.cexpilot.platform.llm.LlmUsage> usages) {
        var enriched = new ExecutionFailure(getCause(), counters.withRouterUsages(usages), output, evidence);
        for (var secondary : getSuppressed()) enriched.addSuppressed(secondary);
        return enriched;
    }
}

