package com.cexpilot.platform.observability;

/**
 * Trace 出口。Phase 1 用 DB 实现（DbTraceSink），后续可平移到 OpenTelemetry Collector。
 */
public interface TraceSink {

    /** 落一条轨迹事件；实现应非阻塞、失败不影响主流程。 */
    void record(TraceEvent event);

    /** 失败路径保留原异常；记录失败作为 suppressed 交给最终异常处理边界统一记录。 */
    default void recordFailure(TraceEvent event, Throwable primary) {
        try {
            record(event);
        } catch (RuntimeException secondary) {
            if (secondary != primary) primary.addSuppressed(secondary);
        }
    }
}
