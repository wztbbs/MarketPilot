package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.engine.dag.PlanNode;
import com.cexpilot.platform.engine.dag.DagPlan;
import com.cexpilot.business.cex.metric.MetricProviderRegistry;
import com.cexpilot.business.cex.metric.QueryShape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 固定物理DAG的存储协议。加载只恢复类型化查询，不读取目录默认值、不重新编译LogicalPlan。 */
final class MonitorDag {
    private MonitorDag() {}
    static ObjectNode write(DagPlan plan) { return com.cexpilot.business.cex.metric.MetricDagCodec.write(plan); }
    static DagPlan read(JsonNode saved,MetricProviderRegistry providers) {
        if(saved.path("schemaVersion").asInt()!=1 || !saved.path("nodes").isArray() || saved.path("nodes").isEmpty())
            throw new IllegalArgumentException("此规则未保存受支持的固定DAG，请重新创建并确认监控");
        // 监控仍只接受已支持的固定窗口与快照，不能因通用 Query 协议扩展而放开规则形态。
        for(var node:saved.path("nodes")) if("metric".equals(node.path("type").asText())) {
            var shape=QueryShape.from(node.at("/metric_binding/query_shape").asText());
            if(shape!=QueryShape.RANGE_STATISTIC && shape!=QueryShape.TIME_SERIES && shape!=QueryShape.SNAPSHOT)
                throw new IllegalArgumentException("固定监控DAG不支持此查询形态");
        }
        return com.cexpilot.business.cex.metric.MetricDagCodec.read(saved,providers);
    }
    /** 先得到完整的拓扑顺序；后续复用集合只扫描一次，不依赖持久化数组的先后顺序。 */
    static List<PlanNode> topological(DagPlan plan) {
        var remaining=new LinkedHashMap<String,PlanNode>(); var ready=new ArrayDeque<String>();
        var degree=new HashMap<String,Integer>(); var children=new HashMap<String,List<String>>();
        for(var node:plan.nodes()) {
            if(node.id()==null || node.id().isBlank() || remaining.putIfAbsent(node.id(),node)!=null)
                throw new IllegalArgumentException("固定DAG节点id无效或重复");
            degree.put(node.id(),node.dependsOn().size());
        }
        for(var node:plan.nodes()) for(var dep:node.dependsOn()) {
            if(!remaining.containsKey(dep)) throw new IllegalArgumentException("固定DAG依赖不存在");
            children.computeIfAbsent(dep,k->new ArrayList<>()).add(node.id());
        }
        for(var node:plan.nodes()) if(degree.get(node.id())==0) ready.add(node.id());
        var order=new ArrayList<PlanNode>();
        while(!ready.isEmpty()) {
            String id=ready.remove(); order.add(remaining.get(id));
            for(var child:children.getOrDefault(id,List.of())) if(degree.compute(child,(k,v)->v-1)==0) ready.add(child);
        }
        if(order.size()!=plan.nodes().size()) throw new IllegalArgumentException("固定DAG存在循环依赖");
        return order;
    }
}
