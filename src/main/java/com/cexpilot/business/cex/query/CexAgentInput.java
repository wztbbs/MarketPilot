package com.cexpilot.business.cex.query;

import com.cexpilot.platform.agent.AgentInput;
import com.cexpilot.business.cex.conversation.PlanningContext;
import com.cexpilot.business.cex.metric.CompiledPlan;
import java.util.function.Consumer;

/** CEXPilot 入口提供的查询私有输入；由业务上下文适配器分发给 Query。 */
public record CexAgentInput(PlanningContext planningContext, Consumer<CompiledPlan> compiledPlanSink) implements AgentInput {}
