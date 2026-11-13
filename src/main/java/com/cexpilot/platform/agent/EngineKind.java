package com.cexpilot.platform.agent;

/**
 * Agent 的执行引擎类型。底座按该声明把执行分发给对应引擎，业务 Agent 只提供声明与回调。
 */
public enum EngineKind {

    /** 底座 ReactEngine 执行的决策循环（call/finish/clarify），声明式 Agent 使用。 */
    REACT,

    /** 经 PlanEngine 分发：使用通用规划循环，或通过 PlanHooks 的自驱动端口执行。 */
    PLAN,

    /**
     * 过渡值：直接调用 Agent.execute() 的过渡路径；已有 Agent 可显式声明由引擎执行。
     * 待通用 plan 引擎就绪、内置 Agent 完成迁移后删除。
     */
    BUILTIN
}
