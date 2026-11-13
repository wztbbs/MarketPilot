package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.react.ReactEngine;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.stereotype.Component;

/** 声明式 ReAct/Plan 是运行协议的一种适配；原决策日志恢复方式保留。 */
@Component
public final class DeclaredAgentRunAdapter implements AgentRunAdapter {
    private final ReactEngine react;
    private final PlanEngine plan;
    public DeclaredAgentRunAdapter(ReactEngine react, PlanEngine plan) { this.react = react; this.plan = plan; }
    public String id() { return "declared-agent"; }
    public String version() { return "decision-journal-v1"; }
    public boolean supports(ConversationAgent agent) { return agent instanceof DeclaredAgent; }
    public QueryExecutionConfig limits(ConversationAgent agent) { return ((DeclaredAgent) agent).configured().limits(); }
    public com.cexpilot.platform.plan.ToolApprovalMode toolApprovalMode(ConversationAgent agent) { return ((DeclaredAgent) agent).configured().toolApprovalMode(); }
    public JsonNode captureInput(ConversationAgent agent, AgentRunContext context) {
        if (context.privateInput() != null) throw new IllegalArgumentException("声明式 Agent 不接收业务私有输入");
        return NullNode.instance;
    }
    public AgentRunContext.PrivateInput restoreInput(ConversationAgent agent, JsonNode snapshot) {
        if (snapshot != null && !snapshot.isNull()) throw new IllegalArgumentException("声明式运行输入格式不匹配");
        return null;
    }
    public RunStep executeSegment(ConversationAgent agent, RunSegment segment) {
        return RunStep.completed(switch (agent.engine()) {
            case REACT -> react.execute(agent, segment.context());
            case PLAN -> plan.execute(agent, segment.context());
            default -> throw new IllegalArgumentException("声明式运行引擎不支持: " + agent.engine());
        });
    }
}
