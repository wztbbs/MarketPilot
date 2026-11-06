package com.cexpilot.agent;

import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/**
 * 共享运行契约。身份 visitor 由服务端入口提供，不来自模型。
 * conversationContext 仅含投影后的对话条件和业务候选，不含 Query Planner 对象。
 * privateInput 只交给被选中的 Agent；不写入共享上下文、Evidence 或 Trace。
 * EvidenceSession 沿用现有会话范围，事实与业务状态仍由原服务保存。
 */
public record AgentRunContext(String question, String history, String traceId, TraceSink sink,
        Consumer<String> delta, RequestContext request, Consumer<String> progress,
        EvidenceSession session, JsonNode routingState, JsonNode conversationContext,
        String visitor, PrivateInput privateInput) {
    public interface PrivateInput {}

    public AgentRunContext {
        routingState = routingState == null ? null : routingState.deepCopy();
        conversationContext = conversationContext.deepCopy();
    }
}
