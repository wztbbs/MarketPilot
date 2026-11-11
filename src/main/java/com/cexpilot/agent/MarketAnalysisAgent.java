package com.cexpilot.agent;

import com.cexpilot.conversation.PlanningContext;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.dag.QueryPlanHooks;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.runtime.ExecutionResult;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Query 业务边界：执行循环归底座 PlanEngine，业务语义经 QueryPlanHooks 注入。 */
@Component
public class MarketAnalysisAgent implements PlanDrivenAgent {
    public static final AgentDefinition DEFINITION = new AgentDefinition("market_analysis", "query", "一次性行情查询、计算、解释和能力说明");
    /**
     * Query 私有输入；不会传给 Monitor 或写入共享上下文。
     *
     * @param plannerInput     Query 结构化规划输入；可为 null
     * @param compiledPlanSink 编译计划回传回调；可为 null
     * @param resolvedQuestion Router 指代消解后的独立问题；可为 null（Query 内部自行回落）
     */
    public record QueryInput(PlanningContext plannerInput, Consumer<CompiledPlan> compiledPlanSink, String resolvedQuestion) implements AgentRunContext.PrivateInput {}
    private final DagRuntime query;
    private final QueryPlanHooks hooks;
    public MarketAnalysisAgent(DagRuntime query) { this.query = query; this.hooks = new QueryPlanHooks(query); }
    @Override public AgentDefinition definition() { return DEFINITION; }
    @Override public boolean streamsAnswer() { return true; }
    @Override public String promptVersion() { return query.promptVersion(); }
    @Override public PlanHooks planHooks() { return hooks; }
    @Override public ExecutionResult execute(AgentRunContext context) {
        throw new IllegalStateException("MarketAnalysisAgent 由底座 PlanEngine 执行，不应直接调用 execute");
    }
}
