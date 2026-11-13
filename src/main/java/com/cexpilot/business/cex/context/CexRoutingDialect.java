package com.cexpilot.business.cex.context;

import com.cexpilot.platform.context.RoutingDialect;
import org.springframework.stereotype.Component;

/** 保留 CEXPilot 原有的 Query 改写约定及路由澄清文案。 */
@Component
public class CexRoutingDialect implements RoutingDialect {
    @Override public boolean supportsRewrite(String route) { return "query".equals(route); }
    @Override public String fallbackQuestion() { return "请确认：你想查询一次行情，还是创建或管理持续监控？"; }
}
