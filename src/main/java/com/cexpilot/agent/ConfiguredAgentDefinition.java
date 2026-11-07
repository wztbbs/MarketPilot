package com.cexpilot.agent;

import com.cexpilot.config.QueryExecutionConfig;
import java.util.Set;

/** 仅声明当前支持的只读 HTTP 工具循环；不替代 Query 或 Monitor 生命周期。 */
public record ConfiguredAgentDefinition(AgentDefinition agent,boolean enabled,String prompt,Set<String> allowedTools,
        int maxSteps,QueryExecutionConfig limits) {
    public ConfiguredAgentDefinition {
        if(prompt==null || !prompt.matches("[a-z][a-z0-9_-]{0,63}") || allowedTools==null || allowedTools.isEmpty()
                || allowedTools.stream().anyMatch(t->!t.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) || maxSteps<1 || maxSteps>16)
            throw new IllegalArgumentException("非法声明式 Agent 配置");
        allowedTools=Set.copyOf(allowedTools);limits.validate();
    }
}
