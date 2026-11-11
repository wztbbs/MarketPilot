package com.cexpilot.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;

/** 最近一次有效计划的执行摘要；节点实际元数据和事实焦点不属于查询要求。 */
public record ExecutionSummary(long sourceVersion, String sourceTurnId, String status, JsonNode focus,
                               Map<String, JsonNode> nodeMetadata) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public ExecutionSummary {
        focus = focus == null || focus.isNull() ? null : focus.deepCopy();
        nodeMetadata = nodeMetadata == null ? Map.of() : Map.copyOf(nodeMetadata);
    }
    public static ExecutionSummary fromEvidence(long version, String turnId, String status, JsonNode evidence) {
        var metadata = new java.util.LinkedHashMap<String, JsonNode>();
        if (evidence != null && evidence.isArray()) for (JsonNode entry : evidence) {
            if (!entry.path("ok").asBoolean(false) || !entry.path("data").isObject()) continue;
            ObjectNode execution = MAPPER.createObjectNode();
            JsonNode data = entry.path("data");
            for (String field : List.of("candle_interval", "interval_source", "requested_range", "effective_range",
                    "actual_range", "coverage", "as_of", "timezone", "requested_count", "actual_count", "sample_complete",
                    "sample_selection", "selection_range", "start_sample_at", "end_sample_at")) {
                if (data.hasNonNull(field)) execution.set(field, data.get(field).deepCopy());
            }
            if (!execution.has("effective_range") && execution.has("requested_range"))
                execution.set("effective_range", execution.get("requested_range").deepCopy());
            if (!execution.isEmpty()) metadata.put(entry.path("node_id").asText(), execution);
        }
        return new ExecutionSummary(version, turnId, status, focusFromEvidence(evidence), metadata);
    }
    public JsonNode metadata(String nodeId) { return nodeMetadata.get(nodeId); }
    static JsonNode focusFromEvidence(JsonNode evidence) {
        ObjectNode focus = MAPPER.createObjectNode();
        ArrayNode nodes = focus.putArray("evidence_nodes");
        if (evidence != null && evidence.isArray()) {
            for (JsonNode entry : evidence) {
                ObjectNode node = nodes.addObject();
                node.put("node_id", entry.path("node_id").asText(null));
                node.put("type", entry.path("type").asText(null));
                node.put("ok", entry.path("ok").asBoolean(false));
                if (entry.has("identity")) {
                    node.set("identity", entry.get("identity"));
                }
                if (entry.has("operator")) {
                    node.put("operator", entry.path("operator").asText());
                }
                // 仅保存事实身份，不复制数值 payload。
                if (entry.has("fact_id")) {
                    node.put("fact_id", entry.path("fact_id").asText());
                }
                if (entry.has("reused")) {
                    node.put("reused", entry.path("reused").asBoolean());
                }
                if (entry.has("source_turn_id")) node.set("source_turn_id", entry.get("source_turn_id"));
            }
        }
        return focus;
    }

}
