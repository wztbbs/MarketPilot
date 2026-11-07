package com.cexpilot.agent;

import com.cexpilot.evidence.EvidenceStore;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ToolRegistry;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConfiguredAgentFactory {
    private final ResourceLoader loader;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final PlanningLlm llm;
    private final EvidenceStore evidence;
    public ConfiguredAgentFactory(ResourceLoader loader,ToolRegistry tools,PromptStore prompts,PlanningLlm llm,EvidenceStore evidence) {
        this.loader=loader;this.tools=tools;this.prompts=prompts;this.llm=llm;this.evidence=evidence;
    }
    public List<ConversationAgent> create() {
        var result=new ArrayList<ConversationAgent>();
        var ids=new HashSet<String>();var routes=new HashSet<String>();
        for(var definition:AgentDefinitionLoader.load(loader)) {
            if(!ids.add(definition.agent().id()) || !routes.add(definition.agent().route())
                    || Set.of("market_analysis","monitor").contains(definition.agent().id()) || Set.of("query","monitor").contains(definition.agent().route()))
                throw new IllegalStateException("重复或保留的 Agent id/route");
            if(definition.enabled())result.add(new HttpToolAgent(definition,tools,prompts,llm,evidence));
        }
        return result;
    }
}
