package com.cexpilot.business.cex.metric;

/** 编译完成后的类型化请求；执行时不再反解析 Plan JSON。 */
public sealed interface MetricQuery extends com.cexpilot.platform.engine.dag.NodeOperation permits TimeRangeQuery, SnapshotQuery, CountQuery {
    MetricBinding binding();
    @Override default String executionName() { return binding().provider(); }
    @Override default com.fasterxml.jackson.databind.JsonNode outputSchema() { return binding().outputSchema(); }
    @Override default void describe(com.fasterxml.jackson.databind.node.ObjectNode node) {
        node.put("type", "metric").put("provider", binding().provider());
        node.set("metric_binding", binding().identity());
    }
    @Override default void validate(com.cexpilot.platform.engine.dag.PlanNode node, java.util.List<String> errors) {
        if (node.tool() != null || !node.dependsOn().isEmpty())
            errors.add(node.id() + " 指标节点必须具有一致的类型化请求，且不能混入 Tool 或上游依赖");
    }
}
