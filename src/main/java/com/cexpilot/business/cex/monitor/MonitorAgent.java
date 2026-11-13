package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.engine.plan.PlanDrivenAgent;
import com.cexpilot.platform.engine.plan.PlanHooks;
import com.cexpilot.platform.agent.AgentDefinition;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 对话阶段只规划并保存待确认草稿；执行循环归底座 PlanEngine，业务语义经 MonitorPlanHooks 注入。 */
@Component
public class MonitorAgent implements PlanDrivenAgent, com.cexpilot.platform.agent.ToolBoundAgent {
    public static final AgentDefinition DEFINITION = new AgentDefinition("monitor", "monitor", "持续行情监控意图及待确认规则规划；具体支持范围由监控规划器校验");
    private final MonitorPlanHooks hooks;
    private final PromptStore prompts;
    private final MonitorConversationFlow flow;
    @Autowired
    public MonitorAgent(MonitorPlanHooks hooks, PromptStore prompts, MonitorConversationFlow flow) {
        this.hooks=hooks; this.prompts=prompts; this.flow=flow;
    }
    @Override public AgentDefinition definition() { return DEFINITION; }
    @Override public String promptVersion() { return hooks.contractVersion(); }
    @Override public java.util.Set<String> allowedTools(){return hooks.allowedTools();}
    @Override public PlanHooks planHooks() { return hooks; }
    @Override public JsonNode enrichRoutingState(String visitor, JsonNode state) {
        return flow!=null && visitor!=null ? flow.enrichState(visitor,state) : state;
    }
    @Override public ExecutionResult execute(AgentRunContext run) {
        throw new IllegalStateException("MonitorAgent 由底座 PlanEngine 执行，不应直接调用 execute");
    }
}
