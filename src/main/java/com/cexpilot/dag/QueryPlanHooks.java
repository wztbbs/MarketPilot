package com.cexpilot.dag;

import com.cexpilot.agent.AgentRunContext;
import com.cexpilot.agent.MarketAnalysisAgent;
import com.cexpilot.agent.PlanHooks;
import com.cexpilot.conversation.PlanningContext;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.TraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Query 的规划方言（{@link PlanHooks} 自驱动实现）：规划、执行与回答整体委托 DagRuntime
 * （其规划循环是双模式修复状态机，不套引擎的均匀重试循环；任务生命周期、事实复用、
 * adaptive 续规划全部保留在 DagRuntime 管道内），结果经终态通道返回。
 * 引擎负责分发与生命周期，本钩子负责 Query 私有输入的组装与 QUERY_REWRITE 落迹。
 */
public class QueryPlanHooks implements PlanHooks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DagRuntime runtime;

    public QueryPlanHooks(DagRuntime runtime) { this.runtime = runtime; }

    @Override public PlanVerdict planSelfDriven(AgentRunContext run) {
        var input = (MarketAnalysisAgent.QueryInput) run.privateInput();
        PlanningContext plannerInput = queryInput(input == null ? null : input.plannerInput(), run.routingState());
        if (plannerInput == null) plannerInput = new PlanningContext("", null);
        plannerInput = plannerInput.withResolvedQuestion(input == null ? null : input.resolvedQuestion());
        var rewrite = JSON.createObjectNode().put("original_question", run.question())
                .put("resolved_question", input == null ? null : input.resolvedQuestion())
                .put("changed", input != null && !Objects.equals(run.question(), input.resolvedQuestion()));
        run.sink().record(new TraceEvent(run.traceId(), "QUERY_REWRITE", "intent_router", null, rewrite.toString(), null, null, null, null, null, null));
        var result = runtime.execute(run.question(), run.history(), run.traceId(), run.sink(), run.delta(),
                run.request(), run.progress(), input == null ? null : input.compiledPlanSink(), plannerInput, run.session());
        return PlanVerdict.terminal(result);
    }

    @Override public ExecutionResult complete(PlanCompletion completion) {
        throw new UnsupportedOperationException("Query 自驱动路径在 planSelfDriven 内完成执行");
    }

    /**
     * 当上一轮的活跃 Agent 是 monitor 时，向 PlanningContext 追加一段跨 Agent 提示：
     * 说明上文的监控候选仅供指代解析，本轮仍按一次性查询处理。
     */
    private PlanningContext queryInput(PlanningContext input, JsonNode state) {
        if (input == null || state == null || !"monitor".equals(state.path("active_agent").asText())) return input;
        var summary = JSON.createObjectNode();
        summary.set("instruments", state.at("/monitor_context/candidate").at("/plan/metrics"));
        String extra = "\n【跨 Agent 查询】上一轮讨论的是监控候选计划。上文查询任务仅是历史候选，不能默认作为本轮当前任务。"
                + "用户若询问监控中的对象，可参考以下候选定义解析指代；本轮新建一次查询，明确回访查询任务时可用历史索引。"
                + "这不是行情事实，也不是执行监控的指令：" + summary;
        return new PlanningContext(input.contextText() + extra, null, input.evidence(), input.textHistoryCount(), input.candidates());
    }
}
