package com.cexpilot.agent;

/** id 是内部稳定标识；route 是对外及存量会话使用的兼容标识。 */
public record AgentDefinition(String id, String route, String description) {
    public AgentDefinition {
        if (id == null || !id.matches("[a-z][a-z0-9_]{0,39}")
                || route == null || !route.matches("[a-z][a-z0-9_]{0,15}")
                || "clarify".equals(route) || description == null || description.isBlank()) {
            throw new IllegalArgumentException("非法 Agent 定义或保留路由: " + id + "/" + route);
        }
    }
}
