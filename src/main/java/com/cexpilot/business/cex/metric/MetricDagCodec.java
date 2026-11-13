package com.cexpilot.business.cex.metric;

import com.cexpilot.platform.engine.dag.PlanNode;
import com.cexpilot.platform.engine.dag.DagPlan;
import com.cexpilot.business.cex.time.TimeSpecParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 指标与计算物理DAG的存储协议。加载只恢复类型化查询，不读取目录默认值、不重新编译LogicalPlan。 */
public final class MetricDagCodec {
    private MetricDagCodec() {}
    public static ObjectNode write(DagPlan plan) { return plan.toJson().put("schemaVersion",1); }
    public static DagPlan read(JsonNode saved,MetricProviderRegistry providers) {
        if(saved.path("schemaVersion").asInt()!=1 || !saved.path("nodes").isArray() || saved.path("nodes").isEmpty())
            throw new IllegalArgumentException("缺少受支持的固定DAG运行协议");
        var nodes=new ArrayList<PlanNode>();
        for(var n:saved.path("nodes")) {
            if(!n.path("id").isTextual() || !n.path("args").isObject() || !n.path("depends_on").isArray())
                throw new IllegalArgumentException("固定DAG节点格式无效");
            var deps=new ArrayList<String>();
            for(var dep:n.path("depends_on")) {
                if(!dep.isTextual()) throw new IllegalArgumentException("固定DAG依赖格式无效");
                deps.add(dep.asText());
            }
            var args=n.path("args").deepCopy(); MetricQuery query=null; String tool=null;
            if("metric".equals(n.path("type").asText())) {
                var b=n.path("metric_binding");
                var source=b.path("source");
                var binding=new MetricBinding(b.path("group_id").asText(),b.path("metric").asText(),b.path("exchange").asText(),
                        b.path("query_shape").asText(),b.path("instrument"),b.path("unit").asText(),source.path("provider").asText(),
                        providers.get(source.path("provider").asText()).selector(source.path("selector").asText()),
                        b.path("catalog_version").asText(),b.path("description").asText());
                query=switch(QueryShape.from(binding.shape())) {
                    case RANGE_STATISTIC,TIME_SERIES -> new TimeRangeQuery(binding,TimeSpecParser.parse(args.path("time")),
                            args.hasNonNull("interval")?args.path("interval").asText():null,args.path("include_unclosed").asBoolean());
                    case SNAPSHOT,OFFICIAL_24H -> new SnapshotQuery(binding,args.hasNonNull("depth")?args.path("depth").asInt():null);
                    case RECENT_N -> new CountQuery(binding,args.path("count").asInt());
                };
            } else if("tool".equals(n.path("type").asText())) tool=n.path("tool").asText();
            else throw new IllegalArgumentException("固定DAG节点类型无效");
            nodes.add(new PlanNode(n.path("id").asText(),tool,args,deps,query));
        }
        return new DagPlan(nodes);
    }
}
