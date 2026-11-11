package com.cexpilot.runtime;

/**
 * Trace 出口。Phase 1 用 DB 实现（DbTraceSink），后续可平移到 OpenTelemetry Collector。
 */
public interface TraceSink {

    /** 落一条轨迹事件；实现应非阻塞、失败不影响主流程。 */
    void record(TraceEvent event);
}
