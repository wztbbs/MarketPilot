package com.cexpilot.business.cex.query;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.cexpilot.business.cex.configuration.ConversationConfig;
import com.cexpilot.business.cex.conversation.*;
import com.cexpilot.business.cex.evidence.*;
import com.cexpilot.business.cex.metric.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.cexpilot.business.cex.query.MetricTestSupport.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真 SQL、Query 专用规划器及 DAG；替身模型与异步计算不访问网络。 */
class QueryRunMigrationTest {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-12T01:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final MutableClock clock=new MutableClock();final AtomicInteger models=new AtomicInteger(),fetches=new AtomicInteger(),submits=new AtomicInteger();
    final AtomicBoolean crashBeforePlanCheckpoint=new AtomicBoolean(),crashAfterFactInsert=new AtomicBoolean();
    java.math.BigDecimal preciseScalar;
    final List<com.cexpilot.platform.observability.TraceEvent> events=Collections.synchronizedList(new ArrayList<>());
    JdbcTemplate jdbc;ConversationService conversations;ConversationService.TurnHandle turn;
    ObjectNode logical;boolean adaptive;boolean asynchronous;volatile boolean ready;
    final List<Fixture> fixtures=new ArrayList<>();
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:query_run_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO conversation(conversation_id,visitor_id) VALUES ('c','alice')");
        conversations=new ConversationService(new ConversationRepository(jdbc),new ConversationConfig());
        turn=new ConversationService.TurnHandle(1,"turn",7);
        jdbc.update("INSERT INTO conversation_state(conversation_id,last_query_no,running_turn_id,fencing_token,lease_until) VALUES ('c',1,'turn',7,?)",LocalDateTime.ofInstant(clock.instant().plusSeconds(180),ZoneOffset.UTC));
        jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,turn_id,turn_status) VALUES ('c',1,'查询成交额均值','turn','RUNNING')");logical=scalarPlan();
    }
    @AfterEach void close(){fixtures.forEach(Fixture::close);assertNull(RunScope.current());assertNull(QueryBudget.current());}
    final class Fixture implements AutoCloseable {
        final DurableStore store=new DurableStore(jdbc,mapper){
            @Override public void checkpoint(String runId,long fence,String key,ObjectNode value,Instant now){
                if("query:accepted-plan".equals(key) && crashBeforePlanCheckpoint.getAndSet(false))throw new AssertionError("模拟进程中止：模型响应已落库，计划检查点尚未写入");
                super.checkpoint(runId,fence,key,value,now);
            }
        };final ToolGateway gateway;final RunCoordinator runs;final MarketAnalysisAgent agent;
        final DagExecutor executor;final QueryRunCodec codec;
        final class AsyncAverage extends com.cexpilot.platform.tool.calculation.AvgTool implements AsyncAgentTool {
                public String name(){return "avg";}
                public ToolExecution submit(JsonNode args,ToolContext context){if(preciseScalar!=null)assertEquals(0,args.at("/input/values/0").decimalValue().compareTo(preciseScalar));submits.incrementAndGet();return ToolExecution.accepted("job-"+context.invocationId(),100);}
                public ToolExecution poll(String id,ToolContext context){return ready?ToolExecution.completed(ToolResult.success(json("{\"value\":\"100\",\"count\":1}"))):ToolExecution.accepted(id,100);}
                public ToolExecution recover(JsonNode args,ToolContext context){return poll("job-"+context.invocationId(),context);}
            }
        Fixture(){
            var tools=asynchronous?registry(new AsyncAverage()):registry();var dag=new DagConfig();var policy=new QueryExecutionConfig();
            var providers=providers(provider((q,c)->{fetches.incrementAndGet();com.cexpilot.platform.observability.HttpRequestCounter.increment();var result=metricResult(q,c,24,true);if(preciseScalar!=null && result instanceof MetricResult.Scalar scalar)return new MetricResult.Scalar(scalar.metadata(),preciseScalar,scalar.observationSeconds(),scalar.actualRange());return result;}));
            executor=new DagExecutor(tools,dag,List.of(new MetricNodeExecutionSupport(providers)));
            var prompts=new PromptStore(LOADER);
            LlmClient model=(messages,specs)->{
                models.incrementAndGet();var system=messages.get(0).content();String answer;
                if(system.equals(prompts.render("query_continue",Map.of("catalog",plannerCatalog(tools))))) {
                    var request=json(messages.get(1).content());
                    if(request.path("plan").path("calculations").isEmpty()) {
                        var delta=plan();calculation(delta,"a","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
                        var out=mapper.createObjectNode().put("action","continue").put("reason","补充均值");out.set("plan",delta);answer=out.toString();
                    }else answer="{\"action\":\"finish\",\"reason\":\"完成\",\"completion\":\"complete\"}";
                } else if(messages.get(1).content().contains("<FACTS>"))answer="均值为100";
                else {var output=(ObjectNode)json(envelope(logical));if(adaptive)output.put("execution_mode","adaptive");answer=output.toString();}
                return new ChatResponse(answer,List.of(),10,5);
            };
            codec=new QueryRunCodec(mapper,providers);
            var runtime=new DagRuntime(model,planner(model,tools,dag),executor,prompts,clock,new EvidenceStore(new EvidenceRepository(jdbc){
                @Override public void insert(EvidenceFact fact){super.insert(fact);if(crashAfterFactInsert.getAndSet(false))throw new AssertionError("模拟事实写入后进程中止");}
            }),policy);runtime.durableCodec(codec);
            agent=new MarketAnalysisAgent(runtime);var agents=new AgentRegistry(List.of(agent));var beans=new DefaultListableBeanFactory();beans.registerSingleton("agents",agents);
            gateway=new ToolGateway(tools,store,mapper,clock,List.of(new RegisteredAgentToolPolicy(beans.getBeanProvider(AgentRegistry.class))),List.of());
            beans.registerSingleton("gateway",gateway);ReflectionTestUtils.setField(tools,"gateway",beans.getBeanProvider(ToolGateway.class));
            var engine=new PlanEngine(tools,prompts,new LlmCallService(model,new LlmConfig(),prompts),null,executor);
            var committer=new ConversationRunCommitter(jdbc,conversations,new DataSourceTransactionManager(jdbc.getDataSource()),clock,mapper);
            runs=new RunCoordinator(store,agents,new RunAdapterRegistry(List.of(new QueryRunAdapter(engine,committer,codec,policy))),mapper,clock,events::add,List.of());fixtures.add(this);
        }
        ExecutionResult start(List<String> deltas){return runs.execute(agent,new AgentRunContext("查询成交额均值","历史","trace",e->{},deltas==null?null:deltas::add,
                new RequestContext(ZoneId.of("Asia/Shanghai"),clock.instant()),null,new EvidenceSession("c",turn.turnId()),mapper.createObjectNode(),mapper.createObjectNode(),"alice",
                new MarketAnalysisAgent.QueryInput(new PlanningContext("",null),p->{throw new AssertionError("回调不能进入输入快照");},null)));}
        public void close(){gateway.close();executor.close();}
    }
    private String plannerCatalog(ToolRegistry tools){var catalog=new MetricCatalog(LOADER);return catalog.concepts()+"\n"+catalog.describeMetrics()+"\n"+catalog.describeOperators(tools);}
    void finishInitial(ExecutionResult result){conversations.finishRoutedTurn("c",turn,"查询成交额均值",result.turnStatus()==null?"SUCCEEDED":result.turnStatus(),result.answer(),"trace",QueryOutput.plan(result),result.evidence(),QueryOutput.scope(result),clock.instant(),ZoneOffset.UTC,result.clarify(),"query",result.agentPayload());}
    void calc(){calculation(logical,"a","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void synchronousRunPreservesStreamingAndTypedTaskResult(){
        var fixture=new Fixture();var deltas=new ArrayList<String>();var result=fixture.start(deltas);assertEquals("均值为100",result.answer());assertEquals(List.of("均值为100"),deltas);
        assertNotNull(QueryOutput.plan(result));assertEquals(1,result.metricFetchCount());assertEquals(1,result.httpRequestCount());assertEquals(0,count("conversation_task"));finishInitial(result);
        var id=result.agentPayload().path("runId").asText();fixture.runs.drive(id);fixture.runs.drive(id);
        assertEquals(1,count("conversation_task_version"));assertEquals(2,models.get());assertEquals(1,fetches.get());
    }
    @Test void asyncCalculationResumesAfterReassemblyAndCommitsExactlyOneTaskVersion(){
        asynchronous=true;calc();var first=new Fixture();var initial=first.start(null);assertEquals("WAITING",initial.turnStatus());finishInitial(initial);assertEquals(0,count("conversation_task"));
        String id=initial.agentPayload().path("runId").asText();assertEquals(1,models.get());assertEquals(1,fetches.get());first.close();ready=true;clock.now=clock.now.plusSeconds(3600);
        var resumed=new Fixture();resumed.gateway.process(resumed.store.calls(id).get(0).id());resumed.runs.drive(id);resumed.runs.drive(id);
        var run=resumed.store.require(RUN,id);assertEquals("SUCCEEDED",run.status(),run.data().toString());assertTrue(run.data().path("businessCommitted").asBoolean());
        assertEquals(2,models.get());assertEquals(1,fetches.get());assertEquals(1,submits.get());assertEquals(2,run.data().at("/budget/tool_nodes").asInt());assertEquals(1,count("conversation_task_version"));assertEquals(2,count("conversation_artifact"));
        var task=new ConversationRepository(jdbc).findActiveTaskVersion("c");assertNotNull(task);assertEquals(1,task.version());assertEquals(1,task.queryDefinition().logicalPlan().path("calculations").size());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT turn_status FROM conversation_query WHERE turn_id=?",String.class,turn.turnId()));
        // 迟到的原 HTTP WAITING 回写不能覆盖恢复后的业务终态。
        finishInitial(initial);assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT turn_status FROM conversation_query WHERE turn_id=?",String.class,turn.turnId()));assertEquals(1,count("conversation_task_version"));
    }
    @Test void adaptiveResumeKeepsAcceptedContinuationAndEarlierFacts(){
        asynchronous=true;adaptive=true;var first=new Fixture();var initial=first.start(null);assertEquals("WAITING",initial.turnStatus());finishInitial(initial);
        String id=initial.agentPayload().path("runId").asText();assertEquals(2,models.get());assertEquals(1,count("conversation_artifact"));
        assertEquals("EXECUTE",first.store.require(RUN,id).data().at("/checkpoint/state/stage").asText());
        first.close();ready=true;clock.now=clock.now.plusSeconds(3600);var resumed=new Fixture();resumed.gateway.process(resumed.store.calls(id).get(0).id());resumed.runs.drive(id);resumed.runs.drive(id);
        assertEquals("SUCCEEDED",resumed.store.require(RUN,id).status());assertEquals(4,models.get());assertEquals(1,fetches.get());assertEquals(1,count("conversation_task_version"));assertEquals(2,count("conversation_artifact"));
    }
    @Test void olderCompletionOnlyUpdatesHistoryAndPreservesNewerAgentFocus(){
        asynchronous=true;calc();var first=new Fixture();var initial=first.start(null);finishInitial(initial);String id=initial.agentPayload().path("runId").asText();
        var next=new ConversationService.TurnHandle(2,"next-turn",8);
        jdbc.update("INSERT INTO conversation_query(conversation_id,query_no,question,turn_id,turn_status) VALUES ('c',2,'再讨论监控','next-turn','RUNNING')");
        jdbc.update("UPDATE conversation_state SET last_query_no=2,running_turn_id='next-turn',fencing_token=8,lease_until=? WHERE conversation_id='c'",LocalDateTime.ofInstant(clock.instant().plusSeconds(180),ZoneOffset.UTC));
        conversations.finishRoutedTurn("c",next,"再讨论监控","SUCCEEDED","待补充","t2",null,mapper.createArrayNode(),null,clock.instant(),ZoneOffset.UTC,null,"monitor",mapper.createObjectNode().put("status","NEEDS_CLARIFICATION"));
        ready=true;clock.now=clock.now.plusSeconds(3600);var resumed=new Fixture();resumed.gateway.process(resumed.store.calls(id).get(0).id());resumed.runs.drive(id);resumed.runs.drive(id);
        assertEquals("SUCCEEDED",resumed.store.require(RUN,id).status());assertEquals("monitor",conversations.routingState("c").path("active_agent").asText());assertEquals(0,count("conversation_task_version"));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT turn_status FROM conversation_query WHERE turn_id=?",String.class,turn.turnId()));
    }
    @Test void interruptedAfterModelResponseReplaysWithoutAnotherPlannerCall(){
        crashBeforePlanCheckpoint.set(true);var first=new Fixture();
        assertThrows(AssertionError.class,()->first.start(null));assertEquals(1,models.get());assertEquals(0,fetches.get());
        String id=jdbc.queryForObject("SELECT id FROM platform_run",String.class);first.close();clock.now=clock.now.plusSeconds(3600);
        var resumed=new Fixture();resumed.runs.drive(id);resumed.runs.drive(id);
        assertEquals("SUCCEEDED",resumed.store.require(RUN,id).status());assertEquals(2,models.get());assertEquals(1,fetches.get());assertEquals(1,count("conversation_task_version"));
        assertEquals(2,resumed.store.require(RUN,id).data().at("/budget/model_calls").asInt());
        assertEquals(2,events.stream().filter(e->"LLM_CALL".equals(e.eventType())).count(),"重放不能伪造第二次实际模型调用");
    }
    @Test void metricDagAndPrivateHistoryRoundTripKeepPhysicalBindingsAndScopes(){
        var funding=metric("fund","funding.rate_settled","recent_n","binance");funding.remove(List.of("time","interval"));funding.put("count",3);
        var snapshot=metric("snap","price.last","snapshot","binance");snapshot.remove(List.of("time","interval"));
        var input=plan(metric("range","trade.turnover","range_statistic","binance"),funding,snapshot);
        var tools=registry();var planner=planner(new Script(envelope(input)),tools,new DagConfig());
        var outcome=planner.plan("查询成交额、资金费率和价格","","codec",e->{});assertTrue(outcome.plan().isPresent());
        var codec=new QueryRunCodec(mapper,catalogProviders());var copy=codec.read(codec.write(outcome),DagPlanner.PlanOutcome.class);
        assertEquals(outcome.plan().get().dagPlan().toJson(),copy.plan().get().dagPlan().toJson());
        assertTrue(copy.plan().get().dagPlan().nodes().stream().anyMatch(n->n.operation() instanceof CountQuery));
        assertTrue(copy.plan().get().dagPlan().nodes().stream().anyMatch(n->n.operation() instanceof SnapshotQuery));
        var base=ContextFixtures.executed("task","c",2,"source","原任务",clock.instant(),ZoneOffset.UTC,outcome.plan().get(),mapper.createArrayNode(),"SUCCEEDED");
        var context=new PlanningContext("上下文",base,List.of(),3,List.of(new TaskVersionCandidate("candidate_1",base)),true,"独立问题");
        var history=codec.read(codec.write(new QueryRunAdapter.Input(context,"独立问题",turn)),QueryRunAdapter.Input.class);
        assertEquals(context,history.plannerInput());
        var output=new QueryOutput(new TaskScope(TaskScope.Action.RESUME,base),outcome.plan().get());
        var restored=codec.read(codec.write(output),QueryOutput.class);assertEquals(output.taskScope(),restored.taskScope());assertEquals(output.compiledPlan().dagPlan().toJson(),restored.compiledPlan().dagPlan().toJson());
    }

    @Test void frozenDecimalArgumentsKeepPrecisionAcrossWaitAndReassembly(){
        preciseScalar=new java.math.BigDecimal("0.1234567890123456789012345678901234");asynchronous=true;calc();var first=new Fixture();
        var initial=first.start(null);assertEquals("WAITING",initial.turnStatus());finishInitial(initial);String id=initial.agentPayload().path("runId").asText();
        assertEquals(0,first.store.calls(id).get(0).data().at("/arguments/input/values/0").decimalValue().compareTo(preciseScalar));
        ready=true;clock.now=clock.now.plusSeconds(3600);var resumed=new Fixture();resumed.gateway.process(resumed.store.calls(id).get(0).id());resumed.runs.drive(id);resumed.runs.drive(id);
        assertEquals("SUCCEEDED",resumed.store.require(RUN,id).status());assertEquals(1,submits.get());assertEquals(1,fetches.get());
        var fact=new EvidenceRepository(jdbc).listReusable("c",clock.instant()).stream().filter(f->!f.derived()).findFirst().orElseThrow();
        assertEquals(0,fact.payload().path("value").decimalValue().compareTo(preciseScalar));
    }
    @Test void factCommittedBeforeProcessExitDoesNotCreateDuplicateEvidence(){
        crashAfterFactInsert.set(true);var first=new Fixture();assertThrows(AssertionError.class,()->first.start(null));
        assertEquals(1,count("conversation_artifact"));String id=jdbc.queryForObject("SELECT id FROM platform_run",String.class);clock.now=clock.now.plusSeconds(3600);
        var resumed=new Fixture();resumed.runs.drive(id);resumed.runs.drive(id);
        assertEquals("SUCCEEDED",resumed.store.require(RUN,id).status());assertEquals(1,count("conversation_artifact"));assertEquals(1,fetches.get());assertEquals(2,models.get());assertEquals(1,count("conversation_task_version"));
    }

}
