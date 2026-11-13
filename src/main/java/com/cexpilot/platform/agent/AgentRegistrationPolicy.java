package com.cexpilot.platform.agent;

/** 应用可增加注册约束，例如为其内置 Agent 保留名称；底座只校验公共契约。 */
public interface AgentRegistrationPolicy {
    void validate(ConfiguredAgentDefinition definition);
}
