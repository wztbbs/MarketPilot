package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.cexpilot.business.cex.conversation.*;
import com.cexpilot.business.cex.metric.MetricCatalog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

/** 真实 H2/业务草稿/权限与通用运行；模型、行情和邮件均为本地替身。 */
class MonitorRunMigrationTest {
    MonitorTestFixture f; MonitorConversationFlow flow;ToolGateway gateway;RunCoordinator runs;
    DurableStore store;ConversationService conversations;AtomicInteger models=new AtomicInteger();
    @BeforeEach void setup(){
        f=new MonitorTestFixture();f.config.setEnabled(true);
        var loader=new DefaultResourceLoader();var catalog=new MetricCatalog(loader);var beans=new DefaultListableBeanFactory();
        var compiler=mock(MonitorDefinitionCompiler.class);var runner=mock(MonitorPlanRunner.class);var mail=mock(EmailNotificationSender.class);
        when(mail.isAvailable()).thenReturn(true);when(mail.isDryRun()).thenReturn(true);
        var definition=f.mapper.createObjectNode().put("schemaVersion",2).put("timezone","UTC").put("requiredHits",2).put("catalogVersion",catalog.version());
        definition.set("candidate",MonitorTestFixture.candidate(2));definition.putArray("rules").addObject().put("description","BTC跌2%");
        when(compiler.compile(any(),any())).thenAnswer(i->definition.deepCopy());
        flow=spy(new MonitorConversationFlow(f.jdbc,f.repository,compiler,runner,f.config,f.json,f.clock,mail));beans.registerSingleton("flow",flow);
        var old=com.cexpilot.business.cex.query.MetricTestSupport.registry();var executors=new ArrayList<AgentTool>();old.specs().forEach(s->executors.add(old.get(s.name())));
        executors.add(new MonitorDraftTool(beans.getBeanProvider(MonitorConversationFlow.class)));
        var names=executors.stream().map(AgentTool::name).collect(java.util.stream.Collectors.toSet());
        var tools=new ToolRegistry(executors,ToolDefinitionLoader.load(loader).stream().filter(d->names.contains(d.name())).toList());
        var validator=mock(MonitorPlanValidator.class);when(validator.validate(any())).thenReturn(new MonitorPlanValidator.Validated(MonitorTestFixture.candidate(2),null,Map.of()));
        var prompts=new PromptStore(loader);var hooks=new MonitorPlanHooks(prompts,catalog,tools,validator,new com.cexpilot.platform.engine.dag.DagConfig(),flow);
        var agent=new MonitorAgent(hooks,prompts,flow);var agents=new AgentRegistry(List.of(agent));beans.registerSingleton("agents",agents);
        store=new DurableStore(f.jdbc,f.mapper);gateway=new ToolGateway(tools,store,f.mapper,f.clock,List.of(new RegisteredAgentToolPolicy(beans.getBeanProvider(AgentRegistry.class)),new MonitorDraftAccessPolicy(store,catalog)),List.of());
        beans.registerSingleton("gateway",gateway);ReflectionTestUtils.setField(tools,"gateway",beans.getBeanProvider(ToolGateway.class));
        LlmClient model=(m,t)->{models.incrementAndGet();return new ChatResponse(MonitorTestFixture.candidate(2).toString(),List.of(),10,5);};
        var engine=new PlanEngine(tools,prompts,new LlmCallService(model,new LlmConfig(),prompts),null,null);
        conversations=new ConversationService(new ConversationRepository(f.jdbc),new com.cexpilot.business.cex.configuration.ConversationConfig());
        var committer=new ConversationRunCommitter(f.jdbc,conversations,new DataSourceTransactionManager(f.jdbc.getDataSource()),f.clock,f.mapper);
        runs=new RunCoordinator(store,agents,new RunAdapterRegistry(List.of(new MonitorRunAdapter(engine,committer,f.mapper,new QueryExecutionConfig()))),f.mapper,f.clock,e->{},List.of());
        f.jdbc.update("INSERT INTO conversation(conversation_id,visitor_id) VALUES ('c','alice')");
        f.jdbc.update("INSERT INTO conversation_state(conversation_id,last_query_no,running_turn_id,fencing_token,lease_until) VALUES ('c',1,'turn',7,?)",LocalDateTime.ofInstant(f.clock.instant().plusSeconds(180),ZoneOffset.UTC));
        f.jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,turn_id,turn_status) VALUES ('c',1,'监控BTC','turn','RUNNING')");
    }
    @AfterEach void close(){gateway.close();f.close();}
    ExecutionResult start(){
        var agent=newAgent();return runs.execute(agent,new AgentRunContext("监控BTC","","trace",e->{},null,new RequestContext(ZoneOffset.UTC,f.clock.instant()),null,new EvidenceSession("c","turn"),f.mapper.createObjectNode(),f.mapper.createObjectNode(),"alice",null));
    }
    ConversationAgent newAgent(){return org.springframework.test.util.ReflectionTestUtils.getField(runs,"agents") instanceof AgentRegistry r?r.require("monitor"):null;}
    @Test void draftGoesThroughGatewayAndOnlyExistingRuleConfirmationEnablesIt(){
        var result=start();assertEquals("PENDING_CONFIRMATION",result.agentPayload().path("status").asText());String id=result.agentPayload().path("runId").asText();
        assertEquals(1,store.calls(id).size());assertEquals("SUCCEEDED",store.calls(id).get(0).status());assertEquals(1,f.count("monitor_rule"));
        assertTrue(f.repository.due(f.clock.instant()).isEmpty());assertEquals(1,models.get());
        conversations.finishRoutedTurn("c",new ConversationService.TurnHandle(1,"turn",7),"监控BTC","SUCCEEDED",result.answer(),"trace",null,result.evidence(),null,f.clock.instant(),ZoneOffset.UTC,null,"monitor",result.agentPayload());
        flow.bindEmail("alice","alice@example.com");var card=result.agentPayload().path("confirmation");
        var active=flow.confirm("alice",card.path("ruleId").asText(),new MonitorModels.ConfirmRequest(card.path("version").asInt(),card.path("specHash").asText()),"alice@example.com");
        assertEquals("ACTIVE",active.path("status").asText());assertEquals(0,f.count("monitor_evaluation"));runs.drive(id);
        assertTrue(store.require(RUN,id).data().path("businessCommitted").asBoolean());assertEquals(1,f.count("monitor_rule_version"));
    }
    @Test void committedDraftWithLostResponseResumesWithoutReplanningOrDuplicateVersion(){
        var first=new AtomicBoolean(true);
        doAnswer(call->{var card=call.callRealMethod();if(first.getAndSet(false))throw new IllegalStateException("响应丢失");return card;})
                .when(flow).saveDraftForRun(any(),any(),any(),any(),any());
        var result=start();assertEquals("WAITING",result.turnStatus());String id=result.agentPayload().path("runId").asText();
        assertEquals("MONITOR_ACCEPTED",store.require(RUN,id).data().at("/checkpoint/phase").asText());assertEquals(1,f.count("monitor_rule_version"));
        f.clock.advance(181);gateway.process(store.calls(id).get(0).id());runs.drive(id);runs.drive(id);
        assertEquals("SUCCEEDED",store.require(RUN,id).status());assertEquals(1,models.get());assertEquals(1,f.count("monitor_rule_version"));
        assertEquals("SUCCEEDED",f.jdbc.queryForObject("SELECT turn_status FROM conversation_query WHERE turn_id='turn'",String.class));
        assertEquals("monitor",conversations.routingState("c").path("active_agent").asText());assertNull(f.jdbc.queryForObject("SELECT running_turn_id FROM conversation_state WHERE conversation_id='c'",String.class));
    }
}
