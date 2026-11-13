package com.cexpilot.platform.agent;

import com.cexpilot.platform.prompt.PromptStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** 启动时注册与校验；未注册路由不能执行，不默认落入 Monitor。 */
@Component
public class AgentRegistry {
    private final Map<String, ConversationAgent> byRoute;
    private List<AgentAccessPolicy> accessPolicies = List.of();
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRegistry(List<ConversationAgent> agents, ConfiguredAgentFactory configured, List<AgentAccessPolicy> policies) {
        this(combined(agents,configured.create()));accessPolicies=List.copyOf(policies);
    }
    public AgentRegistry(List<ConversationAgent> agents,ConfiguredAgentFactory configured) {
        this(combined(agents,configured.create()));
    }
    /** 合并内置 Bean 与 YAML 声明式 Agent，供构造注入使用。 */
    private static List<ConversationAgent> combined(List<ConversationAgent> builtins,List<ConversationAgent> configured) {
        var all=new ArrayList<>(builtins);all.addAll(configured);return all;
    }
    /**
     * 注册全部 Agent：id 与 route 均不允许重复，至少注册一个，否则启动失败。
     *
     * @param agents 内置与声明式 Agent 的合集
     */
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
    /**
     * 按对外 route 取 Agent；未注册即抛 IllegalArgumentException（不默认落入 Monitor）。
     *
     * @param route 路由结果中的对外 route
     * @return 对应的 ConversationAgent，必非 null
     */
    public ConversationAgent require(String route) {
        var agent = byRoute.get(route);
        if (agent == null) throw new IllegalArgumentException("未注册的 Agent 路由: " + route);
        return agent;
    }
    /** @return 全部已注册 Agent 的定义（不过滤灰度） */
    public List<AgentDefinition> definitions() {
        return byRoute.values().stream().map(ConversationAgent::definition).toList();
    }
    /**
     * 按灰度决策过滤后的可路由 Agent 定义，供 Router 生成候选路由清单。
     *
     * @param decision 本轮访客的灰度决策
     * @return 当前访客可见的 Agent 定义列表
     */
    public List<AgentDefinition> definitions(com.cexpilot.platform.execution.config.HarnessRolloutConfig.Decision decision) {
        return definitions(null,decision);
    }
    public List<AgentDefinition> definitions(String visitor, com.cexpilot.platform.execution.config.HarnessRolloutConfig.Decision decision) {
        return byRoute.values().stream().filter(agent -> decision.allows(agent.rolloutGroup()) && allowed(agent,visitor))
                .map(ConversationAgent::definition).toList();
    }
    public boolean allowed(ConversationAgent agent,String visitor) {return accessPolicies.stream().allMatch(policy->policy.allows(agent,visitor));}
    public ConversationAgent requireAllowed(String route,String visitor) {
        var agent=require(route);if(!allowed(agent,visitor))throw new SecurityException("Agent 尚未对当前访客开放");return agent;
    }
    /**
     * 路由前依次让各 Agent 补充服务端持久化状态（如 Monitor 的草稿状态）。
     *
     * @param visitor 可信访客标识，供按访客读取状态；可为 null
     * @param state   已保存的路由状态；可为 null
     * @return 各 Agent 补充后的状态副本；入参为 null 且无补充时仍为 null
     */
    public JsonNode enrichRoutingState(String visitor, JsonNode state) {
        JsonNode enriched = state == null ? null : state.deepCopy();
        for (var agent : byRoute.values()) enriched = agent.enrichRoutingState(visitor, enriched);
        return enriched;
    }
    /** @return 所有已注册 Agent 的 definition+promptVersion 组合指纹 */
    public String promptVersion() {
        var versions = new StringBuilder();
        byRoute.values().forEach(agent -> versions.append(agent.definition()).append(':').append(agent.promptVersion()).append('\n'));
        return PromptStore.fingerprint(versions.toString());
    }
}
