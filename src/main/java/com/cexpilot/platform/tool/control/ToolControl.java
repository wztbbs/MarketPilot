package com.cexpilot.platform.tool.control;

import java.util.List;

/** 执行语义与传输协议无关。resourceVersions 为参数中的 JSON Pointer，值随调用冻结。 */
public record ToolControl(Kind kind, boolean confirmationRequired, String summary,
                          List<String> resourceVersions, boolean idempotent, long expiresAfterSeconds) {
    public enum Kind { READ, OPERATION }
    public ToolControl {
        resourceVersions = resourceVersions == null ? List.of() : List.copyOf(resourceVersions);
        if (kind == null || summary == null || summary.isBlank() || summary.length() > 500
                || expiresAfterSeconds < 60 || expiresAfterSeconds > 604800
                || resourceVersions.stream().anyMatch(p -> !p.startsWith("/")))
            throw new IllegalArgumentException("非法工具执行控制声明");
        if (kind == Kind.READ && confirmationRequired) throw new IllegalArgumentException("READ 工具不需要操作确认");
    }
    public static ToolControl read() { return new ToolControl(Kind.READ, false, "读取数据", List.of(), true, 86400); }
}
