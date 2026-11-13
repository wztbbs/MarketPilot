package com.cexpilot.platform.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;

/** 通用工具证据保存端口，只返回稳定证据 ID，不泄漏业务事实结构。 */
public interface ToolEvidenceWriter {
    Optional<String> saveToolEvidence(String conversationId, String turnId, String traceId,
            String agentId, String tool, JsonNode data, JsonNode provenance, Instant now);
}
