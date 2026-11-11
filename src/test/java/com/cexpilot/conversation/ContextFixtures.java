package com.cexpilot.conversation;

import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 测试构造完整版本；生产代码不从查询条件反推任务身份。 */
public final class ContextFixtures {
    private ContextFixtures() {}
    public static TaskVersion version(String taskId, String conversationId, long version, String turnId,
            String question, String time, String timezone, List<EffectiveTarget> targets, JsonNode focus, String status) {
        return new TaskVersion(taskId, conversationId, version, 0, question, 1, 1, turnId, status,
                time, timezone, new QueryDefinition(time, timezone, null, targets),
                new ExecutionSummary(version, turnId, status, focus, Map.of()), List.of(), List.of());
    }
    public static TaskVersion executed(String taskId, String conversationId, long version, String turnId,
            String question, Instant time, ZoneId zone, CompiledPlan plan, JsonNode evidence, String status) {
        return TaskVersion.complete(taskId, conversationId, version, null,
                new ConversationService.TurnHandle(1, turnId, 1), question, status, null, plan, evidence, time, zone);
    }
}
