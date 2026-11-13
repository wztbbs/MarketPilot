package com.cexpilot.platform.plan;

import com.cexpilot.platform.tool.ToolResult;
import java.util.*;

/** 已执行的真实结果与未开始的调用分别返回；PARTIAL 不表示整体事务回滚。 */
public record PlanExecutionResult(PlanExecutionStatus status, Map<String, ToolResult> results, List<String> notStarted) {
    public PlanExecutionResult { results = Collections.unmodifiableMap(new LinkedHashMap<>(results)); notStarted = List.copyOf(notStarted); }
}
