package com.cexpilot.platform.run;

/** 正常控制流：释放当前工作线程，等待工具结果或方案审批变化；不得转换成模型修复或 ToolResult.failure。 */
public final class RunSuspended extends RuntimeException {
    private final RunWaitTarget target;
    public RunSuspended(String invocationId) { this(RunWaitTarget.toolCall(invocationId)); }
    public RunSuspended(RunWaitTarget target) { super("运行等待 " + target.kind() + ": " + target.id()); this.target = target; }
    public static RunSuspended approval(String planId) { return new RunSuspended(RunWaitTarget.planApproval(planId)); }
    public RunWaitTarget target() { return target; }
    public String invocationId() {
        if (target.kind() != RunWaitTarget.Kind.TOOL_CALL) throw new IllegalStateException("当前等待对象不是工具调用");
        return target.id();
    }
}
