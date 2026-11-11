package com.cexpilot.agent;

/**
 * 由底座 PlanEngine 驱动的内置 Agent：执行循环归引擎，业务语义经 {@link PlanHooks} 注入。
 * 实现类的 execute() 不会被调用（应直接抛异常防止绕过引擎）。
 */
public interface PlanDrivenAgent extends ConversationAgent {

    /** @return 业务规划方言与结果组装回调 */
    PlanHooks planHooks();

    @Override default EngineKind engine() { return EngineKind.PLAN; }
}
