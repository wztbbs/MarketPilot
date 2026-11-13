package com.cexpilot.platform.observability;

/**
 * 一次 LLM 调用、Tool 调用或 DAG 规划的轨迹事件（审计回放需要记录的内容）。
 *
 * @param traceId          所属 Trace 标识
 * @param eventType        LLM_CALL / TOOL_CALL / PLAN / ROUTE_DECISION / AGENT_DISPATCH 等
 * @param name             事件主体名（阶段名/工具名/节点名）
 * @param inputJson        输入快照（JSON 字符串）；可为 null
 * @param outputJson       输出快照（JSON 字符串）；可为 null
 * @param durationMs       耗时（毫秒）；可为 null
 * @param promptTokens     LLM 输入 token；可为 null
 * @param completionTokens LLM 输出 token；可为 null
 * @param ttftMs           首 token 延迟（毫秒）；可为 null
 * @param cachedTokens     命中缓存的 token 数；可为 null
 * @param error            错误信息；null 表示成功
 */
public record TraceEvent(String traceId,
                         String eventType,   // LLM_CALL / TOOL_CALL / PLAN
                         String name,
                         String inputJson,
                         String outputJson,
                         Long durationMs,
                         Integer promptTokens,
                         Integer completionTokens,
                         Long ttftMs,
                         Integer cachedTokens,
                         String error) {

    /** 构造一条 LLM_CALL 事件（含 token 与延迟指标）。 */
    public static TraceEvent llmCall(String traceId, String name, String inputJson, String outputJson,
                                     Long durationMs, Integer promptTokens, Integer completionTokens,
                                     Long ttftMs, Integer cachedTokens, String error) {
        return new TraceEvent(traceId, "LLM_CALL", name, inputJson, outputJson,
                durationMs, promptTokens, completionTokens, ttftMs, cachedTokens, error);
    }

    /** 构造一条 TOOL_CALL 事件（无 LLM 指标）。 */
    public static TraceEvent toolCall(String traceId, String toolName, String inputJson,
                                      String outputJson, Long durationMs, String error) {
        return new TraceEvent(traceId, "TOOL_CALL", toolName, inputJson, outputJson,
                durationMs, null, null, null, null, error);
    }

    /** DAG 规划事件：每次生成的 Plan（含校验结果）落一条，error 为 null 表示校验通过。 */
    public static TraceEvent plan(String traceId, String outputJson, String error) {
        return new TraceEvent(traceId, "PLAN", "dag_planner", null, outputJson,
                null, null, null, null, null, error);
    }
}
