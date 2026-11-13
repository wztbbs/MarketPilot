package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.execution.ExecutionResult;

/** 业务提交的稳定现场；runId 是提交幂等键，输出已由原适配器恢复为业务类型。 */
public record RunCompletion(String runId, AgentRunContext context, RunCheckpoint checkpoint,
                            ExecutionResult result, boolean failed) {}
