package com.cexpilot.platform.run;

import com.cexpilot.platform.execution.ExecutionResult;

/** 应用投影收口。false 表示入口的 WAITING 轮次尚未提交，调度器会重试；实现必须幂等。 */
public interface RunCompletionListener {
    boolean completed(DurableStore.Entry run, ExecutionResult result);
}
