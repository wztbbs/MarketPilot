package com.cexpilot.platform.tool.control;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 每次执行/确认重新读取当前注册关系，撤销 Agent 的工具绑定立即生效。 */
@Component
public class RegisteredAgentToolPolicy implements ToolAccessPolicy {
    private final ObjectProvider<AgentRegistry> agents;
    public RegisteredAgentToolPolicy(ObjectProvider<AgentRegistry> agents) { this.agents = agents; }
    @Override public void check(ToolDefinition definition, JsonNode args, ToolContext context) {
        checkBinding(definition, context);
    }
    @Override public void checkBinding(ToolDefinition definition, ToolContext context) {
        if ("system_readonly".equals(context.agentId()) && definition.control().kind() == ToolControl.Kind.READ) return;
        var registry = agents.getObject();
        var registered = registry.definitions().stream().filter(a -> a.id().equals(context.agentId())).findFirst()
                .orElseThrow(() -> new SecurityException("Agent 已注销"));
        var agent = registry.require(registered.route());
        if (agent instanceof ToolBoundAgent bound) {
            if (!bound.allowedTools().contains(definition.name())) throw new SecurityException("Agent 工具绑定已撤销");
        } else if (definition.control().kind() != ToolControl.Kind.READ) {
            throw new SecurityException("内置只读执行路径没有操作工具授权");
        }
    }
}
