package com.cexpilot.platform.context;

import com.cexpilot.platform.agent.AgentInput;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 应用级跨 Agent 交接扩展，不参与引擎内部规划循环。 */
public interface DispatchLifecycle {
    default String version() { return "none"; }
    default ObjectNode prepareForDispatch(String target, AgentInput input, EvidenceSession session, ObjectNode context,
            String traceId, TraceSink sink) { return null; }
    default ExecutionResult complete(ExecutionResult result, ObjectNode state, String traceId, TraceSink sink) { return result; }
    default void failed(ObjectNode state, String traceId, TraceSink sink) {}
    static DispatchLifecycle none() { return new DispatchLifecycle() {}; }
}
