package com.cexpilot.business.demo;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.ToolAccessPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** 开关控制整组装配；定义放在独立资源目录，不参与默认扫描。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true", matchIfMissing = false)
public class DemoConfiguration {
    @Bean @ConfigurationProperties(prefix = "demo")
    public DemoProperties demoProperties() { return new DemoProperties(); }

    @Bean
    public DemoTickets demoTickets(JdbcTemplate jdbc, Clock clock, DemoProperties properties) {
        return new DemoTickets(jdbc, clock, properties);
    }

    @Bean
    public ToolDefinitionSource demoDefinitions(ResourceLoader loader) {
        return () -> ToolDefinitionLoader.load(loader, "classpath*:demo/tools/*.yml");
    }

    @Bean
    public AgentTool demoCreate(DemoTickets tickets) {
        return new AgentTool() {
            public String name() { return "demo.ticket.create"; }
            public ToolResult execute(JsonNode args, ToolContext context) {
                return tickets.create(args, context, false).result();
            }
        };
    }

    @Bean
    public AsyncAgentTool demoCreateAsync(DemoTickets tickets) {
        return new AsyncAgentTool() {
            public String name() { return "demo.ticket.create_async"; }
            public ToolExecution submit(JsonNode args, ToolContext context) { return tickets.create(args, context, true); }
            public ToolExecution poll(String id, ToolContext context) { return tickets.poll(id, context); }
            public ToolExecution recover(JsonNode args, ToolContext context) { return tickets.recover(args, context); }
        };
    }

    @Bean
    public AgentTool demoAssign(DemoTickets tickets) {
        return new AgentTool() {
            public String name() { return "demo.ticket.assign"; }
            public ToolResult execute(JsonNode args, ToolContext context) { return tickets.assign(args, context); }
        };
    }

    @Bean
    public AgentAccessPolicy demoAgentAccess(DemoProperties properties) {
        return (agent, visitor) -> !agent.definition().id().startsWith("demo_") || properties.allows(visitor);
    }

    @Bean
    public ToolAccessPolicy demoToolAccess(DemoProperties properties) {
        return (definition, args, context) -> {
            if (!definition.name().startsWith("demo.")) return;
            properties.check(context.visitorId());
            if (context.agentId() == null || !context.agentId().startsWith("demo_"))
                throw new SecurityException("Demo 工具仅允许 Demo Agent");
        };
    }

    @Bean
    public DeclaredAgent demoReact(ResourceLoader loader, ToolRegistry tools, PromptStore prompts) {
        return agent("demo_react", loader, tools, prompts);
    }

    @Bean
    public DeclaredAgent demoPlan(ResourceLoader loader, ToolRegistry tools, PromptStore prompts) {
        return agent("demo_plan", loader, tools, prompts);
    }

    private DeclaredAgent agent(String id, ResourceLoader loader, ToolRegistry tools, PromptStore prompts) {
        var definition = AgentDefinitionLoader.load(loader, "classpath*:demo/agents/*.yml").stream()
                .filter(candidate -> candidate.agent().id().equals(id)).findFirst().orElseThrow();
        return new DeclaredAgent(definition, tools, prompts);
    }
}
