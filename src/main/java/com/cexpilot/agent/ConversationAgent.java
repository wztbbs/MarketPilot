package com.cexpilot.agent;

import com.cexpilot.runtime.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;

/** 单次对话的业务边界；后台任务生命周期仍归业务模块。 */
public interface ConversationAgent {
    AgentDefinition definition();
    String promptVersion();
    ExecutionResult execute(AgentRunContext context);

    /** 内置能力始终可路由；新增声明式能力可先按访客灰度。 */
    default com.cexpilot.config.HarnessRolloutConfig.Group rolloutGroup() {
        return com.cexpilot.config.HarnessRolloutConfig.Group.CORE;
    }

    /** 流式 Agent 自行交付所有终态（含澄清和拒答），其他 Agent 由 Harness 交付一次。 */
    default boolean streamsAnswer() { return false; }

    /** 在路由前补充服务端持久化状态，不执行模型或行情工具。 */
    default JsonNode enrichRoutingState(String visitor, JsonNode state) { return state; }
}
