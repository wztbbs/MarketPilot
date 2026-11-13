package com.cexpilot.platform.tool;

/** ACCEPTED 是外部任务已受理，UNKNOWN 是是否受理尚不确定，均不是工具失败。 */
public record ToolExecution(ToolResult result, String jobId, long pollAfterMs, boolean unknown) {
    public ToolExecution {
        if (result == null && !unknown && (jobId == null || jobId.isBlank()))
            throw new IllegalArgumentException("异步受理必须返回稳定 jobId");
        if (result == null && (pollAfterMs < 100 || pollAfterMs > 3600000))
            throw new IllegalArgumentException("非法轮询间隔");
    }
    public static ToolExecution completed(ToolResult result) { return new ToolExecution(java.util.Objects.requireNonNull(result), null, 0, false); }
    public static ToolExecution accepted(String jobId, long pollAfterMs) { return new ToolExecution(null, jobId, pollAfterMs, false); }
    public static ToolExecution unknown(long pollAfterMs) { return new ToolExecution(null, null, pollAfterMs, true); }
}
