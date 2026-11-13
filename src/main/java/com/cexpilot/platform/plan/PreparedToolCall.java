package com.cexpilot.platform.plan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一笔工具调用实例，不是独立的业务操作／权限定义。arguments 可包含已批准的参数引用。 */
public record PreparedToolCall(String id, String tool, JsonNode arguments, List<String> dependsOn) {
    public PreparedToolCall {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,39}") || tool == null || !tool.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                || arguments == null || !arguments.isObject()) throw new IllegalArgumentException("非法方案工具调用");
        arguments = arguments.deepCopy();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        if (dependsOn.stream().distinct().count() != dependsOn.size()) throw new IllegalArgumentException("调用依赖不能重复");
    }
    @Override public JsonNode arguments() { return arguments.deepCopy(); }
}
