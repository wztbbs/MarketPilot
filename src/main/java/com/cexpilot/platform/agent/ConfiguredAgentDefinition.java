package com.cexpilot.platform.agent;

import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import java.util.Set;

/**
 * YAML 声明式 Agent 的配置载体；不替代 Query 或 Monitor 生命周期。
 *
 * @param agent        静态身份定义（id/route/description）
 * @param enabled      是否启用；禁用的定义只参与校验，不注册为可路由 Agent
 * @param engine       执行引擎：react（HTTP 工具决策循环）或 plan（DAG 计划执行），由底座对应引擎接管
 * @param prompt       业务提示词模板名（小写字母/数字/_/-，最长 64），由 PromptStore 解析
 * @param allowedTools 允许调用的工具名白名单，非空；构造时转为不可变集合
 * @param maxSteps     决策循环最大步数，1~16（react 引擎使用；plan 引擎当前单轮规划，忽略该值）
 * @param limits       请求级预算上限（模型/工具调用次数、超时、token），构造时校验
 * @param toolApprovalMode 服务端选择逐调用确认或方案审批，默认 PER_CALL
 */
public record ConfiguredAgentDefinition(AgentDefinition agent,boolean enabled,EngineKind engine,String prompt,Set<String> allowedTools,
        int maxSteps,QueryExecutionConfig limits, com.cexpilot.platform.plan.ToolApprovalMode toolApprovalMode) {
    public ConfiguredAgentDefinition(AgentDefinition agent, boolean enabled, EngineKind engine, String prompt, Set<String> allowedTools,
                                     int maxSteps, QueryExecutionConfig limits) {
        this(agent, enabled, engine, prompt, allowedTools, maxSteps, limits, com.cexpilot.platform.plan.ToolApprovalMode.PER_CALL);
    }
    public ConfiguredAgentDefinition {
        if(prompt==null || !prompt.matches("[a-z][a-z0-9_-]{0,63}") || allowedTools==null || allowedTools.isEmpty()
                || allowedTools.stream().anyMatch(t->!t.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) || maxSteps<1 || maxSteps>16 || toolApprovalMode == null)
            throw new IllegalArgumentException("非法声明式 Agent 配置");
        allowedTools=Set.copyOf(allowedTools);limits.validate();
    }
}
