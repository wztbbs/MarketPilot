package com.cexpilot.business.cex.query;

import com.cexpilot.business.cex.metric.MetricNodes;

import com.cexpilot.platform.engine.dag.PlanNode;
import com.cexpilot.platform.engine.dag.PlanValidator;

import com.cexpilot.platform.llm.LlmConfig;
import com.cexpilot.platform.engine.dag.DagConfig;
import com.cexpilot.business.cex.metric.MetricPlanCompiler;
import com.cexpilot.business.cex.metric.CompiledPlan;
import com.cexpilot.business.cex.metric.TimeRangeQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 仅允许追加；旧节点、历史绑定、来源元数据和物理 ID 都不能被后续规划修改。 */
final class QueryPlanExtension {
    private final MetricPlanCompiler compiler;
    private final PlanValidator validator;
    private final int maxNodes;
    QueryPlanExtension(MetricPlanCompiler compiler,PlanValidator validator,DagConfig dag,LlmConfig llm) {
        this.compiler=compiler;this.validator=validator;maxNodes=Math.min(dag.getMaxNodes(),llm.getMaxToolCalls());
    }
    CompiledPlan append(CompiledPlan prior,JsonNode additions,String question,com.cexpilot.platform.execution.RequestContext request) {
        if(!additions.isObject())throw new IllegalArgumentException("补充计划必须为对象");
        additions.fieldNames().forEachRemaining(k->{if(!Set.of("metrics","calculations").contains(k))throw new IllegalArgumentException("补充计划含未知字段: "+k);});
        ObjectNode merged=(ObjectNode)prior.logicalPlanJson().deepCopy();
        var ids=new HashSet<String>();
        prior.logicalPlan().metrics().forEach(m->ids.add(m.id()));prior.logicalPlan().calculations().forEach(c->ids.add(c.id()));
        var constants=new HashSet<BigDecimal>();
        var matcher=Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?)(?:[eE][+-]?[0-9]+)?").matcher(question);
        while(matcher.find())constants.add(new BigDecimal(matcher.group()).stripTrailingZeros());
        collectNumbers(prior.logicalPlanJson().path("calculations"),constants,false);
        int added=0;
        for(String key:List.of("metrics","calculations")) {
            JsonNode values=additions.path(key);
            if(!values.isArray())throw new IllegalArgumentException(key+" 必须为数组");
            for(JsonNode value:values) {
                if(!ids.add(value.path("id").asText()))throw new IllegalArgumentException("补充计划不能覆盖或复用旧 id");
                if("metrics".equals(key) && (value.has("evidence_ref") || "pinned_result".equals(value.path("reference_mode").asText())))
                    throw new IllegalArgumentException("补充计划只可引用当前累积计划中的历史输入，不新增历史绑定");
                if("calculations".equals(key))collectNumbers(value.path("input"),constants,true);
                ((com.fasterxml.jackson.databind.node.ArrayNode)merged.path(key)).add(value);added++;
            }
        }
        if(added==0)throw new IllegalArgumentException("NO_PROGRESS: 没有新增节点");
        var compiled=compiler.compileFull(merged,maxNodes);
        var errors=validator.validate(compiled.dagPlan(),compiler.allowedTools(),maxNodes);
        errors.addAll(new FundingAggregationValidator().check(question,compiled));
        if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));
        var priorNodes=new HashSet<String>();prior.dagPlan().nodes().forEach(n->priorNodes.add(n.id()));
        var fingerprints=new HashSet<String>();
        for(var node:compiled.dagPlan().nodes()) {
            String fingerprint=fingerprint(node,compiled,request);
            if(!fingerprints.add(fingerprint) && !priorNodes.contains(node.id()))throw new IllegalArgumentException("NO_PROGRESS: 重复查询或计算，请直接引用已有节点");
        }
        var targets=compiled.targets().stream().map(t->prior.targets().stream().filter(old->old.physicalId().equals(t.physicalId())).findFirst().orElse(t)).toList();
        return new CompiledPlan(compiled.logicalPlan(),compiled.logicalPlanJson(),compiled.dagPlan(),compiled.branches(),targets);
    }
    private static String fingerprint(PlanNode node,CompiledPlan compiled,com.cexpilot.platform.execution.RequestContext request) {
        if(MetricNodes.binding(node)==null)return node.tool()+":"+node.args();
        var identity=MetricNodes.binding(node).identity().deepCopy();
        identity.remove(List.of("group_id","id"));
        ObjectNode args=(ObjectNode)node.args().deepCopy();
        if(MetricNodes.query(node) instanceof TimeRangeQuery query) {
            var resolver=new com.cexpilot.business.cex.time.TimeRangeResolver(java.time.Clock.fixed(request.requestTime(),request.userZone()));
            var range=resolver.resolve(request.userZone(),query.time(),request.requestTime());
            args.remove("time");args.put("start",range.startInclusive().toString()).put("end",range.endExclusive().toString())
                    .put("timezone",range.timezone().getId());
        }
        var logical=compiled.logicalPlan().metrics().stream().filter(m->m.id().equals(MetricNodes.binding(node).groupId())).findFirst().orElseThrow();
        String reference="pinned_result".equals(logical.referenceMode())?"pinned:"+logical.evidenceRef():"latest";
        return reference+":"+identity+":"+args;
    }
    private static void collectNumbers(JsonNode node,Set<BigDecimal> constants,boolean validate) {
        if(node.isNumber() || node.isTextual() && node.asText().matches("[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
            var number=new BigDecimal(node.asText()).stripTrailingZeros();
            if(validate && !constants.contains(number))throw new IllegalArgumentException("补充计算的数值常量必须来自用户原文或初始计划，行情数值请引用已有节点");
            if(!validate)constants.add(number);
        } else if(node.isContainerNode())node.forEach(child->collectNumbers(child,constants,validate));
    }
}
