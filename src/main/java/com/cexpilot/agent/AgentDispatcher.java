package com.cexpilot.agent;

import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 共享 Harness：上下文投影、注册路由、用量聚合和流式交付；无业务执行分支。 */
@Component
public class AgentDispatcher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final IntentRouter router;
    private final AgentRegistry agents;
    private final AgentContextAssembler contexts;
    private final com.cexpilot.config.QueryExecutionConfig queryConfig;
    private final com.cexpilot.config.HarnessRolloutConfig rollout;
    private final AnalysisMonitorHandoff handoffs;
    private final ReactEngine reactEngine;
    private final PlanEngine planEngine;
    public AgentDispatcher(IntentRouter router, AgentRegistry agents, AgentContextAssembler contexts) {
        this(router,agents,contexts,new com.cexpilot.config.QueryExecutionConfig());
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,AgentContextAssembler contexts,com.cexpilot.config.QueryExecutionConfig queryConfig) {
        this(router,agents,contexts,queryConfig,new com.cexpilot.config.HarnessRolloutConfig(),new AnalysisMonitorHandoff());
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,AgentContextAssembler contexts,
            com.cexpilot.config.QueryExecutionConfig queryConfig,com.cexpilot.config.HarnessRolloutConfig rollout,AnalysisMonitorHandoff handoffs) {
        this(router,agents,contexts,queryConfig,rollout,handoffs,null,null);
    }
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,AgentContextAssembler contexts,
            com.cexpilot.config.QueryExecutionConfig queryConfig,com.cexpilot.config.HarnessRolloutConfig rollout,AnalysisMonitorHandoff handoffs,
            ReactEngine reactEngine) {
        this(router,agents,contexts,queryConfig,rollout,handoffs,reactEngine,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,AgentContextAssembler contexts,
            com.cexpilot.config.QueryExecutionConfig queryConfig,com.cexpilot.config.HarnessRolloutConfig rollout,AnalysisMonitorHandoff handoffs,
            ReactEngine reactEngine,PlanEngine planEngine) {
        this.router=router;this.agents=agents;this.contexts=contexts;this.queryConfig=queryConfig;this.rollout=rollout;this.handoffs=handoffs;
        this.reactEngine=reactEngine;this.planEngine=planEngine;
    }
    /**
     * Harness 全链路的提示词版本指纹：聚合所有 Agent、Router、灰度策略与交接版本。
     *
     * @return 组合版本串的指纹，用于 trace 记录与复现
     */
    public String promptVersion() {
        return PromptStore.fingerprint(agents.promptVersion()+router.promptVersion()+rollout.version()+AnalysisMonitorHandoff.VERSION);
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
    /**
     * 已进入共享预算作用域后的执行主体：补充状态、投影上下文、调用 Router，再按注册表执行目标 Agent。
     * Query 私有输入在本方法构造；满足条件时建立分析转监控交接，最终聚合 Router 用量并完成答案交付。
     *
     * @param req 本轮执行的完整入参，字段语义见 {@link AgentExecutionRequest}
     * @param gates 非空的本轮灰度决策快照，由外层根据 visitor 计算；控制可见路由、执行准入和显式交接，
     *              adaptive 开关已在外层合入当前预算，此处不重新分组或创建预算
     * @return 聚合 Router 用量并标记实际 route 的执行结果；clarify 路由直接返回澄清结果，不执行业务 Agent
     */
    private ExecutionResult executeBudgeted(AgentExecutionRequest req,
            com.cexpilot.config.HarnessRolloutConfig.Decision gates) {
        var routingState=agents.enrichRoutingState(req.visitor(),req.routingState());
        if(req.progress()!=null) req.progress().accept("正在识别查询或监控意图…");
        IntentRouter.Decision decision;
        try { decision=router.route(req.question(),contexts.routerView(req.history(),routingState,req.requestContext()),req.traceId(),req.sink(),agents.definitions(gates)); }
        catch(IntentRouter.Failure failure) {
            throw new ExecutionFailure(failure.getCause(),new TurnCounters(0,0,0,0,0,0,List.of()).withRouterUsages(failure.usages));
        }
        req.selectedAgent().accept(decision.route());
        if("clarify".equals(decision.route())) {
            var payload=JSON.createObjectNode().put("status","NEEDS_CLARIFICATION").put("execution_status","NOT_CREATED");
            var result=new ExecutionResult(decision.question(),JSON.createArrayNode(),0,0,0,0,"NEEDS_CLARIFICATION",decision.question())
                    .withLlmUsages(List.of()).withAgent("clarify",payload).withRouterUsages(decision.usages());
            emit(req.delta(),result); return result;
        }
        // 完整对话视图只服务执行阶段，路由确定后再组装
        var context=contexts.context(req.history(),req.planningContext(),routingState,req.requestContext());
        ExecutionResult result;
        ConversationAgent agent;
        try {
            agent=agents.require(decision.route());
            if(!gates.allows(agent.rolloutGroup())) throw new IllegalArgumentException("Agent 尚未对当前访客开放");
            req.sink().record(new TraceEvent(req.traceId(),"AGENT_DISPATCH","agent_registry",null,
                    JSON.createObjectNode().put("agent_id",agent.definition().id()).put("route",decision.route()).toString(),
                    null,null,null,null,null,null));
            var privateInputs=Map.of(MarketAnalysisAgent.DEFINITION.id(),
                    new MarketAnalysisAgent.QueryInput(req.planningContext(),req.compiledPlanSink(),decision.resolvedQuestion()));
            var handoff=gates.handoff()?handoffs.prepare(decision.route(),req.planningContext(),req.session(),context,req.traceId(),req.sink()):null;
            var run=new AgentRunContext(req.question(),req.history(),req.traceId(),req.sink(),req.delta(),req.requestContext(),req.progress(),req.session(),routingState,context,
                    req.visitor(),privateInputs.get(agent.definition().id()));
            ExecutionResult executed;
            try {
                // 底座按引擎声明分发：REACT/PLAN 由对应引擎执行声明式 Agent；BUILTIN 为内置 Agent 过渡路径（阶段 3 后移除）
                executed=switch(agent.engine()) {
                    case REACT -> {
                        if(reactEngine==null) throw new IllegalStateException("ReactEngine 未装配");
                        yield reactEngine.execute(agent,run);
                    }
                    case PLAN -> {
                        if(planEngine==null) throw new IllegalStateException("PlanEngine 未装配");
                        yield planEngine.execute(agent,run);
                    }
                    case BUILTIN -> agent.execute(run);
                };
            }
            catch(RuntimeException failure) {
                try { handoffs.failed(handoff,req.traceId(),req.sink()); }
                catch(RuntimeException traceFailure) { failure.addSuppressed(traceFailure); }
                throw failure;
            }
            try { executed=handoffs.complete(executed,handoff,req.traceId(),req.sink()); }
            catch(RuntimeException failure) { throw executionFailure(failure,executed); }
            result=executed.withAgent(agent.definition().route(),executed.agentPayload()).withRouterUsages(decision.usages());
        } catch(ExecutionFailure failure) {
            throw new ExecutionFailure(failure.getCause(),failure.counters.withRouterUsages(decision.usages()),failure.taskScope,failure.evidence);
        } catch(RuntimeException failure) {
            throw new ExecutionFailure(failure,new TurnCounters(0,0,0,0,0,0,List.of()).withRouterUsages(decision.usages()));
        }
        // Query 在原运行时内流式交付；规划型 Agent 由公共层交付一次结果。
        if(!agent.streamsAnswer()) emit(req.delta(),result);
        return result;
    }
    /** 通过 delta 回调交付最终答案一次；delta 为 null 时跳过；回调抛错包装为 ExecutionFailure 上抛。 */
    private void emit(Consumer<String> delta,ExecutionResult result) {
        if(delta==null) return;
        try { delta.accept(result.answer()); }
        catch(RuntimeException failure) {
            throw executionFailure(failure,result);
        }
    }
    /** 把执行期异常连同结果中已累计的计数、任务范围和证据包装成 ExecutionFailure。 */
    private ExecutionFailure executionFailure(RuntimeException failure,ExecutionResult result) {
        return new ExecutionFailure(failure,new TurnCounters(result.plannerLlmCalls(),result.answerLlmCalls(),result.metricFetchCount(),
                result.calculationCount(),result.httpRequestCount(),result.reusedFactCount(),result.savedFactIds(),result.llmUsages(),result.routerLlmCalls(),result.toolCallCount()),
                result.taskScope(),result.evidence());
    }
}
