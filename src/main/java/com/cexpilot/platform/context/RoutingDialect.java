package com.cexpilot.platform.context;

/** 路由语义扩展；候选 Agent 始终由注册表提供，不由底座硬编码。 */
public interface RoutingDialect {
    default boolean supportsRewrite(String route) { return false; }
    default String fallbackQuestion() { return "请说明你希望使用哪项能力，以及要完成的事情。"; }
}
