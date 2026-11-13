package com.cexpilot.business.cex.query;

import com.cexpilot.platform.agent.AgentOutput;
import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.business.cex.conversation.TaskScope;

/** 查询任务提交所需的类型化结果；底座只保留 AgentOutput，不解析任务状态。 */
public record QueryOutput(TaskScope taskScope, com.cexpilot.business.cex.metric.CompiledPlan compiledPlan) implements AgentOutput {
    public QueryOutput(TaskScope taskScope) { this(taskScope,null); }
    public static com.cexpilot.business.cex.metric.CompiledPlan plan(ExecutionResult result) {
        return result.output() instanceof QueryOutput query ? query.compiledPlan() : null;
    }
    public static TaskScope scope(ExecutionResult result) {
        return result.output() instanceof QueryOutput query ? query.taskScope() : null;
    }
    public static TaskScope scope(ExecutionFailure failure) {
        return failure.output instanceof QueryOutput query ? query.taskScope() : null;
    }
}
