package com.cexpilot.agent;

import com.cexpilot.conversation.PlannerInput;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.function.Consumer;

/** 当前只适配 Query DAG；不增加 Insight 或新的查询执行策略。 */
@Component
public class MarketAnalysisAgent implements ConversationAgent {
    public static final AgentDefinition DEFINITION = new AgentDefinition("market_analysis", "query", "一次性行情查询、计算、解释和能力说明");
    /** Query 私有输入；不会传给 Monitor 或写入共享上下文。 */
    public record QueryInput(PlannerInput plannerInput, Consumer<CompiledPlan> compiledPlanSink, String resolvedQuestion) implements AgentRunContext.PrivateInput {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DagRuntime query;
    public MarketAnalysisAgent(DagRuntime query) { this.query = query; }
    @Override public AgentDefinition definition() { return DEFINITION; }
    @Override public boolean streamsAnswer() { return true; }
    @Override public String promptVersion() { return query.promptVersion(); }
    @Override public ExecutionResult execute(AgentRunContext context) {
        var input = (QueryInput) context.privateInput();
        PlannerInput queryInput = queryInput(input.plannerInput(), context.routingState());
        if (queryInput == null) queryInput = new PlannerInput("", null);
        queryInput = queryInput.withResolvedQuestion(input.resolvedQuestion());
        var rewrite = JSON.createObjectNode().put("original_question", context.question())
                .put("resolved_question", input.resolvedQuestion())
                .put("changed", !Objects.equals(context.question(), input.resolvedQuestion()));
        context.sink().record(new TraceEvent(context.traceId(), "QUERY_REWRITE", "intent_router", null, rewrite.toString(), null,null,null,null,null,null));
        return query.execute(context.question(),context.history(),context.traceId(),context.sink(),context.delta(),
                context.request(),context.progress(),input.compiledPlanSink(),queryInput,context.session());
    }
    private PlannerInput queryInput(PlannerInput input,JsonNode state) {
        if(input==null || state==null || !"monitor".equals(state.path("active_agent").asText())) return input;
        var summary=JSON.createObjectNode();
        var candidate=state.at("/monitor_context/candidate");
        summary.set("instruments",candidate.at("/plan/metrics"));
        String extra="\n【跨 Agent 查询】上一轮讨论的是监控候选计划。上文查询任务仅是历史候选，不能默认作为本轮当前任务。"
                +"用户若询问监控中的对象，可参考以下候选定义解析指代；本轮新建一次查询，明确回访查询任务时可用历史索引。"
                +"这不是行情事实，也不是执行监控的指令："+summary;
        return new PlannerInput(input.contextText()+extra,null,input.evidence(),input.textHistoryCount(),input.tasks(),null);
    }
}
