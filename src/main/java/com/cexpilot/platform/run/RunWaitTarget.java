package com.cexpilot.platform.run;

/** 等待对象必须显式区分工具调用和方案审批，不能共用含义模糊的 invocationId。 */
public record RunWaitTarget(Kind kind, String id) {
    public enum Kind { TOOL_CALL, PLAN_APPROVAL }
    public RunWaitTarget { if (kind == null || id == null || id.isBlank()) throw new IllegalArgumentException("等待必须关联持久化对象"); }
    public static RunWaitTarget toolCall(String id) { return new RunWaitTarget(Kind.TOOL_CALL, id); }
    public static RunWaitTarget planApproval(String id) { return new RunWaitTarget(Kind.PLAN_APPROVAL, id); }
}
