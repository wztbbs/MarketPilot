package com.cexpilot.platform.agent;

import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.tool.ToolRegistry;

/**
 * YAML 声明式 Agent 的注册形态：只携带声明（身份、引擎、提示词、工具白名单、步数与预算），
 * 不含执行逻辑——执行由底座引擎按 {@link #engine()} 分发接管（react → ReactEngine，plan → PlanEngine）。
 * 不接管 Query / Monitor 状态。
 */
public final class DeclaredAgent implements ToolBoundAgent {
    private final ConfiguredAgentDefinition definition;
    private final ToolRegistry tools;
    private final PromptStore prompts;

    public DeclaredAgent(ConfiguredAgentDefinition definition, ToolRegistry tools, PromptStore prompts) {
        this.definition = definition; this.tools = tools; this.prompts = prompts;
        switch (definition.engine()) {
            case REACT -> {
                prompts.version(definition.prompt()); prompts.version(reactPrompt()); prompts.version("react_base_answer");
                for (String name : definition.allowedTools()) if (tools.spec(name)==null) throw new IllegalArgumentException("react 引擎只允许已注册的工具: " + name);
            }
            case PLAN -> {
                prompts.version(definition.prompt()); prompts.version(planPrompt()); prompts.version("plan_agent_answer");
                for (String name : definition.allowedTools()) if (tools.spec(name) == null) throw new IllegalArgumentException("plan 引擎只允许已注册的工具: " + name);
            }
            case BUILTIN -> throw new IllegalArgumentException("声明式 Agent 不支持 builtin 引擎");
        }
    }

    /** @return 完整声明（含引擎、提示词、工具白名单、步数与预算上限），供引擎执行 */
    public ConfiguredAgentDefinition configured() { return definition; }

    @Override public java.util.Set<String> allowedTools() { return definition.allowedTools(); }
    @Override public AgentDefinition definition() { return definition.agent(); }
    @Override public EngineKind engine() { return definition.engine(); }
    @Override public com.cexpilot.platform.execution.config.HarnessRolloutConfig.Group rolloutGroup() { return com.cexpilot.platform.execution.config.HarnessRolloutConfig.Group.HTTP_AGENT; }

    /**
     * 组装版本指纹：Agent 定义、引擎、提示词版本、步数与预算上限、各工具契约版本。
     *
     * @return 组合指纹，任何提示词或工具契约变化都会改变该值
     */
    @Override public String promptVersion() {
        var version = new StringBuilder(definition.agent().toString()).append(definition.engine()).append(definition.prompt()).append(definition.maxSteps());
        if (definition.toolApprovalMode() == com.cexpilot.platform.plan.ToolApprovalMode.PREPARED_PLAN) {
            version.append(":prepared-plan-v1");
        }
        switch (definition.engine()) {
            case REACT -> version.append(prompts.version(definition.prompt())).append(prompts.version(reactPrompt())).append(prompts.version("react_base_answer"));
            case PLAN -> version.append(prompts.version(definition.prompt())).append(prompts.version(planPrompt())).append(prompts.version("plan_agent_answer"));
            default -> { }
        }
        definition.allowedTools().stream().sorted().forEach(name -> version.append(name).append(tools.contractVersion(name)));
        var p = definition.limits(); version.append(p.getMaxModelCalls()).append(':').append(p.getMaxToolCalls()).append(':').append(p.getTimeoutMs()).append(':').append(p.getMaxObservedTokens());
        return PromptStore.fingerprint(version.toString());
    }

    private String reactPrompt() { return definition.toolApprovalMode() == com.cexpilot.platform.plan.ToolApprovalMode.PREPARED_PLAN ? "react_base_prepare" : "react_base_decision"; }
    private String planPrompt() { return definition.toolApprovalMode() == com.cexpilot.platform.plan.ToolApprovalMode.PREPARED_PLAN ? "plan_base_prepare" : "plan_agent"; }

    @Override public ExecutionResult execute(AgentRunContext context) {
        throw new IllegalStateException("声明式 Agent 由底座引擎执行，不应直接调用 execute");
    }
}
