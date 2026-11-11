package com.cexpilot.agent;

import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ToolRegistry;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.util.*;

/** 把 AgentDefinitionLoader 加载的 YAML 定义装配成 DeclaredAgent Bean（声明载体，执行归底座引擎）。 */
@Component
public class ConfiguredAgentFactory {
    private final ResourceLoader loader;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    public ConfiguredAgentFactory(ResourceLoader loader,ToolRegistry tools,PromptStore prompts) {
        this.loader=loader;this.tools=tools;this.prompts=prompts;
    }
    /**
     * 加载全部声明式定义并实例化 enabled 的 Agent。
     * id/route 不得重复，也不允许占用内置的 market_analysis/monitor 与 query/monitor。
     *
     * @return 已启用的声明式 Agent 列表（可能为空）；冲突或配置错误抛 IllegalStateException
     */
    public List<ConversationAgent> create() {
        var result=new ArrayList<ConversationAgent>();
        var ids=new HashSet<String>();var routes=new HashSet<String>();
        for(var definition:AgentDefinitionLoader.load(loader)) {
            if(!ids.add(definition.agent().id()) || !routes.add(definition.agent().route())
                    || Set.of("market_analysis","monitor").contains(definition.agent().id()) || Set.of("query","monitor").contains(definition.agent().route()))
                throw new IllegalStateException("重复或保留的 Agent id/route");
            if(definition.enabled())result.add(new DeclaredAgent(definition,tools,prompts));
        }
        return result;
    }
}
