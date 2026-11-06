package com.cexpilot.agent;

import com.cexpilot.conversation.PlannerInput;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
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
    public AgentDispatcher(IntentRouter router, AgentRegistry agents, AgentContextAssembler contexts) {
        this(router,agents,contexts,new com.cexpilot.config.QueryExecutionConfig());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentDispatcher(IntentRouter router,AgentRegistry agents,AgentContextAssembler contexts,com.cexpilot.config.QueryExecutionConfig queryConfig) {
        this.router=router;this.agents=agents;this.contexts=contexts;this.queryConfig=queryConfig;
    }
    public String promptVersion() {
        return PromptStore.fingerprint(agents.promptVersion()+router.promptVersion());
    }
    // 保留 AskService 的入口契约；Query 私有输入仅在此兼容适配处构造。
    public ExecutionResult execute(String question,String history,String traceId,TraceSink sink,Consumer<String> delta,
            RequestContext request,Consumer<String> progress,Consumer<CompiledPlan> compiledPlanSink,PlannerInput input,
            EvidenceSession session,JsonNode routingState,Consumer<String> selectedAgent) {
        return execute(question,history,traceId,sink,delta,request,progress,compiledPlanSink,input,session,routingState,selectedAgent,null);
    }
    public ExecutionResult execute(String question,String history,String traceId,TraceSink sink,Consumer<String> delta,
            RequestContext request,Consumer<String> progress,Consumer<CompiledPlan> compiledPlanSink,PlannerInput input,
            EvidenceSession session,JsonNode routingState,Consumer<String> selectedAgent,String visitor) {
        try(var scope=QueryBudget.open(queryConfig)) {
            return executeBudgeted(question,history,traceId,sink,delta,request,progress,compiledPlanSink,input,session,routingState,selectedAgent,visitor);
        }
    }
    private ExecutionResult executeBudgeted(String question,String history,String traceId,TraceSink sink,Consumer<String> delta,
            RequestContext request,Consumer<String> progress,Consumer<CompiledPlan> compiledPlanSink,PlannerInput input,
            EvidenceSession session,JsonNode routingState,Consumer<String> selectedAgent,String visitor) {
        routingState=agents.enrichRoutingState(visitor,routingState);
        var context=contexts.context(history,input,routingState,request);
        if(progress!=null) progress.accept("正在识别查询或监控意图…");
        IntentRouter.Decision decision;
        try { decision=router.route(question,contexts.routerContext(context),traceId,sink,agents.definitions()); }
        catch(IntentRouter.Failure failure) {
            throw new ExecutionFailure(failure.getCause(),new TurnCounters(0,0,0,0,0,0,List.of()).withRouterUsages(failure.usages));
        }
        selectedAgent.accept(decision.route());
        if("clarify".equals(decision.route())) {
            var payload=JSON.createObjectNode().put("status","NEEDS_CLARIFICATION").put("execution_status","NOT_CREATED");
            var result=new ExecutionResult(decision.question(),JSON.createArrayNode(),0,0,0,0,"NEEDS_CLARIFICATION",decision.question())
                    .withLlmUsages(List.of()).withAgent("clarify",payload).withRouterUsages(decision.usages());
            emit(delta,result); return result;
        }
        ExecutionResult result;
        ConversationAgent agent;
        try {
            agent=agents.require(decision.route());
            sink.record(new TraceEvent(traceId,"AGENT_DISPATCH","agent_registry",null,
                    JSON.createObjectNode().put("agent_id",agent.definition().id()).put("route",decision.route()).toString(),
                    null,null,null,null,null,null));
            var privateInputs=Map.of(MarketAnalysisAgent.DEFINITION.id(),
                    new MarketAnalysisAgent.QueryInput(input,compiledPlanSink,decision.resolvedQuestion()));
            var run=new AgentRunContext(question,history,traceId,sink,delta,request,progress,session,routingState,context,
                    visitor,privateInputs.get(agent.definition().id()));
            var executed=agent.execute(run);
            result=executed.withAgent(agent.definition().route(),executed.agentPayload()).withRouterUsages(decision.usages());
        } catch(ExecutionFailure failure) {
            throw new ExecutionFailure(failure.getCause(),failure.counters.withRouterUsages(decision.usages()),failure.taskScope,failure.evidence);
        } catch(RuntimeException failure) {
            throw new ExecutionFailure(failure,new TurnCounters(0,0,0,0,0,0,List.of()).withRouterUsages(decision.usages()));
        }
        // Query 在原运行时内流式交付；规划型 Agent 由公共层交付一次结果。
        if(!agent.streamsAnswer()) emit(delta,result);
        return result;
    }
    private void emit(Consumer<String> delta,ExecutionResult result) {
        if(delta==null) return;
        try { delta.accept(result.answer()); }
        catch(RuntimeException failure) {
            throw new ExecutionFailure(failure,new TurnCounters(result.plannerLlmCalls(),result.answerLlmCalls(),result.metricFetchCount(),
                    result.calculationCount(),result.httpRequestCount(),result.reusedFactCount(),result.savedFactIds(),result.llmUsages(),result.routerLlmCalls()),
                    result.taskScope(),result.evidence());
        }
    }
}
