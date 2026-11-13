package com.cexpilot.platform.agent;

import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;

/** 单次对话的业务边界；后台任务生命周期仍归业务模块。 */
public interface ConversationAgent {

    /** @return Agent 的静态身份定义（id/route/description） */
    AgentDefinition definition();

    /**
     * @return 执行引擎类型；底座据此分发执行。内置 Agent 默认为 {@link EngineKind#BUILTIN}
     *         （过渡值，随 query/monitor 迁入通用引擎后删除），声明式 Agent 声明为
     *         {@link EngineKind#REACT}，由底座 ReactEngine 执行
     */
    default EngineKind engine() { return EngineKind.BUILTIN; }

    /** @return 当前 Agent 提示词与相关配置的版本指纹，用于 trace 复现 */
    String promptVersion();

    /**
     * 执行一轮对话业务。
     *
     * @param context 共享运行上下文（问题、历史、时间基准、会话、私有输入等）
     * @return 本轮执行结果（答案、证据、计数与 Agent 载荷）
     */
    ExecutionResult execute(AgentRunContext context);

    /** 内置能力始终可路由；新增声明式能力可先按访客灰度。 */
    default com.cexpilot.platform.execution.config.HarnessRolloutConfig.Group rolloutGroup() {
        return com.cexpilot.platform.execution.config.HarnessRolloutConfig.Group.CORE;
    }

    /** 流式 Agent 自行交付所有终态（含澄清和拒答），其他 Agent 由 Harness 交付一次。 */
    default boolean streamsAnswer() { return false; }

    /** 在路由前补充服务端持久化状态，不执行模型或行情工具。 */
    default JsonNode enrichRoutingState(String visitor, JsonNode state) { return state; }
}
