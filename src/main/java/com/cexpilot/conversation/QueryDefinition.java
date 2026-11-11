package com.cexpilot.conversation;

import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.ConditionSource;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 查询语义及其规范化条件投影。没有任务身份、执行结果或任务状态。
 * logicalPlan 保存完整计算/引用语义；targets 是同次编译生成、带来源的条件投影，不能独立修改。
 * requestTime/timezone 是该定义的时间消解基准，继承定义时保持原基准。
 */
public record QueryDefinition(String requestTime, String timezone, JsonNode logicalPlan,
                              List<EffectiveTarget> targets) {
    public QueryDefinition {
        logicalPlan = logicalPlan == null || logicalPlan.isNull() ? null : logicalPlan.deepCopy();
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
    public static QueryDefinition fromCompiledPlan(String taskId, long version, String turnId,
            java.time.Instant requestTime, java.time.ZoneId zone, CompiledPlan plan, JsonNode evidence) {
        var targets = plan.targets().stream().map(target -> {
            var sources = new java.util.LinkedHashMap<String, ConditionSource>();
            target.conditionSources().forEach((field, source) -> sources.put(field, source.identify(turnId, taskId, version)));
            return target.withSources(sources);
        }).toList();
        JsonNode logical = plan.logicalPlanJson() == null ? null : plan.logicalPlanJson().deepCopy();
        // 事实别名只对当次模型输入有效。持久化计划只保留稳定事实身份；未产出绑定事实则显式不可用。
        if (logical != null) for (JsonNode metric : logical.path("metrics")) {
            if (!"pinned_result".equals(metric.path("reference_mode").asText()) || !metric.isObject()) continue;
            String factId = null;
            for (EffectiveTarget target : targets) {
                if (!target.groupId().equals(metric.path("id").asText()) || evidence == null || !evidence.isArray()) continue;
                for (JsonNode entry : evidence) {
                    if (target.physicalId().equals(entry.path("node_id").asText()) && entry.hasNonNull("fact_id"))
                        factId = entry.path("fact_id").asText();
                }
            }
            var stored = (com.fasterxml.jackson.databind.node.ObjectNode) metric;
            stored.remove("evidence_ref");
            stored.put("evidence_fact_id", factId);
        }
        return new QueryDefinition(requestTime.toString(), zone == null ? null : zone.getId(), logical, targets);
    }
}
