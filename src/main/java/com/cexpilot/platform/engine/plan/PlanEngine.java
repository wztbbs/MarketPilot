package com.cexpilot.platform.engine.plan;

import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.agent.ConversationAgent;
import com.cexpilot.platform.agent.DeclaredAgent;
import com.cexpilot.platform.llm.PlanningJson;
import com.cexpilot.platform.llm.ModelOutputException;
import com.cexpilot.platform.llm.LlmCallService;

import com.cexpilot.platform.engine.dag.DagExecutor;
import com.cexpilot.platform.evidence.ToolEvidenceWriter;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.platform.execution.TurnCounters;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 底座 Plan 引擎：拥有"规划 → 校验 → 执行 → 回答"的循环骨架（提示词渲染、LLM 调用、修复重试、
 * 用量聚合、失败包装），业务语义经 {@link PlanHooks} 注入。
 * 声明式 Agent（{@link EngineKind#PLAN}）使用引擎内置的 {@link DeclarativePlanHooks}
 * （工具/计算节点 DAG）；实现 {@link PlanDrivenAgent} 的内置 Agent 注入自己的方言与组装。
 * 引擎只负责执行语义；任务生命周期、续规划等重语义不属于本引擎。
 */
@Component
public class PlanEngine {
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final LlmCallService llm;
    private final ToolEvidenceWriter evidence;
    private final DagExecutor dagExecutor;
    private final com.cexpilot.platform.plan.PreparedPlanService preparedPlans;
    private final com.cexpilot.platform.plan.PreparedPlanExecutor preparedExecutor;

    public PlanEngine(ToolRegistry tools, PromptStore prompts, LlmCallService llm, ToolEvidenceWriter evidence, DagExecutor dagExecutor) {
        this(tools,prompts,llm,evidence,dagExecutor,null,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PlanEngine(ToolRegistry tools, PromptStore prompts, LlmCallService llm, ToolEvidenceWriter evidence, DagExecutor dagExecutor,
                      com.cexpilot.platform.plan.PreparedPlanService preparedPlans, com.cexpilot.platform.plan.PreparedPlanExecutor preparedExecutor) {
        this.tools = tools; this.prompts = prompts; this.llm = llm; this.evidence = evidence; this.dagExecutor = dagExecutor;
        this.preparedPlans = preparedPlans; this.preparedExecutor = preparedExecutor;
    }

    /**
     * 执行一个 plan 型 Agent：声明式 Agent 使用引擎内置回调并收紧预算；
     * 内置 Agent 注入自定义回调，预算沿用外层请求作用域。
     *
     * @param agent 待执行的 Agent
     * @param run   共享运行上下文
     * @return 执行结果（答案、证据事实数组、计数与 Agent 载荷）
     */
    public ExecutionResult execute(ConversationAgent agent, AgentRunContext run) {
        if (agent instanceof DeclaredAgent declared) {
            var definition = declared.configured();
            if (definition.toolApprovalMode() == com.cexpilot.platform.plan.ToolApprovalMode.PREPARED_PLAN && (preparedPlans == null || preparedExecutor == null))
                throw new IllegalStateException("Plan 方案审批服务未装配");
            try (var scope = QueryBudget.open(definition.limits())) {
                QueryBudget.current().tighten(definition.limits());
                return drive(new DeclarativePlanHooks(definition, tools, prompts, llm, evidence, dagExecutor, preparedPlans, preparedExecutor), run);
            }
        }
        if (agent instanceof PlanDrivenAgent driven) return drive(driven.planHooks(), run);
        throw new IllegalArgumentException("PlanEngine 无法执行: " + agent.definition().id());
    }

    /**
     * 规划循环骨架：渲染系统提示词 → 逐轮调用模型 → 解析并交由业务校验 →
     * 通过则交业务组装，失败按业务文案修复重试，耗尽或预算超限走业务降级；
     * 业务组装抛出的 ExecutionFailure 原样上抛，其余异常连同已累积用量包装。
     */
    private ExecutionResult drive(PlanHooks hooks, AgentRunContext run) {
        var usages = new ArrayList<LlmUsage>();
        int plannerCalls = 0;
        try {
            var selfDriven = hooks.planSelfDriven(run);
            if (selfDriven != null) {
                if (selfDriven.terminal() != null) return selfDriven.terminal();
                return hooks.complete(new PlanHooks.PlanCompletion(run, selfDriven.compiled(), List.copyOf(usages), plannerCalls));
            }
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException waiting) { throw waiting; }
        catch (ExecutionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new ExecutionFailure(failure, new TurnCounters(0, 0, 0, 0, 0, 0, List.of()));
        }
        hooks.beforePlanning(run);
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(prompts.render(hooks.planningPrompt(), hooks.planningVars())));
        messages.add(ChatMessage.user(hooks.planningInput(run).toString()));
        try {
            for (int attempt = 0; attempt < hooks.maxAttempts(); attempt++) {
                int before = usages.size();
                ChatResponse response;
                try { response = llm.call(hooks.stage(), hooks.planningPrompt(), messages, run.traceId(), run.sink(), usages); }
                finally { plannerCalls += usages.size() - before; }
                if (response.hasToolCalls()) {
                    hooks.planRejected(response.content(), "规划不能调用工具", run);
                    messages.add(ChatMessage.assistant(response.content() == null ? "" : response.content(), null));
                    messages.add(ChatMessage.user(hooks.repairMessage("规划不能调用工具")));
                    continue;
                }
                JsonNode output;
                try { output = PlanningJson.parse(response.content()); }
                catch (ModelOutputException invalid) {
                    hooks.planRejected(response.content(), invalid.getMessage(), run);
                    messages.add(ChatMessage.assistant(response.content() == null ? "" : response.content(), null));
                    messages.add(ChatMessage.user(hooks.repairMessage(invalid.getMessage())));
                    continue;
                }
                var verdict = hooks.validate(output, run, usages);
                if (verdict.terminal() != null) return verdict.terminal();
                if (verdict.compiled() != null)
                    return hooks.complete(new PlanHooks.PlanCompletion(run, verdict.compiled(), List.copyOf(usages), plannerCalls));
                messages.add(ChatMessage.assistant(response.content() == null ? "" : response.content(), null));
                messages.add(ChatMessage.user(hooks.repairMessage(verdict.repairFeedback())));
            }
            return hooks.exhausted(new PlanHooks.PlanCompletion(run, null, List.copyOf(usages), plannerCalls));
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException waiting) { throw waiting; }
        catch (ExecutionFailure failure) { throw failure; }
        catch (QueryBudget.Exhausted exhausted) {
            return hooks.budgetExhausted(new PlanHooks.PlanCompletion(run, null, List.copyOf(usages), plannerCalls), exhausted);
        } catch (RuntimeException failure) {
            throw new ExecutionFailure(failure, new TurnCounters(plannerCalls, 0, 0, 0, 0, 0, java.util.List.of()).withLlmUsages(usages));
        }
    }
}
