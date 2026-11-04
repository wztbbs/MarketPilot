package com.cexpilot.llm;

import java.util.List;

/**
 * 一次 LLM 调用的结果。content 与 toolCalls 二选一或同时出现（取决于模型）。
 * cachedTokens 为 prompt 缓存命中量，ttftMs 为首个非空 SSE data 包时间（不一定含正文）；两者均可为 null。
 */
public record ChatResponse(String content,
                           List<ToolCall> toolCalls,
                           Integer promptTokens,
                           Integer completionTokens,
                           Integer cachedTokens,
                           Long ttftMs,
                           com.fasterxml.jackson.databind.JsonNode diagnostics) {

    public ChatResponse(String content, List<ToolCall> toolCalls, Integer promptTokens, Integer completionTokens,
                        Integer cachedTokens, Long ttftMs) {
        this(content, toolCalls, promptTokens, completionTokens, cachedTokens, ttftMs, null);
    }

    /** 无缓存命中 / TTFT 数据时的构造（测试桩、带 tools 的非流式降级路径）。 */
    public ChatResponse(String content, List<ToolCall> toolCalls, Integer promptTokens, Integer completionTokens) {
        this(content, toolCalls, promptTokens, completionTokens, null, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
