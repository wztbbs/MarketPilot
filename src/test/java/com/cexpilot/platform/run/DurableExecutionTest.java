package com.cexpilot.platform.run;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.engine.react.ReactEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

/** 全链路只用 H2、脚本模型和内存外部任务，不请求线上或真实 LLM。 */
class DurableExecutionTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-11-12T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    static final class ExternalJob implements AsyncAgentTool {
        AtomicInteger submits=new AtomicInteger(),polls=new AtomicInteger(),recovers=new AtomicInteger(); volatile boolean done;
        String invocationId;
        public String name(){return "sample.write";}
        public ToolExecution submit(JsonNode args,ToolContext context){submits.incrementAndGet();invocationId=context.invocationId();return ToolExecution.accepted("job-1",1000);}
        public ToolExecution poll(String job,ToolContext context){polls.incrementAndGet();assertEquals("job-1",job);assertEquals(invocationId,context.invocationId());return done?ToolExecution.completed(ToolResult.success(JSON.createObjectNode().put("value",3))):ToolExecution.accepted(job,1000);}
        public ToolExecution recover(JsonNode args,ToolContext context){recovers.incrementAndGet();return ToolExecution.accepted("job-1",1000);}
    }
    static final class Fixture implements AutoCloseable {
        final MutableClock clock=new MutableClock(); final JdbcTemplate jdbc;
        final DurableStore store; final ToolRegistry tools; final ToolGateway gateway;
        final DagExecutor dag; final DeclaredAgent agent; final AgentRegistry agents;
        final ReactEngine react; final PlanEngine plan; final Queue<String> responses=new ArrayDeque<>();final AtomicInteger models=new AtomicInteger();
        RunCoordinator runs;
        Fixture(EngineKind engine,AgentTool tool,ToolControl control,List<ToolAccessPolicy> policies,List<ToolResourceGuard> guards) throws Exception {
            var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);jdbc=new JdbcTemplate(source);
            store=new DurableStore(jdbc,JSON);
            var definition=new ToolDefinition(tool.name(),true,"测试操作",JSON.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"version\":{\"type\":\"integer\"}}}"),
                    JSON.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"number\"}}}"),null,control);
            tools=new ToolRegistry(List.of(tool),List.of(definition));gateway=new ToolGateway(tools,store,JSON,clock,policies,guards);
            var factory=new DefaultListableBeanFactory();factory.registerSingleton("gateway",gateway);ReflectionTestUtils.setField(tools,"gateway",factory.getBeanProvider(ToolGateway.class));
            dag=new DagExecutor(tools,new DagConfig());var prompts=new PromptStore(new DefaultResourceLoader());
            LlmClient client=(messages,specs)->{models.incrementAndGet();return new ChatResponse(responses.remove(),List.of(),10,5);};
            var llm=new LlmCallService(client,new LlmConfig(),prompts);
            agent=new DeclaredAgent(new ConfiguredAgentDefinition(new AgentDefinition("sample","sample","示例"),true,engine,"plan_agent_answer",Set.of(tool.name()),5,new com.cexpilot.platform.execution.config.QueryExecutionConfig()),tools,prompts);
            agents=new AgentRegistry(List.of(agent));react=new ReactEngine(tools,prompts,llm,null);plan=new PlanEngine(tools,prompts,llm,null,dag);
            restart();
            if(engine==EngineKind.REACT){responses.add("{\"action\":\"call\",\"tool\":\"sample.write\",\"arguments\":{\"version\":1}}");responses.add("{\"action\":\"finish\",\"evidence_refs\":[\"e1\"]}");responses.add("{\"answer\":\"操作完成\",\"evidence_refs\":[\"e1\"]}");}
            else {responses.add("{\"nodes\":[{\"id\":\"n1\",\"tool\":\"sample.write\",\"args\":{\"version\":1}}]}");responses.add("{\"answer\":\"操作完成\",\"evidence_refs\":[\"n1\"]}");}
        }
        void restart(){runs=new RunCoordinator(new DurableStore(jdbc,JSON),agents,new RunAdapterRegistry(List.of(new DeclaredAgentRunAdapter(react,plan))),JSON,clock,e->{},List.of());}
        ExecutionResult start(){return runs.execute(agent,new AgentRunContext("执行操作","","trace",e->{},null,new RequestContext(ZoneOffset.UTC,clock.instant()),null,null,null,JSON.createObjectNode(),"alice",null));}
        DurableStore.Entry call(String id){return store.calls(id).get(0);}
        public void close(){dag.close();gateway.close();}
    }
    static ToolControl confirmation(){return new ToolControl(ToolControl.Kind.OPERATION,true,"创建测试对象",List.of(),true,86400);}
    @ParameterizedTest @EnumSource(value=EngineKind.class,names={"REACT","PLAN"})
    void confirmationAsyncPollingAndRestartResumeOriginalDecision(EngineKind kind) throws Exception {
        var external=new ExternalJob();
        try(var f=new Fixture(kind,external,confirmation(),List.of(),List.of())) {
            var initial=f.start();assertEquals("WAITING",initial.turnStatus());String runId=initial.agentPayload().path("runId").asText();
            var call=f.call(runId);assertEquals("CONFIRMATION_REQUIRED",call.status());assertEquals(0,external.submits.get());assertEquals(1,f.models.get());
            String fingerprint=call.data().path("fingerprint").asText();
            assertThrows(NoSuchElementException.class,()->f.gateway.confirm(call.id(),"bob",fingerprint,true));
            assertThrows(IllegalArgumentException.class,()->f.gateway.confirm(call.id(),"alice","tampered",true));
            f.gateway.confirm(call.id(),"alice",fingerprint,true);f.gateway.confirm(call.id(),"alice",fingerprint,true);
            f.gateway.process(call.id());assertEquals(1,external.submits.get());assertEquals("WAITING",f.call(runId).status());
            f.restart();f.runs.drive(runId);assertEquals(1,f.models.get());
            f.clock.advance(2);f.gateway.process(call.id());assertEquals(1,external.polls.get());assertEquals(1,external.submits.get());
            external.done=true;f.clock.advance(3600);f.gateway.signal(call.id(),"job-1");f.gateway.process(call.id());
            // 即使没有 callback，due 扫描也能找到完成未恢复的运行。
            assertTrue(f.store.due(RUN,f.clock.instant(),32).contains(runId));
            f.runs.drive(runId);var completed=f.runs.view(runId,"alice");
            assertEquals("SUCCEEDED",completed.path("status").asText(),completed.toPrettyString());
            assertEquals("操作完成",completed.at("/result/answer").asText());assertEquals(1,external.submits.get());
            assertEquals(kind==EngineKind.REACT?3:2,f.models.get());assertEquals(1,f.store.require(RUN,runId).data().at("/budget/tool_nodes").asInt());
            assertEquals(f.models.get(),f.store.require(RUN,runId).data().at("/budget/model_calls").asInt());
            f.gateway.signal(call.id(),"job-1");f.runs.drive(runId);assertEquals(1,external.submits.get());
        }
    }
    @Test void changedResourceAndRevokedPermissionCannotBeApproved() throws Exception {
        var version=new AtomicInteger(1);var allowed=new AtomicBoolean(true);var external=new ExternalJob();
        ToolAccessPolicy policy=(tool,args,ctx)->{if(!allowed.get())throw new SecurityException("撤销");};
        ToolResourceGuard guard=new ToolResourceGuard(){public boolean supports(String tool){return true;}public Map<String,JsonNode> currentVersions(String tool,JsonNode args,ToolContext context){return Map.of("/version",JSON.valueToTree(version.get()));}};
        var control=new ToolControl(ToolControl.Kind.OPERATION,true,"按目标版本更新",List.of("/version"),true,86400);
        try(var f=new Fixture(EngineKind.REACT,external,control,List.of(policy),List.of(guard))) {
            var output=f.start();var call=f.call(output.agentPayload().path("runId").asText());String fingerprint=call.data().path("fingerprint").asText();
            version.set(2);assertThrows(SecurityException.class,()->f.gateway.confirm(call.id(),"alice",fingerprint,true));
            version.set(1);allowed.set(false);assertThrows(SecurityException.class,()->f.gateway.confirm(call.id(),"alice",fingerprint,true));
            assertEquals(0,external.submits.get());
        }
    }
    @Test void rejectedAndExpiredConfirmationsNeverExecute() throws Exception {
        var external=new ExternalJob();try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            f.gateway.confirm(call.id(),"alice",call.data().path("fingerprint").asText(),false);f.gateway.process(call.id());
            assertEquals("REJECTED",f.call(id).status());assertEquals(0,external.submits.get());
        }
        external=new ExternalJob();try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);f.clock.advance(86401);
            f.gateway.process(call.id());assertEquals("EXPIRED",f.call(id).status());assertEquals(0,external.submits.get());
        }
    }
    @Test void nonIdempotentUnknownSubmissionIsNotBlindlyRepeated() throws Exception {
        var executions=new AtomicInteger();AgentTool tool=new AgentTool(){public String name(){return "sample.write";}public ToolResult execute(JsonNode args,ToolContext ctx){executions.incrementAndGet();throw new IllegalStateException("响应丢失");}};
        var control=new ToolControl(ToolControl.Kind.OPERATION,false,"不可重试的操作",List.of(),false,86400);
        try(var f=new Fixture(EngineKind.REACT,tool,control,List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            assertEquals("UNKNOWN",call.status());f.clock.advance(61);f.gateway.process(call.id());assertEquals(1,executions.get());assertEquals("UNKNOWN",f.call(id).status());
        }
    }
    @Test void expiredLeaseIsFencedAndAbandonedAcceptedSubmissionRecoversByInvocationId() throws Exception {
        var external=new ExternalJob();try(var f=new Fixture(EngineKind.PLAN,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            f.gateway.confirm(call.id(),"alice",call.data().path("fingerprint").asText(),true);
            call=f.call(id);var data=call.data();assertTrue(f.store.update(CALL,call,"EXECUTING",f.clock.instant(),f.clock.instant().plusSeconds(60),data));
            var stale=f.store.claim(RUN,id,f.clock.instant(),f.clock.instant().plusSeconds(60));assertNotNull(stale);
            f.clock.advance(61);f.gateway.process(call.id());assertEquals(0,external.submits.get());assertEquals(1,external.recovers.get());
            assertTrue(f.store.due(RUN,f.clock.instant(),32).contains(id));var newer=f.store.claim(RUN,id,f.clock.instant(),f.clock.instant().plusSeconds(60));
            assertNotNull(newer);assertFalse(f.store.update(RUN,stale,"FAILED",null,null,stale.data()));
        }
    }
    @Test void permissionRevokedAfterApprovalIsCheckedAgainBeforeSubmission() throws Exception {
        var allowed=new AtomicBoolean(true);var external=new ExternalJob();
        try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of((definition,args,context)->{
            if(!allowed.get())throw new SecurityException("已撤销");
        }),List.of())) {
            var output=f.start();var call=f.call(output.agentPayload().path("runId").asText());
            f.gateway.confirm(call.id(),"alice",call.data().path("fingerprint").asText(),true);allowed.set(false);
            f.gateway.process(call.id());assertEquals("FAILED",f.store.require(CALL,call.id()).status());assertEquals(0,external.submits.get());
        }
    }
    @Test void dueScannerCompletesWithoutCallbackAndPublishesAfterInitialTurnIsCommitted() throws Exception {
        var external=new ExternalJob();try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            f.gateway.confirm(call.id(),"alice",call.data().path("fingerprint").asText(),true);f.gateway.process(call.id());
            external.done=true;f.clock.advance(2);
            var published=new AtomicInteger();
            f.runs=new RunCoordinator(f.store,f.agents,new RunAdapterRegistry(List.of(new DeclaredAgentRunAdapter(f.react,f.plan))),JSON,f.clock,e->{},List.of((run,result)->published.incrementAndGet()>1));
            try(var scanner=new RunReconciler(f.store,f.gateway,f.runs,f.clock)) {
                scanner.scan();await(()->f.store.require(CALL,call.id()).status().equals("SUCCEEDED"));
                scanner.scan();await(()->f.store.require(RUN,id).status().equals("SUCCEEDED"));
                await(()->{scanner.scan();return published.get()==1 && f.store.require(RUN,id).leaseUntil()==null;});
                assertFalse(f.store.require(RUN,id).data().path("completionPublished").asBoolean());
                f.clock.advance(16);await(()->{scanner.scan();return f.store.require(RUN,id).data().path("completionPublished").asBoolean();});
            }
        }
    }
    @Test void changedAgentVersionStopsResumeAndFrozenParameterTamperingFailsClosed() throws Exception {
        var external=new ExternalJob();try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            var tampered=call.data();((com.fasterxml.jackson.databind.node.ObjectNode)tampered.get("arguments")).put("version",9);
            assertTrue(f.store.update(CALL,call,call.status(),call.nextAttempt(),null,tampered));
            assertThrows(SecurityException.class,()->f.gateway.confirm(call.id(),"alice",call.data().path("fingerprint").asText(),true));
            var run=f.store.require(RUN,id);var data=run.data().put("agentVersion","incompatible");
            assertTrue(f.store.update(RUN,run,run.status(),f.clock.instant(),null,data));f.runs.drive(id);
            assertEquals("FAILED",f.store.require(RUN,id).status());assertEquals(0,external.submits.get());
        }
    }
    @Test void browserApiUsesCookieIdentityAndExactFrozenProposal() throws Exception {
        var external=new ExternalJob();try(var f=new Fixture(EngineKind.REACT,external,confirmation(),List.of(),List.of())) {
            var output=f.start();String id=output.agentPayload().path("runId").asText();var call=f.call(id);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new com.cexpilot.business.cex.api.AgentRunController(f.runs,f.gateway)).build();
            var alice=new jakarta.servlet.http.Cookie("cexpilot_uid","alice");var bob=new jakarta.servlet.http.Cookie("cexpilot_uid","bob");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/agent-runs/"+id).cookie(bob))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/agent-runs/"+id).cookie(alice))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
            String path="/api/agent-runs/"+id+"/calls/"+call.id()+"/decision";
            var decision=JSON.createObjectNode().put("fingerprint",call.data().path("fingerprint").asText()).put("approve",true).put("visitor","bob");
            decision.putObject("arguments").put("version",99);String body=decision.toString();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(alice).contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(bob).header("X-Agent-Action","1").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(alice).header("X-Agent-Action","1").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertEquals("READY",f.store.require(CALL,call.id()).status());
            assertEquals(1,f.store.require(CALL,call.id()).data().at("/arguments/version").asInt());
            assertEquals("alice",f.store.require(CALL,call.id()).data().path("decidedBy").asText());
            assertEquals(0,external.submits.get());
        }
    }
    @Test void completionProjectionRetriesInitialCommitAndDoesNotOverwriteLaterTurn() throws Exception {
        try(var f=new Fixture(EngineKind.PLAN,new ExternalJob(),confirmation(),List.of(),List.of())) {
            f.jdbc.update("INSERT INTO conversation(conversation_id,visitor_id) VALUES ('conv','alice')");
            f.jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,turn_id,turn_status) VALUES ('conv',1,'原问题','turn','RUNNING')");
            f.jdbc.update("INSERT INTO ask_trace(trace_id,question,status,prompt_tokens,completion_tokens) VALUES ('trace','原问题','WAITING',5,2)");
            var data=JSON.createObjectNode().put("traceId","trace");data.putObject("session").put("turnId","turn").put("conversationId","conv");
            var record=new DurableStore.Entry("run","alice",null,"SUCCEEDED",0,0,null,null,data);
            var result=new ExecutionResult("完成答案",JSON.createArrayNode(),1,1,10,3).withAgent("sample",JSON.createObjectNode().put("runId","run"));
            var listener=new com.cexpilot.business.cex.conversation.AgentRunCompletion(f.jdbc,new DataSourceTransactionManager(f.jdbc.getDataSource()));
            assertFalse(listener.completed(record,result));
            f.jdbc.update("UPDATE conversation_query SET turn_status='WAITING',trace_id='trace' WHERE turn_id='turn'");
            f.jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,answer,turn_id,turn_status) VALUES ('conv',2,'后来问题','后来答案','later','SUCCEEDED')");
            assertTrue(listener.completed(record,result));assertTrue(listener.completed(record,result));
            assertEquals("完成答案",f.jdbc.queryForObject("SELECT answer FROM conversation_query WHERE turn_id='turn'",String.class));
            assertEquals("SUCCEEDED",f.jdbc.queryForObject("SELECT turn_status FROM conversation_query WHERE turn_id='turn'",String.class));
            assertEquals("后来答案",f.jdbc.queryForObject("SELECT answer FROM conversation_query WHERE turn_id='later'",String.class));
            assertEquals(15,f.jdbc.queryForObject("SELECT prompt_tokens FROM ask_trace WHERE trace_id='trace'",Integer.class));
        }
    }
    @Test void dagSubmissionTimeoutSuspendsInsteadOfReportingACompletedFailure() throws Exception {
        var submissions=new AtomicInteger();var recoveries=new AtomicInteger();
        AsyncAgentTool slow=new AsyncAgentTool(){
            public String name(){return "sample.write";}
            public ToolExecution submit(JsonNode args,ToolContext context){
                submissions.incrementAndGet();
                try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("受理响应丢失");}
                return ToolExecution.accepted("job-slow",1000);
            }
            public ToolExecution recover(JsonNode args,ToolContext context){recoveries.incrementAndGet();return ToolExecution.accepted("job-slow",1000);}
            public ToolExecution poll(String job,ToolContext context){return ToolExecution.completed(ToolResult.success(JSON.createObjectNode().put("value",3)));}
        };
        var control=new ToolControl(ToolControl.Kind.OPERATION,false,"长动作",List.of(),true,86400);
        try(var f=new Fixture(EngineKind.PLAN,slow,control,List.of(),List.of())) {
            ((DagConfig)ReflectionTestUtils.getField(f.dag,"config")).setNodeTimeoutMs(100);
            var output=f.start();assertEquals("WAITING",output.turnStatus());String id=output.agentPayload().path("runId").asText();
            await(()->!f.store.calls(id).isEmpty() && f.call(id).status().equals("UNKNOWN"));var call=f.call(id);
            assertEquals(1,submissions.get());f.clock.advance(61);f.gateway.process(call.id());assertEquals(1,recoveries.get());
            f.clock.advance(2);f.gateway.process(call.id());f.runs.drive(id);
            assertEquals("SUCCEEDED",f.store.require(RUN,id).status());assertEquals(1,submissions.get());assertEquals(2,f.models.get());
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"本地调度器未在限时内完成");
    }
}
