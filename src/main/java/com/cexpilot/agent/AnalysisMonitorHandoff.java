package com.cexpilot.agent;

import com.cexpilot.conversation.PlannerInput;
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

    public ObjectNode prepare(String target, PlannerInput input, EvidenceSession session, ObjectNode context,
                              String traceId, TraceSink sink) {
        if (!"monitor".equals(target) || !"query".equals(context.path("current_agent").asText())
                || input == null || input.activeFrame() == null) return null;
        var frame = input.activeFrame();
        if (session == null || !session.conversationId().equals(frame.conversationId())) {
            sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null,
                    JSON.createObjectNode().put("status", "REJECTED").put("reason", "CONVERSATION_MISMATCH").toString(),
                    null, null, null, null, null, null));
            // 不把外会话 Query 投影继续传给 Monitor；历史仍由入口的会话校验负责。
            context.remove("query_task");
            throw new IllegalArgumentException("交接来源不属于当前会话");
        }
        var handoff = JSON.createObjectNode().put("version", VERSION).put("status", "PREPARED")
                .put("source_agent", "market_analysis").put("target_agent", "monitor")
                .put("source_frame_id", frame.frameId()).put("source_frame_version", frame.version())
                .put("source_turn_id", frame.sourceTurnId()).put("source_request_time", frame.requestTime())
                .put("source_status", frame.lastAttempt() == null ? "UNKNOWN" : frame.lastAttempt().status())
                .put("context_kind", "CONDITION_CONTEXT").put("requires_confirmation", true)
                .put("target_count", frame.targets().size()).put("targets_truncated", frame.targets().size() > 8);
        var targets = handoff.putArray("targets");
        frame.targets().stream().limit(8).forEach(t -> {
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

    public void failed(ObjectNode handoff, String traceId, TraceSink sink) {
        if (handoff == null) return;
        var outcome = handoff.deepCopy().put("status", "MONITOR_EXECUTION_FAILED");
        sink.record(new TraceEvent(traceId, "AGENT_HANDOFF", VERSION, null, outcome.toString(),
                null, null, null, null, null, "交接后的 Monitor 执行失败；以该轮失败信息和持久化草稿状态为准"));
    }
}
