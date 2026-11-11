package com.cexpilot.conversation;

import com.cexpilot.metric.CompiledPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 一项任务某次提交后的完整可恢复状态。持久化后不可覆盖，身份为 taskId + version。 */
public record TaskVersion(String taskId, String conversationId, long version, long baseVersion,
        String originQuestion, long firstQueryNo, long lastQueryNo, String turnId, String status,
        String requestTime, String timezone, QueryDefinition queryDefinition, ExecutionSummary executionSummary,
        List<Dialogue> dialogue, List<ClarificationAnswer> clarificationAnswers) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public record Dialogue(String turnId, String question, String response) {}
    public TaskVersion {
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        clarificationAnswers = clarificationAnswers == null ? List.of() : List.copyOf(clarificationAnswers);
    }
    public TaskVersion withClarificationAnswers(List<ClarificationAnswer> submitted) {
        var merged = new java.util.LinkedHashMap<String, ClarificationAnswer>();
        clarificationAnswers.forEach(a -> merged.put(a.key(), a));
        submitted.forEach(a -> merged.put(a.key(), a));
        return new TaskVersion(taskId, conversationId, version, baseVersion, originQuestion, firstQueryNo,
                lastQueryNo, turnId, status, requestTime, timezone, queryDefinition, executionSummary, dialogue,
                List.copyOf(merged.values()));
    }
    public static TaskVersion complete(String taskId, String conversationId, long version, TaskVersion base,
            ConversationService.TurnHandle turn, String question, String turnStatus, String response,
            CompiledPlan plan, JsonNode evidence, Instant requestTime, ZoneId zone) {
        QueryDefinition definition = base == null ? null : base.queryDefinition();
        ExecutionSummary execution = base == null ? null : base.executionSummary();
        if (plan != null) {
            definition = QueryDefinition.fromCompiledPlan(taskId, version, turn.turnId(), requestTime, zone, plan, evidence);
            execution = ExecutionSummary.fromEvidence(version, turn.turnId(), turnStatus, evidence);
        }
        String taskStatus = turnStatus;
        if (plan == null && "SUCCEEDED".equals(turnStatus) && base != null && !base.dialogue().isEmpty())
            taskStatus = base.status();
        List<Dialogue> chain = new ArrayList<>(base == null ? List.of() : base.dialogue());
        if ("NEEDS_CLARIFICATION".equals(taskStatus) || "FAILED".equals(taskStatus))
            chain.add(new Dialogue(turn.turnId(), question, response));
        else chain.clear();
        return new TaskVersion(taskId, conversationId, version, base == null ? 0 : base.version(),
                base == null ? question : base.originQuestion(), base == null ? turn.queryNo() : base.firstQueryNo(),
                turn.queryNo(), turn.turnId(), taskStatus, requestTime.toString(), zone == null ? null : zone.getId(),
                definition, execution, chain,
                base == null || "SUCCEEDED".equals(taskStatus) ? List.of() : base.clarificationAnswers());
    }
    public String toJson() {
        try { return MAPPER.writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("任务版本序列化失败", e); }
    }
    public static TaskVersion fromJson(String json) {
        try {
            // JDBC 驱动对 JSON 列可能返回 JSON 文本或包装为 JSON 字符串，二者都属于同一存储格式。
            JsonNode node = MAPPER.readTree(json);
            if (node.isTextual()) node = MAPPER.readTree(node.textValue());
            return MAPPER.treeToValue(node, TaskVersion.class);
        }
        catch (Exception e) { throw new IllegalArgumentException("任务版本解析失败", e); }
    }
}
