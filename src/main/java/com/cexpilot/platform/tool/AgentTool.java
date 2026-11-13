package com.cexpilot.platform.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 所有可被 LLM 调用的工具都实现它。
 * 工具内部是确定性代码：取数、标准化、计算都在 execute 里完成，
 * 返回给 LLM 的是 Structured Facts，不是交易所原始 JSON。
 */
public interface AgentTool {

    /** @return 工具名，与 YAML 定义同名，全局唯一 */
    String name();

    /**
     * 执行一次工具调用（确定性代码：取数、标准化、计算）。
     *
     * @param args 已按 input schema 校验并应用默认值的参数
     * @param ctx  本次调用的上下文（trace/会话归属、时间基准、身份与授权）
     * @return 结构化结果（Structured Facts），不是交易所原始 JSON
     */
    ToolResult execute(JsonNode args, ToolContext ctx);
}
