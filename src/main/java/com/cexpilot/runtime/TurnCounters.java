package com.cexpilot.runtime;

import com.cexpilot.llm.LlmUsage;
import java.util.ArrayList;
import java.util.List;

/** 公共运行计数，供 Query、Monitor 和 Router 的成功/失败路径使用。 */
public record TurnCounters(int plannerLlmCalls, int answerLlmCalls, int metricFetchCount,
                           int calculationCount, int httpRequestCount, int reusedFactCount,
                           List<String> savedFactIds, List<LlmUsage> llmUsages,int routerLlmCalls,int toolCallCount) {

    public TurnCounters(int plannerLlmCalls,int answerLlmCalls,int metricFetchCount,int calculationCount,int httpRequestCount,int reusedFactCount,
            List<String> savedFactIds,List<LlmUsage> usages,int routerLlmCalls) {
        this(plannerLlmCalls,answerLlmCalls,metricFetchCount,calculationCount,httpRequestCount,reusedFactCount,savedFactIds,usages,routerLlmCalls,0);
    }
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

    public TurnCounters withLlmUsages(List<LlmUsage> usages) {
        return new TurnCounters(plannerLlmCalls, answerLlmCalls, metricFetchCount, calculationCount,
                httpRequestCount, reusedFactCount, savedFactIds, usages,routerLlmCalls,toolCallCount);
    }

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
