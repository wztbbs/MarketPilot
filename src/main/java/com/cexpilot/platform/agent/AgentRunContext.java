package com.cexpilot.platform.agent;

import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/**
 * 目标 Agent 的公共运行上下文。visitor 来自可信入口；privateInput 只交给目标 Agent。
 * conversationContext 与 routingState 是应用提供的 JSON 投影，底座不解释业务命名空间。
 * session 只表示证据所属会话与轮次，不包含查询任务或后台规则状态。
 */
public record AgentRunContext(String question, String history, String traceId, TraceSink sink,
        Consumer<String> delta, RequestContext request, Consumer<String> progress,
        EvidenceSession session, JsonNode routingState, JsonNode conversationContext,
        String visitor, PrivateInput privateInput) {
    public interface PrivateInput extends AgentInput {}

    public AgentRunContext {
        routingState = routingState == null ? null : routingState.deepCopy();
        conversationContext = conversationContext.deepCopy();
    }
}
