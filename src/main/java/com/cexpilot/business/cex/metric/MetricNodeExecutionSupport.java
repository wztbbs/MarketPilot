package com.cexpilot.business.cex.metric;

import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.calculation.CalculationTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 行情 Provider、事实复用和指标计算语义，由 CEX 注入通用 DAG 执行器。 */
@Component
public class MetricNodeExecutionSupport implements NodeExecutionSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MetricProviderRegistry providers;
    public MetricNodeExecutionSupport(MetricProviderRegistry providers) { this.providers = providers; }
    @Override public boolean supports(NodeOperation operation) { return operation instanceof MetricQuery; }
    @Override public ToolResult execute(PlanNode node, DagContext context, RequestContext request, NodeBindings bindings) {
        var reuse = reuse(node, bindings);
        if (reuse != null) return ToolResult.success(reuse.payload().deepCopy());
        var query = MetricNodes.query(node);
        return ToolResult.success(MetricResultJson.write(query.binding(), providers.get(query.binding().provider()).query(query, request)));
    }
    @Override public void beforeTool(PlanNode node, DagContext context, AgentTool tool) {
        if (tool instanceof CalculationTool) MetricCalculationContext.validate(node, context);
    }
    @Override public ToolResult afterTool(PlanNode node, DagContext context, AgentTool tool, ToolResult result) {
        return tool instanceof CalculationTool ? MetricCalculationContext.attach(node, context, result) : result;
    }
    @Override public TraceEvent event(PlanNode node, ToolResult result, NodeBindings bindings, String traceId, long durationMs) {
        var binding = MetricNodes.binding(node);
        var input = JSON.createObjectNode().put("node_id", node.id());
        var reuse = reuse(node, bindings);
        if (reuse != null) {
            input.put("fact_id", reuse.factId()).put("source_turn_id", reuse.sourceTurnId());
            if (reuse.observedAt() != null) input.put("observed_at", reuse.observedAt());
            input.put("fetched_at", reuse.fetchedAt()).put("reference_mode", "PINNED_RESULT");
        } else input.set("args", node.args());
        input.set("metric_binding", binding.identity());
        return new TraceEvent(traceId, reuse == null ? "METRIC_RESULT" : "FACT_REUSED", binding.metric(),
                input.toString(), result.toMessageContent(), durationMs, null, null, null, null, result.error());
    }
    private ExecutionBindings.ReuseFact reuse(PlanNode node, NodeBindings bindings) {
        return bindings instanceof ExecutionBindings cex ? cex.reuseFor(node.id()) : null;
    }
}
