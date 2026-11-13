package com.cexpilot.platform.agent;

import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolRegistry;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.util.*;

/** 把 AgentDefinitionLoader 加载的 YAML 定义装配成 DeclaredAgent Bean（声明载体，执行归底座引擎）。 */
@Component
public class ConfiguredAgentFactory {
    private final ResourceLoader loader;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final List<AgentRegistrationPolicy> policies;
    public ConfiguredAgentFactory(ResourceLoader loader,ToolRegistry tools,PromptStore prompts) {
        this(loader, tools, prompts, List.of());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ConfiguredAgentFactory(ResourceLoader loader, ToolRegistry tools, PromptStore prompts, List<AgentRegistrationPolicy> policies) {
        this.loader=loader;this.tools=tools;this.prompts=prompts;this.policies=List.copyOf(policies);
    }
    /**
     * 加载全部声明式定义并实例化 enabled 的 Agent。
     * id/route 不得重复；应用可以注入额外的名称保留规则。
     *
     * @return 已启用的声明式 Agent 列表（可能为空）；冲突或配置错误抛 IllegalStateException
     */
    public List<ConversationAgent> create() {
        var result=new ArrayList<ConversationAgent>();
        var ids=new HashSet<String>();var routes=new HashSet<String>();
        for(var definition:AgentDefinitionLoader.load(loader)) {
            policies.forEach(policy -> policy.validate(definition));
            if(!ids.add(definition.agent().id()) || !routes.add(definition.agent().route()))
                throw new IllegalStateException("重复或保留的 Agent id/route");
            if(definition.enabled())result.add(new DeclaredAgent(definition,tools,prompts));
        }
        return result;
    }
}
