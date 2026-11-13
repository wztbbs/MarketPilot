package com.cexpilot.platform.agent;

import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.engine.react.ReactEngine;

import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.execution.ExecutionFailure;
import com.cexpilot.platform.execution.TurnCounters;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cexpilot.platform.execution.config.HarnessRolloutConfig;
import java.util.function.Consumer;

/** 共享 Harness：上下文投影、注册路由、用量聚合和流式交付；无业务执行分支。 */
@Component
public class AgentDispatcher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final IntentRouter router;
    private final AgentRegistry agents;
    private final com.cexpilot.platform.context.AgentContextProvider contexts;
    private final com.cexpilot.platform.execution.config.QueryExecutionConfig queryConfig;
    private final com.cexpilot.platform.execution.config.HarnessRolloutConfig rollout;
    private final com.cexpilot.platform.context.DispatchLifecycle handoffs;
    private final ReactEngine reactEngine;
    private final PlanEngine planEngine;
    private org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.run.RunCoordinator> durableRuns;
    @org.springframework.beans.factory.annotation.Autowired
    void durableRuns(org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.run.RunCoordinator> runs) {this.durableRuns=runs;}
    public AgentDispatcher(IntentRouter router, AgentRegistry agents, com.cexpilot.platform.context.AgentContextProvider contexts) {
        this(router,agents,contexts,new com.cexpilot.platform.execution.config.QueryExecutionConfig());
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,com.cexpilot.platform.context.AgentContextProvider contexts,com.cexpilot.platform.execution.config.QueryExecutionConfig queryConfig) {
        this(router,agents,contexts,queryConfig,new com.cexpilot.platform.execution.config.HarnessRolloutConfig(),com.cexpilot.platform.context.DispatchLifecycle.none());
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,com.cexpilot.platform.context.AgentContextProvider contexts,
            com.cexpilot.platform.execution.config.QueryExecutionConfig queryConfig,com.cexpilot.platform.execution.config.HarnessRolloutConfig rollout,com.cexpilot.platform.context.DispatchLifecycle handoffs) {
        this(router,agents,contexts,queryConfig,rollout,handoffs,null,null);
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,com.cexpilot.platform.context.AgentContextProvider contexts,
            com.cexpilot.platform.execution.config.QueryExecutionConfig queryConfig,com.cexpilot.platform.execution.config.HarnessRolloutConfig rollout,com.cexpilot.platform.context.DispatchLifecycle handoffs,
            ReactEngine reactEngine) {
        this(router,agents,contexts,queryConfig,rollout,handoffs,reactEngine,null);
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,com.cexpilot.platform.context.AgentContextProvider contexts,
            com.cexpilot.platform.execution.config.QueryExecutionConfig queryConfig,com.cexpilot.platform.execution.config.HarnessRolloutConfig rollout,com.cexpilot.platform.context.DispatchLifecycle handoffs,
            ReactEngine reactEngine,PlanEngine planEngine) {
        this.router=router;this.agents=agents;this.contexts=contexts;this.queryConfig=queryConfig;this.rollout=rollout;this.handoffs=handoffs;
        this.reactEngine=reactEngine;this.planEngine=planEngine;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentDispatcher(IntentRouter router, AgentRegistry agents,
            org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.context.AgentContextProvider> contexts,
            com.cexpilot.platform.execution.config.QueryExecutionConfig config,
            com.cexpilot.platform.execution.config.HarnessRolloutConfig rollout,
            org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.context.DispatchLifecycle> lifecycle,
            ReactEngine reactEngine, PlanEngine planEngine) {
        this(router, agents, contexts.getIfAvailable(com.cexpilot.platform.context.BasicAgentContextProvider::new), config, rollout,
                lifecycle.getIfAvailable(com.cexpilot.platform.context.DispatchLifecycle::none), reactEngine, planEngine);
    }
    /**
     * Harness 全链路的提示词版本指纹：聚合所有 Agent、Router、灰度策略与交接版本。
     *
     * @return 组合版本串的指纹，用于 trace 记录与复现
     */
    public String promptVersion() {
        return PromptStore.fingerprint(agents.promptVersion()+router.promptVersion()+rollout.version()+handoffs.version());
    }
    /**
     * 问答执行唯一入口：AskService 的普通问答和 SSE 问答均走这里。
     * 根据访客确定灰度开关，建立或收紧本轮共享预算，再委托 executeBudgeted 路由并执行 Agent。
     * 嵌套调用沿用已有预算；本方法只清理自己创建的预算作用域，不重置已发生的调用计数。
     *
     * @param req 本轮执行的完整入参，字段语义见 {@link AgentExecutionRequest}
     * @return 本轮执行结果，包含答案、证据、终态、Agent 业务载荷和聚合后的 Router/Agent 调用用量
     */
    public ExecutionResult execute(AgentExecutionRequest req) {
        var gates=rollout.decide(req.visitor());
        var limits=rollout.queryConfig(queryConfig,gates);
        try(var scope=QueryBudget.open(limits)) {
            QueryBudget.current().tighten(limits);
            req.sink().record(new TraceEvent(req.traceId(),"HARNESS_ROLLOUT","harness_rollout",null,
                    gates.toJson().put("policy_version",rollout.version()).put("adaptive_query",QueryBudget.current().config().isAdaptiveEnabled()).toString(),
                    null,null,null,null,null,null));
            return executeBudgeted(req,gates);
        }
    }
    /** 预算内的编排顺序：路由 → 组装执行上下文 → 运行 Agent → 交付结果。 */
    private ExecutionResult executeBudgeted(AgentExecutionRequest req, HarnessRolloutConfig.Decision gates) {
        var routingState = agents.enrichRoutingState(req.visitor(), req.routingState());
        var decision = route(req, routingState, gates);
        ExecutionResult result = null;
        try {
            req.selectedAgent().accept(decision.route());
            if ("clarify".equals(decision.route())) {
                result = clarify(decision);
                emit(req.delta(), result);
                return result;
            }
            // 完整对话视图只服务执行阶段，路由确定后再组装。
            var conversationView = contexts.executionContext(req.history(), req.input(), routingState, req.requestContext());
            var dispatched = run(req, decision, routingState, conversationView, gates);
            result = dispatched.result();
            return emitAnswerIfNeeded(req, dispatched);
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException control) {
            throw control;
        } catch (ExecutionFailure failure) {
            throw failure.withRouterUsages(decision.usages());
        } catch (RuntimeException failure) {
            if (result != null) throw executionFailure(failure, result);
            throw new ExecutionFailure(failure, new TurnCounters(0, 0, 0, 0, 0, 0, List.of()).withRouterUsages(decision.usages()));
        }
    }

    /** 路由失败保留已发生的模型用量；选中通知由编排边界处理。 */
    private IntentRouter.Decision route(AgentExecutionRequest req, JsonNode routingState,
                                        HarnessRolloutConfig.Decision gates) {
        if (req.progress() != null) req.progress().accept("正在识别查询或监控意图…");
        IntentRouter.Decision decision;
        try {
            decision = router.route(req.question(), contexts.routerView(req.history(), routingState, req.requestContext()),
                    req.traceId(), req.sink(), agents.definitions(req.visitor(), gates));
        } catch (IntentRouter.Failure failure) {
            throw new ExecutionFailure(failure.getCause(),
                    new TurnCounters(0, 0, 0, 0, 0, 0, List.of()).withRouterUsages(failure.usages));
        }
        return decision;
    }

    /** 路由澄清直接交付，不准备或执行 Agent。 */
    private ExecutionResult clarify(IntentRouter.Decision decision) {
        var payload = JSON.createObjectNode().put("status", "NEEDS_CLARIFICATION").put("execution_status", "NOT_CREATED");
        var result = new ExecutionResult(decision.question(), JSON.createArrayNode(), 0, 0, 0, 0,
                "NEEDS_CLARIFICATION", decision.question())
                .withLlmUsages(List.of()).withAgent("clarify", payload).withRouterUsages(decision.usages());
        return result;
    }

    /** 保留实际 Agent，交付时可判断它是否已自行流式输出。 */
    private record AgentResult(ConversationAgent agent, ExecutionResult result) {}

    /** 校验准入、准备交接与私有输入，并在运行收尾后统一合并 Router 用量。 */
    private AgentResult run(AgentExecutionRequest req, IntentRouter.Decision decision, JsonNode routingState,
                            ObjectNode conversationView, HarnessRolloutConfig.Decision gates) {
        var agent = agents.requireAllowed(decision.route(), req.visitor());
        if (!gates.allows(agent.rolloutGroup())) throw new IllegalArgumentException("Agent 尚未对当前访客开放");
        req.sink().record(new TraceEvent(req.traceId(), "AGENT_DISPATCH", "agent_registry", null,
                JSON.createObjectNode().put("agent_id", agent.definition().id()).put("route", decision.route()).toString(),
                null, null, null, null, null, null));
        var handoff = gates.handoff()
                ? handoffs.prepareForDispatch(decision.route(), req.input(), req.session(), conversationView, req.traceId(), req.sink()) : null;
        var runContext = new AgentRunContext(req.question(), req.history(), req.traceId(), req.sink(), req.delta(),
                req.requestContext(), req.progress(), req.session(), routingState, conversationView, req.visitor(),
                contexts.inputFor(agent, req.input(), decision.resolvedQuestion()));
        var executed = runWithHandoff(agent, runContext, handoff);
        var result = executed.withAgent(agent.definition().route(), executed.agentPayload()).withRouterUsages(decision.usages());
        return new AgentResult(agent, result);
    }

    /** 执行失败触发失败交接；完成交接失败则保留已经取得的结果和计数。 */
    private ExecutionResult runWithHandoff(ConversationAgent agent, AgentRunContext runContext, ObjectNode handoff) {
        ExecutionResult executed;
        try {
            executed = executeAgent(agent, runContext);
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException control) {
            throw control;
        } catch (RuntimeException failure) {
            try { handoffs.failed(handoff, runContext.traceId(), runContext.sink()); }
            catch (RuntimeException traceFailure) { failure.addSuppressed(traceFailure); }
            throw failure;
        }
        try { return handoffs.complete(executed, handoff, runContext.traceId(), runContext.sink()); }
        catch (RuntimeException failure) { throw executionFailure(failure, executed); }
    }

    /** 优先通过持久化运行适配器执行，否则按 Agent 声明选择引擎。 */
    private ExecutionResult executeAgent(ConversationAgent agent, AgentRunContext runContext) {
        var coordinator = durableRuns == null ? null : durableRuns.getIfAvailable();
        if (coordinator != null && coordinator.supports(agent)) return coordinator.execute(agent, runContext);
        return switch (agent.engine()) {
            case REACT -> {
                if (reactEngine == null) throw new IllegalStateException("ReactEngine 未装配");
                yield reactEngine.execute(agent, runContext);
            }
            case PLAN -> {
                if (planEngine == null) throw new IllegalStateException("PlanEngine 未装配");
                yield planEngine.execute(agent, runContext);
            }
            case BUILTIN -> agent.execute(runContext);
        };
    }

    /** 自行流式回答的 Agent 不重复交付终态；WAITING 仍需交付等待提示。 */
    private ExecutionResult emitAnswerIfNeeded(AgentExecutionRequest req, AgentResult dispatched) {
        var result = dispatched.result();
        if (!dispatched.agent().streamsAnswer() || "WAITING".equals(result.turnStatus())) emit(req.delta(), result);
        return result;
    }
    /** 通过 delta 回调交付最终答案一次；失败由 executeBudgeted 统一附带最终结果计数上抛。 */
    private void emit(Consumer<String> delta,ExecutionResult result) {
        if(delta==null) return;
        delta.accept(result.answer());
    }
    /** 把执行期异常连同结果中已累计的计数、任务范围和证据包装成 ExecutionFailure。 */
    private ExecutionFailure executionFailure(RuntimeException failure,ExecutionResult result) {
        return new ExecutionFailure(failure,new TurnCounters(result.plannerLlmCalls(),result.answerLlmCalls(),result.metricFetchCount(),
                result.calculationCount(),result.httpRequestCount(),result.reusedFactCount(),result.savedFactIds(),result.llmUsages(),result.routerLlmCalls(),result.toolCallCount()),
                result.output(),result.evidence());
    }
}
