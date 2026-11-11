package com.cexpilot.agent;

import com.cexpilot.conversation.PlanningContext;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 跨轮 query → monitor 的条件交接；不复制事实、不生成监控 Plan、不启用规则。 */
@Component
public class AnalysisMonitorHandoff {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String VERSION = "analysis-monitor-v1";

    /**
     * 在目标 Agent 执行前判断是否满足 query → monitor 交接条件：目标为 monitor、来源为 query、
     * 存在活动 definition 且 definition 属于当前会话。满足时构造 PREPARED 交接载荷写入上下文与 trace，
     * 并从上下文中移除 query_task（避免监控误用一次性查询状态）。
     *
     * @param target    本轮路由目标 route
     * @param input     Query 规划输入，提供活动 definition；可为 null（不交接）
     * @param session   当前会话；definition 与会话不一致时拒绝交接并抛 IllegalArgumentException
     * @param context   完整上下文投影，交接时会被改写（写入 handoff、移除 query_task）
     * @param traceId   本轮 Trace 标识
     * @param sink      Trace 写入器
     * @return PREPARED 状态的交接载荷 JSON；不满足交接条件返回 null
     */
    public ObjectNode prepare(String target, PlanningContext input, EvidenceSession session, ObjectNode context,
                              String traceId, TraceSink sink) {
        if (!"monitor".equals(target) || !"query".equals(context.path("current_agent").asText())
                || input == null || input.activeDefinition() == null) return null;
        var definition = input.activeDefinition();
        var version = input.currentVersion();
        if (session == null || !session.conversationId().equals(version.conversationId())) {
            sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null,
                    JSON.createObjectNode().put("status", "REJECTED").put("reason", "CONVERSATION_MISMATCH").toString(),
                    null, null, null, null, null, null));
            // 不把外会话 Query 投影继续传给 Monitor；历史仍由入口的会话校验负责。
            context.remove("query_task");
            throw new IllegalArgumentException("交接来源不属于当前会话");
        }
        var handoff = JSON.createObjectNode().put("version", VERSION).put("status", "PREPARED")
                .put("source_agent", "market_analysis").put("target_agent", "monitor")
                .put("source_task_id", version.taskId()).put("source_task_version", version.version())
                .put("source_turn_id", version.turnId()).put("source_request_time", definition.requestTime())
                .put("source_status", version.status())
                .put("context_kind", "CONDITION_CONTEXT").put("requires_confirmation", true)
                .put("target_count", definition.targets().size()).put("targets_truncated", definition.targets().size() > 8);
        var targets = handoff.putArray("targets");
        definition.targets().stream().limit(8).forEach(t -> {
            var targetNode = targets.addObject().put("metric", t.metric()).put("exchange", t.exchange())
                    .put("query_shape", t.queryShape()).put("unit", t.unit());
            targetNode.set("instrument", t.instrument() == null ? JSON.nullNode() : t.instrument().deepCopy());
            // 固定日期、历史窗口、样本引用、已算出的数值不属于监控规则定义。
        });
        context.set("handoff", handoff.deepCopy());
        context.remove("query_task");
        sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null, handoff.toString(),
                null, null, null, null, null, null));
        return handoff;
    }

    /**
     * 目标 Agent 执行成功后收尾：把交接状态推进为 DELIVERED_TO_MONITOR，
     * 记录 Monitor 侧的执行结果状态，写 trace，并把交接结果附到 agentPayload 返回。
     *
     * @param result  目标 Agent 的执行结果
     * @param handoff {@link #prepare} 返回的交接载荷；null 表示本轮无交接，原样返回
     * @return 附带 handoff 载荷的新 ExecutionResult；无交接时返回入参本身
     */
    public ExecutionResult complete(ExecutionResult result, ObjectNode handoff, String traceId, TraceSink sink) {
        if (handoff == null) return result;
        var outcome = handoff.deepCopy().put("status", "DELIVERED_TO_MONITOR")
                .put("outcome", result.agentPayload() == null ? result.turnStatus() : result.agentPayload().path("status").asText("UNKNOWN"));
        var payload = result.agentPayload() != null && result.agentPayload().isObject()
                ? (ObjectNode) result.agentPayload().deepCopy() : JSON.createObjectNode();
        payload.set("handoff", outcome);
        sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null, outcome.toString(),
                null, null, null, null, null, null));
        return result.withAgent(result.agentType(), payload);
    }

    /**
     * 目标 Agent 执行失败时记录交接失败事件（状态 MONITOR_EXECUTION_FAILED）。
     *
     * @param handoff {@link #prepare} 返回的交接载荷；null 时空操作
     */
    public void failed(ObjectNode handoff, String traceId, TraceSink sink) {
        if (handoff == null) return;
        var outcome = handoff.deepCopy().put("status", "MONITOR_EXECUTION_FAILED");
        sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null, outcome.toString(),
                null, null, null, null, null, "交接后的 Monitor 执行失败；以该轮失败信息和持久化草稿状态为准"));
    }
}
