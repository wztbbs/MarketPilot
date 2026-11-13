package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.AgentRunContext;
import java.util.Objects;

/** 输入每段从持久化快照恢复；checkpoint 必须在提交其对应的外部动作之前保存。 */
public record RunSegment(AgentRunContext context, RunScope scope) {
    public RunSegment { Objects.requireNonNull(context); Objects.requireNonNull(scope); }
    public String runId() { return scope.runId(); }
    public RunCheckpoint checkpoint() { return scope.progress(); }
    public void checkpoint(RunCheckpoint next) { scope.progress(next); }
}
