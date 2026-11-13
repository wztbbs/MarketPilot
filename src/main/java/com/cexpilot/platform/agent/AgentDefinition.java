package com.cexpilot.platform.agent;

/**
 * Agent 的静态身份定义。id 是内部稳定标识；route 是对外及存量会话使用的兼容标识。
 *
 * @param id          内部稳定标识，小写字母/数字/下划线，最长 40 字符
 * @param route       对外路由标识（路由结果、会话状态中使用），最长 16 字符；不允许为保留值 "clarify"
 * @param description 面向 Router 的能力描述，非空
 */
public record AgentDefinition(String id, String route, String description) {
    public AgentDefinition {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,39}")
                || route == null || !route.matches("[a-z][a-z0-9_]{0,15}")
                || "clarify".equals(route) || description == null || description.isBlank()) {
            throw new IllegalArgumentException("非法 Agent 定义或保留路由: " + id + "/" + route);
        }
    }
}
