package com.cexpilot.platform.engine.plan;

import com.cexpilot.platform.agent.ConversationAgent;
import com.cexpilot.platform.agent.EngineKind;

/**
 * 经底座 PlanEngine 分发的 Agent：可使用通用规划循环或显式自驱动路径，语义经 {@link PlanHooks} 注入。
 * 实现类的 execute() 不会被调用（应直接抛异常防止绕过引擎）。
 */
public interface PlanDrivenAgent extends ConversationAgent {

    /** @return 业务规划方言与结果组装回调 */
    PlanHooks planHooks();

    @Override default EngineKind engine() { return EngineKind.PLAN; }
}
