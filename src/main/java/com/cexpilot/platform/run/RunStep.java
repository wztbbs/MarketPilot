package com.cexpilot.platform.run;

import com.cexpilot.platform.execution.ExecutionResult;
import java.util.Objects;

/** 一段活跃执行的明确结论；等待不属于业务失败或规划修复。 */
public sealed interface RunStep {
    record Completed(ExecutionResult result) implements RunStep {
        public Completed { Objects.requireNonNull(result); }
    }
    record Waiting(RunWaitTarget target) implements RunStep {
        public Waiting { Objects.requireNonNull(target); }
        public Waiting(String invocationId) { this(RunWaitTarget.toolCall(invocationId)); }
        public String invocationId() {
            if (target.kind() != RunWaitTarget.Kind.TOOL_CALL) throw new IllegalStateException("当前等待对象不是工具调用");
            return target.id();
        }
    }
    static Completed completed(ExecutionResult result) { return new Completed(result); }
    static Waiting waiting(String invocationId) { return new Waiting(invocationId); }
    static Waiting waitingForApproval(String planId) { return new Waiting(RunWaitTarget.planApproval(planId)); }
}
