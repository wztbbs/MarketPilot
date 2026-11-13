package com.cexpilot.business.cex.context;

import com.cexpilot.platform.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

/** 即使声明被禁用，也不允许它占用 CEXPilot 内置 Agent 的身份。 */
@Component
public class CexAgentRegistrationPolicy implements AgentRegistrationPolicy {
    @Override public void validate(ConfiguredAgentDefinition definition) {
        if (Set.of("market_analysis", "monitor").contains(definition.agent().id())
                || Set.of("query", "monitor").contains(definition.agent().route()))
            throw new IllegalStateException("重复或保留的 Agent id/route");
    }
}
