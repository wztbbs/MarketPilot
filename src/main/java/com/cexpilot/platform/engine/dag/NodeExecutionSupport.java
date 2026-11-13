package com.cexpilot.platform.engine.dag;

import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.observability.TraceEvent;

/** 类型化节点执行及工具前后置校验扩展；具体业务校验、Provider 和事件留在业务包。 */
public interface NodeExecutionSupport {
    boolean supports(NodeOperation operation);
    ToolResult execute(PlanNode node, DagContext context, RequestContext request, NodeBindings bindings);
    TraceEvent event(PlanNode node, ToolResult result, NodeBindings bindings, String traceId, long durationMs);
    default void beforeTool(PlanNode node, DagContext context, AgentTool tool) {}
    default ToolResult afterTool(PlanNode node, DagContext context, AgentTool tool, ToolResult result) { return result; }
}
