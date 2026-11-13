package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.ConversationAgent;
import org.springframework.stereotype.Component;
import java.util.*;

/** 一个 Agent 最多匹配一个适配器；持久化记录以稳定 id/version 找回原编解码协议。 */
@Component
public final class RunAdapterRegistry {
    private final Map<String, AgentRunAdapter> byId;
    public RunAdapterRegistry(List<AgentRunAdapter> adapters) {
        var registry = new LinkedHashMap<String, AgentRunAdapter>();
        for (var adapter : adapters) {
            if (adapter.id() == null || !adapter.id().matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
                    || adapter.version() == null || adapter.version().isBlank()
                    || registry.putIfAbsent(adapter.id(), adapter) != null)
                throw new IllegalArgumentException("重复或非法的运行适配器");
        }
        byId = Collections.unmodifiableMap(registry);
    }
    public Optional<AgentRunAdapter> find(ConversationAgent agent) {
        var matching = byId.values().stream().filter(adapter -> adapter.supports(agent)).toList();
        if (matching.size() > 1) throw new IllegalStateException("Agent 匹配了多个运行适配器: " + agent.definition().id());
        return matching.stream().findFirst();
    }
    public AgentRunAdapter require(String id, String version, ConversationAgent agent) {
        var adapter = byId.get(id);
        if (adapter == null || !adapter.version().equals(version) || !adapter.supports(agent)
                || find(agent).orElse(null) != adapter)
            throw new IllegalStateException("运行适配器不可用或版本不匹配");
        return adapter;
    }
}
