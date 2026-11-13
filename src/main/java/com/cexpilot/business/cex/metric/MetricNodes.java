package com.cexpilot.business.cex.metric;

import com.cexpilot.platform.engine.dag.PlanNode;

/** 查询业务解释通用 DAG 中的指标节点。 */
public final class MetricNodes {
    private MetricNodes() {}
    public static MetricQuery query(PlanNode node) { return node.operation() instanceof MetricQuery query ? query : null; }
    public static MetricBinding binding(PlanNode node) { var query = query(node); return query == null ? null : query.binding(); }
}
