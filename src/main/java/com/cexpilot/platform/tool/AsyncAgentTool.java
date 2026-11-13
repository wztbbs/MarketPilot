package com.cexpilot.platform.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** submit 必须使用 context.invocationId 作为幂等键；recover 在受理结果丢失时按该键查单。 */
public interface AsyncAgentTool extends AgentTool {
    ToolExecution submit(JsonNode args, ToolContext context);
    ToolExecution poll(String jobId, ToolContext context);
    ToolExecution recover(JsonNode args, ToolContext context);
    @Override default ToolResult execute(JsonNode args, ToolContext context) {
        throw new IllegalStateException("异步工具必须通过统一调用入口");
    }
}
