package com.cexpilot.agent;

import com.cexpilot.prompt.PromptStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** 启动时注册与校验；未注册路由不能执行，不默认落入 Monitor。 */
@Component
public class AgentRegistry {
    private final Map<String, ConversationAgent> byRoute;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRegistry(List<ConversationAgent> agents,ConfiguredAgentFactory configured) {
        this(combined(agents,configured.create()));
    }
    private static List<ConversationAgent> combined(List<ConversationAgent> builtins,List<ConversationAgent> configured) {
        var all=new ArrayList<>(builtins);all.addAll(configured);return all;
    }
    public AgentRegistry(List<ConversationAgent> agents) {
        var routes = new TreeMap<String, ConversationAgent>();
        var ids = new HashSet<String>();
        for (var agent : agents) {
            var definition = agent.definition();
            if (!ids.add(definition.id()) || routes.putIfAbsent(definition.route(), agent) != null)
                throw new IllegalStateException("重复 Agent id 或 route: " + definition);
        }
        if (routes.isEmpty()) throw new IllegalStateException("至少注册一个 Agent");
        byRoute = Collections.unmodifiableMap(routes);
    }
    public ConversationAgent require(String route) {
        var agent = byRoute.get(route);
        if (agent == null) throw new IllegalArgumentException("未注册的 Agent 路由: " + route);
        return agent;
    }
    public List<AgentDefinition> definitions() {
        return byRoute.values().stream().map(ConversationAgent::definition).toList();
    }
    public List<AgentDefinition> definitions(com.cexpilot.config.HarnessRolloutConfig.Decision decision) {
        return byRoute.values().stream().filter(agent -> decision.allows(agent.rolloutGroup()))
                .map(ConversationAgent::definition).toList();
    }
    public JsonNode enrichRoutingState(String visitor, JsonNode state) {
        JsonNode enriched = state == null ? null : state.deepCopy();
        for (var agent : byRoute.values()) enriched = agent.enrichRoutingState(visitor, enriched);
        return enriched;
    }
    public String promptVersion() {
        var versions = new StringBuilder();
        byRoute.values().forEach(agent -> versions.append(agent.definition()).append(':').append(agent.promptVersion()).append('\n'));
        return PromptStore.fingerprint(versions.toString());
    }
}
