package com.cexpilot.agent;

/**
 * Agent 的执行引擎类型。底座按该声明把执行分发给对应引擎，业务 Agent 只提供声明与回调。
 */
public enum EngineKind {

    /** 底座 ReactEngine 执行的决策循环（call/finish/clarify），声明式 Agent 使用。 */
    REACT,

    /** 底座 PlanEngine 执行的 DAG 计划引擎：LLM 生成工具/计算节点计划，引擎校验并拓扑分层执行。 */
    PLAN,

    /**
     * 过渡值：内置 Agent（query/monitor）自带 execute() 实现，不经过底座引擎。
     * 待通用 plan 引擎就绪、内置 Agent 完成迁移后删除。
     */
    BUILTIN
}
