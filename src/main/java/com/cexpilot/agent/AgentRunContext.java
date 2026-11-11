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
 *
 * @param question            本轮用户原始问题
 * @param history             历史问答文本；可为空或 null
 * @param traceId             本轮 Trace 标识
 * @param sink                Trace 事件写入器，非空
 * @param delta               答案文本流式回调；可为 null
 * @param request             请求时间上下文（固定时间基准与用户时区），非空
 * @param progress            面向用户的进度回调；可为 null
 * @param session             事实与业务操作归属的 conversationId/turnId；兼容调用可为 null
 * @param routingState        服务端路由/监控状态快照；可为 null，构造时深拷贝
 * @param conversationContext 投影后的模型可读上下文，非空，构造时深拷贝
 * @param visitor             服务端可信访客标识；兼容调用可为 null
 * @param privateInput        仅目标 Agent 可见的私有输入；无则 null
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
