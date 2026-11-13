package com.cexpilot.business.demo;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.execution.config.HarnessRolloutConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoConfigurationTest {
    @org.springframework.boot.context.properties.EnableConfigurationProperties
    @Configuration(proxyBeanMethods=false) static class Dependencies {
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));}
        @Bean Clock clock(){return Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"),ZoneOffset.UTC);}
        @Bean PromptStore prompts(ResourceLoader loader){return new PromptStore(loader);}
        @Bean ToolRegistry registry(List<AgentTool> tools,List<ToolDefinitionSource> sources){return new ToolRegistry(tools,sources.stream().flatMap(s->s.definitions().stream()).toList());}
        @Bean ConversationAgent core(){return new ConversationAgent(){public AgentDefinition definition(){return new AgentDefinition("core","core","正常业务");}public String promptVersion(){return "v1";}public com.cexpilot.platform.execution.ExecutionResult execute(AgentRunContext run){throw new UnsupportedOperationException();}};}
        @Bean AgentRegistry agents(List<ConversationAgent> agents,List<AgentAccessPolicy> policies){var factory=mock(ConfiguredAgentFactory.class);when(factory.create()).thenReturn(List.of());return new AgentRegistry(agents,factory,policies);}
    }
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Dependencies.class,DemoConfiguration.class);
    @Test void disabledDoesNotRegisterAnythingOrCreateDemoTable(){runner.run(context->{
        assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(DemoTickets.class).isEmpty());assertTrue(context.getBeansOfType(ToolDefinitionSource.class).isEmpty());
        assertEquals(1,context.getBean(AgentRegistry.class).definitions().size());assertTrue(context.getBean(ToolRegistry.class).specs().isEmpty());
        assertEquals(0,context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='demo_ticket'",Integer.class));
    });}
    @Test void enabledLoadsSeparateDefinitionsAndEnforcesWhitelist(){runner.withPropertyValues("demo.enabled=true","demo.visitors=alice").run(context->{
        assertNull(context.getStartupFailure());var agents=context.getBean(AgentRegistry.class);var gates=new HarnessRolloutConfig.Decision(true,true,true);
        assertEquals(3,agents.definitions("alice",gates).size());assertEquals(1,agents.definitions("bob",gates).size());assertEquals(1,agents.definitions(null,gates).size());
        assertThrows(SecurityException.class,()->agents.requireAllowed("demo_react","bob"));assertNotNull(agents.requireAllowed("demo_plan","alice"));
        var registry=context.getBean(ToolRegistry.class);assertEquals(3,registry.specs().size());
        var policy=context.getBean(ToolAccessPolicy.class);assertThrows(SecurityException.class,()->policy.check(registry.definition("demo.ticket.create"),null,identity("bob","demo_react","key")));
        assertThrows(SecurityException.class,()->policy.check(registry.definition("demo.ticket.create"),null,identity("alice","core","key")));
    });}
    @Test void asyncJobsSurviveRepositoryRecreationAndAreOwnerIsolated(){runner.withPropertyValues("demo.enabled=true","demo.visitors=alice,bob","demo.delay-seconds=15").run(context->{
        assertNull(context.getStartupFailure());var tickets=context.getBean(DemoTickets.class);var args=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("title","模拟工单");var alice=identity("alice","demo_plan","stable-key");
        assertEquals("stable-key",tickets.create(args,alice,true).jobId());tickets.create(args,alice,true);
        assertEquals(1,context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM demo_ticket",Integer.class));
        assertThrows(SecurityException.class,()->tickets.poll("stable-key",identity("bob","demo_plan","stable-key")));
        var restored=new DemoTickets(context.getBean(JdbcTemplate.class),Clock.offset(context.getBean(Clock.class),Duration.ofSeconds(16)),context.getBean(DemoProperties.class));
        assertTrue(restored.recover(args,alice).result().ok());var assignment=args.deepCopy().removeAll().put("ticket_id","stable-key").put("version",1).put("assignee","张三");
        assertTrue(restored.assign(assignment,alice).ok());assertTrue(restored.assign(assignment,alice).ok());assertEquals(2,restored.assign(assignment,alice).data().path("version").asInt());
        assertFalse(restored.assign(assignment.deepCopy().put("assignee","FAIL"),alice).ok());
    });}
    static ToolContext identity(String visitor,String agent,String key){return new ToolContext("trace","conversation",ZoneOffset.UTC,Instant.parse("2026-11-13T00:00:00Z"),agent,visitor,Set.of("demo.ticket.create","demo.ticket.create_async","demo.ticket.assign"),key);}
}
