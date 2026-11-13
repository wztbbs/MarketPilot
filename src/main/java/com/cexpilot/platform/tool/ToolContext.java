package com.cexpilot.platform.tool;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 一次工具调用的上下文：trace 归属 + 对话归属 + 请求时间上下文。
 *
 * @param traceId        本轮 Trace 标识
 * @param conversationId 所属会话 id；可为 null
 * @param agentId        调用方 Agent 的内部 id；HTTP 工具鉴权使用，可为 null（兼容旧路径）
 * @param visitorId      服务端可信访客标识；HTTP 工具鉴权使用，可为 null
 * @param allowedTools   调用方获准使用的工具名集合；null 归一为空集合
 * @param timezone    请求上下文携带的用户时区；null 表示未携带，工具按各自默认口径回落
 * @param requestTime 本次请求固定的时间基准；null 表示未携带，工具可自行读取时钟（不推荐）
 */
public record ToolContext(String traceId, String conversationId, ZoneId timezone, Instant requestTime, String agentId, String visitorId, java.util.Set<String> allowedTools, String invocationId) {
    public ToolContext(String traceId, String conversationId, ZoneId timezone, Instant requestTime, String agentId, String visitorId, java.util.Set<String> allowedTools) {
        this(traceId, conversationId, timezone, requestTime, agentId, visitorId, allowedTools, null);
    }

    public ToolContext(String traceId,String conversationId,ZoneId timezone,Instant requestTime) {
        this(traceId,conversationId,timezone,requestTime,null,null,java.util.Set.of());
    }
    public ToolContext {allowedTools=allowedTools==null?java.util.Set.of():java.util.Set.copyOf(allowedTools);}
    public ToolContext(String traceId, String conversationId) {
        this(traceId, conversationId, null, null);
    }

    public ToolContext(String traceId, String conversationId, ZoneId timezone) {
        this(traceId, conversationId, timezone, null);
    }
}
