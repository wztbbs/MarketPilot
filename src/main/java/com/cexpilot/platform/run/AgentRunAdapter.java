package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 业务运行接入契约。只保存 JSON 数据，不保存 Java 回调、对象类名或调用栈。
 * version 同时约束输入、检查点、私有输出及执行语义；变更格式必须变更版本。
 */
public interface AgentRunAdapter {
    String id();
    String version();
    boolean supports(ConversationAgent agent);
    QueryExecutionConfig limits(ConversationAgent agent);
    JsonNode captureInput(ConversationAgent agent, AgentRunContext context);
    AgentRunContext.PrivateInput restoreInput(ConversationAgent agent, JsonNode snapshot);
    RunStep executeSegment(ConversationAgent agent, RunSegment segment);

    /** 默认保留逐调用确认；新业务显式选择方案审批，不能由模型入参降级。 */
    default com.cexpilot.platform.plan.ToolApprovalMode toolApprovalMode(ConversationAgent agent) {
        return com.cexpilot.platform.plan.ToolApprovalMode.PER_CALL;
    }

    /** 有私有输出的业务必须提供显式编解码，禁止基于用户提供的 Java 类名反序列化。 */
    default JsonNode captureOutput(AgentOutput output) {
        if (output != null) throw new IllegalArgumentException("该运行适配器未声明私有输出编码");
        return null;
    }
    default AgentOutput restoreOutput(JsonNode snapshot) {
        if (snapshot != null && !snapshot.isNull()) throw new IllegalArgumentException("该运行适配器未声明私有输出解码");
        return null;
    }

    /**
     * 最终结果已落库后调用，崩溃后可能重复投递；必须以 runId 幂等提交业务状态。
     * false 表示业务入口尚未准备好，底座会重试；不得在此重新执行工具或生成新计划。
     */
    default boolean commit(RunCompletion completion) { return true; }
}
