package com.cexpilot.metric;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 编译期补齐后的单指标节点有效查询条件（按交易所展开），带字段级来源标记（{@link ConditionOrigin}）。
 * 多对象查询按节点保存，不压成全局 symbol/exchange。interval 等是查询要求，
 * 实际执行情况保存在 ExecutionSummary，不混入查询要求。
 *
 * @param instrument 编译器规范化后的完整 instrument（market_type/base/quote/settle）
 */
public record EffectiveTarget(
        String groupId,
        String physicalId,
        String exchange,
        ConditionOrigin exchangeOrigin,
        String metric,
        String queryShape,
        JsonNode instrument,
        ConditionOrigin settleOrigin,
        JsonNode timeExpression,
        String interval,
        ConditionOrigin intervalOrigin,
        Boolean includeUnclosed,
        ConditionOrigin includeUnclosedOrigin,
        Integer count,
        Integer depth,
        ConditionOrigin depthOrigin,
        String unit,
        String provider,
        String catalogVersion,
        java.util.Map<String, ConditionSource> conditionSources) {
    public EffectiveTarget {
        // Jackson 的 JsonNode null 反序列化为 NullNode；统一缺省值，避免落库后快照被误判成时间变化。
        if (timeExpression != null && timeExpression.isNull()) timeExpression = null;
        conditionSources = conditionSources == null ? java.util.Map.of() : java.util.Map.copyOf(conditionSources);
    }

    /** 编译器构造条件，字段来源在来源消解阶段补齐。 */
    public EffectiveTarget(String groupId, String physicalId, String exchange, ConditionOrigin exchangeOrigin,
            String metric, String queryShape, JsonNode instrument, ConditionOrigin settleOrigin,
            JsonNode timeExpression, String interval, ConditionOrigin intervalOrigin, Boolean includeUnclosed,
            ConditionOrigin includeUnclosedOrigin, Integer count, Integer depth, ConditionOrigin depthOrigin,
            String unit, String provider, String catalogVersion) {
        this(groupId, physicalId, exchange, exchangeOrigin, metric, queryShape, instrument, settleOrigin,
                timeExpression, interval, intervalOrigin, includeUnclosed, includeUnclosedOrigin, count, depth,
                depthOrigin, unit, provider, catalogVersion, java.util.Map.of());
    }

    public EffectiveTarget withSources(java.util.Map<String, ConditionSource> sources) {
        return new EffectiveTarget(groupId, physicalId, exchange, origin(sources, "exchange", exchangeOrigin),
                metric, queryShape, instrument, origin(sources, "settle", settleOrigin), timeExpression,
                interval, origin(sources, "interval", intervalOrigin), includeUnclosed,
                origin(sources, "include_unclosed", includeUnclosedOrigin), count, depth,
                origin(sources, "depth", depthOrigin), unit, provider, catalogVersion, sources);
    }

    private static ConditionOrigin origin(java.util.Map<String, ConditionSource> sources, String field,
                                         ConditionOrigin fallback) {
        return sources.containsKey(field) ? sources.get(field).origin() : fallback;
    }
}
