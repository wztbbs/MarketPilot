package com.cexpilot.agent;

import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.ToolRegistry;

/**
 * YAML 声明式 Agent 的注册形态：只携带声明（身份、引擎、提示词、工具白名单、步数与预算），
 * 不含执行逻辑——执行由底座引擎按 {@link #engine()} 分发接管（react → ReactEngine，plan → PlanEngine）。
 * 不接管 Query / Monitor 状态。
 */
public final class DeclaredAgent implements ConversationAgent {
    private final ConfiguredAgentDefinition definition;
    private final ToolRegistry tools;
    private final PromptStore prompts;

    public DeclaredAgent(ConfiguredAgentDefinition definition, ToolRegistry tools, PromptStore prompts) {
        this.definition = definition; this.tools = tools; this.prompts = prompts;
        switch (definition.engine()) {
            case REACT -> {
                prompts.version(definition.prompt()); prompts.version("http_tool_agent"); prompts.version("http_tool_answer");
                for (String name : definition.allowedTools()) if (!tools.isHttp(name)) throw new IllegalArgumentException("react 引擎只允许已注册的只读 HTTP Tool: " + name);
            }
            case PLAN -> {
                prompts.version(definition.prompt()); prompts.version("plan_agent"); prompts.version("plan_agent_answer");
                for (String name : definition.allowedTools()) if (tools.spec(name) == null) throw new IllegalArgumentException("plan 引擎只允许已注册的工具: " + name);
            }
            case BUILTIN -> throw new IllegalArgumentException("声明式 Agent 不支持 builtin 引擎");
        }
    }

    /** @return 完整声明（含引擎、提示词、工具白名单、步数与预算上限），供引擎执行 */
    public ConfiguredAgentDefinition configured() { return definition; }

    @Override public AgentDefinition definition() { return definition.agent(); }
    @Override public EngineKind engine() { return definition.engine(); }
    @Override public com.cexpilot.config.HarnessRolloutConfig.Group rolloutGroup() { return com.cexpilot.config.HarnessRolloutConfig.Group.HTTP_AGENT; }

    /**
     * 组装版本指纹：Agent 定义、引擎、提示词版本、步数与预算上限、各工具契约版本。
     *
     * @return 组合指纹，任何提示词或工具契约变化都会改变该值
     */
    @Override public String promptVersion() {
        var version = new StringBuilder(definition.agent().toString()).append(definition.engine()).append(definition.prompt()).append(definition.maxSteps());
        switch (definition.engine()) {
            case REACT -> version.append(prompts.version(definition.prompt())).append(prompts.version("http_tool_agent")).append(prompts.version("http_tool_answer"));
            case PLAN -> version.append(prompts.version(definition.prompt())).append(prompts.version("plan_agent")).append(prompts.version("plan_agent_answer"));
            default -> { }
        }
        definition.allowedTools().stream().sorted().forEach(name -> version.append(name).append(tools.contractVersion(name)));
        var p = definition.limits(); version.append(p.getMaxModelCalls()).append(':').append(p.getMaxToolCalls()).append(':').append(p.getTimeoutMs()).append(':').append(p.getMaxObservedTokens());
        return PromptStore.fingerprint(version.toString());
    }

    @Override public ExecutionResult execute(AgentRunContext context) {
        throw new IllegalStateException("声明式 Agent 由底座引擎执行，不应直接调用 execute");
    }
}
