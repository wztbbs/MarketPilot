package com.cexpilot.platform.execution;

import com.cexpilot.platform.llm.LlmUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * 公共运行计数，供 Query、Monitor 和 Router 的成功/失败路径使用。
 *
 * @param plannerLlmCalls  planner 阶段 LLM 调用次数（含修复重试）
 * @param answerLlmCalls   answer 阶段 LLM 调用次数
 * @param metricFetchCount metric 节点真实取数次数
 * @param calculationCount 算子节点执行次数
 * @param httpRequestCount 真实发出的交易所 HTTP 请求数
 * @param reusedFactCount  REUSE_FACT 复用的历史事实数
 * @param savedFactIds     本轮新保存的事实 id 列表；null 归一为空列表
 * @param llmUsages        每次调用的模型与用量；null 表示未知（兼容旧路径）
 * @param routerLlmCalls   Router 阶段 LLM 调用次数
 * @param toolCallCount    工具调用次数（声明式 Agent 使用）
 */
public record TurnCounters(int plannerLlmCalls, int answerLlmCalls, int metricFetchCount,
                           int calculationCount, int httpRequestCount, int reusedFactCount,
                           List<String> savedFactIds, List<LlmUsage> llmUsages,int routerLlmCalls,int toolCallCount) {

    public TurnCounters(int plannerLlmCalls,int answerLlmCalls,int metricFetchCount,int calculationCount,int httpRequestCount,int reusedFactCount,
            List<String> savedFactIds,List<LlmUsage> usages,int routerLlmCalls) {
        this(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,reusedFactCount,savedFactIds,usages,routerLlmCalls,0);
    }
    /** @return 替换 toolCallCount 后的副本 */
    public TurnCounters withToolCalls(int count) {
        return new TurnCounters(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,reusedFactCount,savedFactIds,llmUsages,routerLlmCalls,count);
    }
    public TurnCounters(int plannerLlmCalls,int answerLlmCalls,int metricFetchCount,int calculationCount,
            int httpRequestCount,int reusedFactCount,List<String> savedFactIds,List<LlmUsage> usages) {
        this(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,reusedFactCount,savedFactIds,usages,0);
    }

    public TurnCounters(int plannerLlmCalls, int answerLlmCalls, int metricFetchCount,
            int calculationCount, int httpRequestCount, int reusedFactCount, List<String> savedFactIds) {
        this(plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount, httpRequestCount,
                reusedFactCount, savedFactIds, null);
    }

    /** @return 替换 llmUsages 后的副本 */
    public TurnCounters withLlmUsages(List<LlmUsage> usages) {
        return new TurnCounters(plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount,
                httpRequestCount, reusedFactCount, savedFactIds, usages,routerLlmCalls,toolCallCount);
    }

    /**
     * 合并 Router 用量：routing 追加到 llmUsages 前部，routerLlmCalls 记为路由调用次数；
     * 原用量缺失但计数非零时补一条 unknown 占位。
     */
    public TurnCounters withRouterUsages(List<LlmUsage> routing) {
        var combined=new ArrayList<>(routing);
        if(llmUsages!=null) combined.addAll(llmUsages);
        else if(plannerLlmCalls+answerLlmCalls>0) combined.add(new LlmUsage("unknown",null,null,null,null));
        return new TurnCounters(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,
                reusedFactCount,savedFactIds,combined,routing.size(),toolCallCount);
    }

    public TurnCounters {
        savedFactIds = savedFactIds == null ? List.of() : List.copyOf(savedFactIds);
        llmUsages = llmUsages == null ? null : List.copyOf(llmUsages);
    }
}
