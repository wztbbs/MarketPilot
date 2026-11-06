package com.cexpilot.dag;

import com.cexpilot.config.*;
import com.cexpilot.conversation.*;
import com.cexpilot.evidence.*;
import com.cexpilot.llm.*;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 脚本模型 + 真实编译/校验/算子/证据存储；不访问交易所或收费模型。 */
class QueryIterationTest {
    private static final RequestContext REQUEST=new RequestContext(ZoneId.of("Asia/Shanghai"),Instant.parse("2026-11-06T00:00:00Z"));
    private final QueryExecutionConfig policy=new QueryExecutionConfig();
    private final List<DagExecutor> executors=new ArrayList<>();
    private final InMemoryEvidenceRepository facts=new InMemoryEvidenceRepository();
    private final AtomicReference<CompiledPlan> committed=new AtomicReference<>();
    private final List<TraceEvent> events=new ArrayList<>();
    @AfterEach void close(){executors.forEach(DagExecutor::close);assertNull(QueryBudget.current());}
    private DagRuntime runtime(LlmClient script,MetricProvider provider) {
        var dag=new DagConfig();var tools=registry();var executor=new DagExecutor(tools,dag,providers(provider));executors.add(executor);
        return new DagRuntime(script,planner(script,tools,dag),executor,new PromptStore(LOADER),Clock.fixed(REQUEST.requestTime(),REQUEST.userZone()),new EvidenceStore(facts),policy);
    }
    private ExecutionResult run(DagRuntime runtime,String question,List<String> deltas) {
        return runtime.execute(question,"历史", "t",events::add,deltas==null?null:deltas::add,REQUEST,null,committed::set,
                new PlannerInput("",null),new EvidenceSession("c","turn"));
    }
    private static String adaptive(JsonNode plan){return ((ObjectNode)json(envelope(plan))).put("execution_mode","adaptive").toString();}
    private static String finish(boolean complete){return "{\"action\":\"finish\",\"reason\":\"按现有证据收束\",\"completion\":\""+(complete?"complete":"partial")+"\"}";}
    private static String add(JsonNode plan){var node=JSON.createObjectNode().put("action","continue").put("reason","补足用户要求");node.set("plan",plan);return node.toString();}
    private static ObjectNode funding(String id,int count,String... exchanges){var m=metric(id,"funding.rate_settled","recent_n",exchanges);m.remove(List.of("time","interval"));m.put("count",count);return m;}
    private static ObjectNode averagePlan(String id,int count,String exchange){var p=plan(funding(id,count,exchange));calculation(p,"long_avg","avg","{\"kind\":\"field\",\"collection\":\"{{"+id+"."+exchange+".samples}}\",\"field\":\"value\"}");return p;}
    private static JsonNode entry(ExecutionResult result,String id){for(var e:result.evidence())if(id.equals(e.path("node_id").asText()))return e;throw new AssertionError(id);}

    @ParameterizedTest @ValueSource(booleans={true,false})
    void modelChoosesNextExchangeFromActualComparisonAndOnlySelectedBranchExecutes(boolean binanceHigher) {
        var initial=plan(funding("m1",3,"binance","okx"));
        for(String exchange:List.of("binance","okx"))calculation(initial,exchange+"_avg","avg","{\"kind\":\"field\",\"collection\":\"{{m1."+exchange+".samples}}\",\"field\":\"value\"}");
        calculation(initial,"comparison","compare","{\"left\":\"{{binance_avg.value}}\",\"right\":\"{{okx_avg.value}}\"}");
        var observed=new AtomicBoolean();
        var selected=binanceHigher?"binance":"okx";
        var script=new Script(adaptive(initial),"SELECT",finish(true),"完整回答") {
            @Override public ChatResponse chat(List<ChatMessage> messages,List<ToolSpec> tools) {
                if("SELECT".equals(outputs.peek())) {
                    var input=json(messages.get(1).content());
                    var relation=new AtomicReference<String>();
                    input.path("observations").forEach(e->{if("comparison".equals(e.path("node_id").asText()))relation.set(e.at("/data/relation").asText());});
                    assertEquals(binanceHigher?"greater":"less",relation.get());observed.set(true);
                    outputs.remove();calls.add(List.copyOf(messages));
                    return new ChatResponse(add(averagePlan("m2",10,relation.get().equals("greater")?"binance":"okx")),List.of(),10,5);
                }
                return super.chat(messages,tools);
            }
        };
        var fetched=Collections.synchronizedList(new ArrayList<String>());
        var provider=namedProvider("funding",FundingMetric::valueOf,(q,c)->{
            var count=(CountQuery)q;assertEquals(REQUEST.requestTime(),c.requestTime());
            fetched.add(count.binding().exchange()+":"+count.count());
            boolean high=count.binding().exchange().equals(binanceHigher?"binance":"okx");
            return new MetricResult.Series(new MetricResult.RecentMetadata(count.count(),count.count(),true,null,c.userZone()),
                    IntStream.range(0,count.count()).mapToObj(i->new MetricResult.Sample(c.requestTime().minusSeconds((count.count()-i)*28800L),new BigDecimal(high?"0.003":"0.001"))).toList());
        });
        var deltas=new ArrayList<String>();var result=run(runtime(script,provider),"比较两所BTC最近3期资金费率均值，只对均值较高的一所查询最近10期均值",deltas);
        assertTrue(observed.get());assertEquals(3,fetched.size());assertTrue(fetched.contains(selected+":10"));
        assertFalse(fetched.contains((binanceHigher?"okx":"binance")+":10"));
        assertEquals("0.003",entry(result,"long_avg").at("/data/value").asText());
        assertEquals(3,result.metricFetchCount());assertEquals(4,result.calculationCount());assertEquals(7,result.evidence().size());
        assertEquals(7,facts.rows.size());assertEquals(3,result.plannerLlmCalls());assertEquals(1,result.answerLlmCalls());
        assertEquals(List.of("完整回答"),deltas);assertEquals("MODEL_FINISH",result.agentPayload().at("/execution/stop_reason").asText());
        assertEquals(2,committed.get().logicalPlan().metrics().size());assertEquals(4,committed.get().logicalPlan().calculations().size());
        var frame=QueryFrame.fromCompiledPlan("c","turn","完整原任务",REQUEST.requestTime(),REQUEST.userZone(),committed.get(),result.evidence());
        assertEquals(3,frame.targets().size());assertEquals(7,frame.focus().path("evidence_nodes").size());
        assertTrue(events.stream().anyMatch(e->e.eventType().equals("QUERY_ITERATION") && e.outputJson().contains("cumulative_plan")));
    }
    @Test void addsMissingCalculationUsingExistingFactsWithoutRefetching() {
        var initial=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
        var delta=plan();calculation(delta,"difference","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m1.okx.value}}\"}");
        var calls=new AtomicInteger();var script=new Script(adaptive(initial),add(delta),finish(true),"差20");
        var result=run(runtime(script,provider((q,c)->{calls.incrementAndGet();return metricResult(q,c,24,true); })),"两所成交额差多少",null);
        assertEquals(2,calls.get());assertEquals("20",entry(result,"difference").at("/data/value").asText());
        assertEquals(3,facts.rows.size());
        var derived=facts.rows.values().stream().filter(EvidenceFact::derived).findFirst().orElseThrow();assertEquals(2,derived.sourceFactIds().size());
    }
    @Test void duplicateRequestStopsWithoutAnExtraProviderCall() {
        var script=new Script(adaptive(scalarPlan()),add(plan(metric("again","trade.turnover","range_statistic","binance"))),"已停止重复查询");
        var calls=new AtomicInteger();var result=run(runtime(script,provider((q,c)->{calls.incrementAndGet();return metricResult(q,c,24,true);})),"成交额",null);
        assertEquals(1,calls.get());assertEquals("NO_PROGRESS",result.agentPayload().at("/execution/stop_reason").asText());
        assertEquals(1,committed.get().logicalPlan().metrics().size());
    }
    @Test void rejectedPlanCanBeCorrectedButCannotOverrideOriginalConditions() {
        var replacement=plan(metric("m1","trade.turnover","range_statistic","okx"));
        var delta=plan();calculation(delta,"avg","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        var script=new Script(adaptive(scalarPlan()),add(replacement),add(delta),finish(true),"结果");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额均值",null);
        assertEquals("binance",committed.get().logicalPlan().metrics().get(0).exchanges().get(0));
        assertEquals(1,result.metricFetchCount());assertEquals(1,result.calculationCount());
        assertTrue(script.calls.get(2).get(1).content().contains("不能覆盖"));
    }
    @Test void inventedConstantsNeverExecute() {
        var delta=plan();calculation(delta,"bad","avg","{\"kind\":\"values\",\"values\":[\"998877.2\"]}");
        var script=new Script(adaptive(scalarPlan()),add(delta),finish(false),"不支持该输入");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额均值",null);
        assertEquals(0,result.calculationCount());assertTrue(script.calls.get(2).get(1).content().contains("数值常量"));
        assertEquals(1,committed.get().dagPlan().nodes().size());
    }
    @Test void partialFailureRetainsIndependentSuccessAndCannotBecomeCompleteByModelClaim() {
        var initial=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
        var script=new Script(adaptive(initial),finish(true),"OKX缺失，只有币安结果");
        var result=run(runtime(script,provider((q,c)->{if(q.binding().exchange().equals("okx"))throw new IllegalStateException("timeout");return metricResult(q,c,24,true);})),"两所成交额",null);
        assertEquals(2,result.evidence().size());assertTrue(entry(result,"metric_0").path("ok").asBoolean());
        assertFalse(entry(result,"metric_1").path("ok").asBoolean());assertEquals("PARTIAL",result.agentPayload().at("/execution/execution_status").asText());
    }
    @Test void clarificationKeepsAcquiredEvidenceWithoutCallingAnswer() {
        var script=new Script(adaptive(scalarPlan()),"{\"action\":\"clarify\",\"reason\":\"需确认基准\",\"clarify\":[{\"key\":\"baseline\",\"label\":\"比较基准\",\"reason\":\"missing\"}]}");
        var deltas=new ArrayList<String>();var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额再比较",deltas);
        assertEquals("NEEDS_CLARIFICATION",result.turnStatus());assertEquals(0,result.answerLlmCalls());
        assertEquals(1,result.evidence().size());assertEquals(1,result.savedFactIds().size());assertEquals(1,ClarificationField.fromPayload(result.agentPayload()).size());
        assertEquals(List.of(result.answer()),deltas);
    }
    @Test void continuationLimitStopsAndKeepsCumulativeTask() {
        policy.setMaxContinuations(1);
        var delta=plan();calculation(delta,"avg","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        var further=plan();calculation(further,"min","min","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        var script=new Script(adaptive(scalarPlan()),add(delta),add(further),"预算不足");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额",null);
        assertEquals("CONTINUATION_LIMIT",result.agentPayload().at("/execution/stop_reason").asText());assertEquals(1,result.calculationCount());
    }
    @Test void toolBudgetStopsBeforeAddedProviderIsInvoked() {
        policy.setMaxToolCalls(1);
        var script=new Script(adaptive(scalarPlan()),add(plan(metric("m2","trade.turnover","range_statistic","okx"))),"只得到币安结果");
        var calls=new AtomicInteger();var result=run(runtime(script,provider((q,c)->{calls.incrementAndGet();return metricResult(q,c,24,true);})),"两所成交额",null);
        assertEquals(1,calls.get());assertEquals("TOOL_BUDGET",result.agentPayload().at("/execution/stop_reason").asText());
    }
    @Test void disabledAdaptiveUsesSingleExecutionAndExplainsUnfinishedTask() {
        policy.setAdaptiveEnabled(false);var script=new Script(adaptive(scalarPlan()),"仅完成第一步");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额",null);
        assertEquals(2,script.calls.size());assertEquals("ADAPTIVE_DISABLED",result.agentPayload().at("/execution/stop_reason").asText());
    }
    @Test void continuationTransportFailureRetainsUnknownCostAndSavedFacts() {
        var script=new Script(adaptive(scalarPlan())) {
            @Override public ChatResponse chat(List<ChatMessage> messages,List<ToolSpec> tools){if(calls.size()==1)throw new IllegalStateException("continuation timeout");return super.chat(messages,tools);}
        };
        var failure=assertThrows(ExecutionFailure.class,()->run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额",null));
        assertEquals(2,failure.counters.plannerLlmCalls());assertEquals(1,failure.counters.savedFactIds().size());
        assertNull(LlmUsage.totalCost(failure.counters.llmUsages()));assertEquals(1,failure.evidence.size());
    }
    @Test void routerAndPlannerRepairShareBudgetWhileFinalAnswerHasReservedCall() {
        policy.setMaxModelCalls(4);
        var script=new Script("{\"route\":\"query\"}","invalid",adaptive(scalarPlan()),"只完成初始查询");
        var prompts=new PromptStore(LOADER);
        var query=runtime(script,provider((q,c)->metricResult(q,c,24,true)));
        var router=new com.cexpilot.agent.IntentRouter(new com.cexpilot.agent.PlanningLlm(script,new LlmConfig(),prompts),prompts);
        var dispatcher=new com.cexpilot.agent.AgentDispatcher(router,
                new com.cexpilot.agent.AgentRegistry(List.of(new com.cexpilot.agent.MarketAnalysisAgent(query))),
                new com.cexpilot.agent.AgentContextAssembler(),policy);
        var result=dispatcher.execute("成交额","","t",events::add,null,REQUEST,null,committed::set,new PlannerInput("",null),
                new EvidenceSession("c","turn"),JSON.createObjectNode(),selected->{});
        assertEquals(4,script.calls.size());assertEquals(1,result.routerLlmCalls());assertEquals(2,result.plannerLlmCalls());
        assertEquals(1,result.answerLlmCalls());assertEquals("MODEL_BUDGET",result.agentPayload().at("/execution/stop_reason").asText());
        assertEquals(4,result.agentPayload().at("/execution/budget/model_calls").asInt());
    }
    @Test void tokenLimitKeepsFactsAndUsesDeterministicTerminationWithoutAnotherModel() {
        policy.setMaxObservedTokens(15);
        var script=new Script(adaptive(scalarPlan()));
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额",null);
        assertEquals(1,script.calls.size());assertEquals(0,result.answerLlmCalls());assertEquals(1,result.evidence().size());
        assertEquals("TOKEN_BUDGET",result.agentPayload().at("/execution/stop_reason").asText());
        assertTrue(result.answer().contains("尚未完整完成"));
    }
    @Test void allNewNodesFailingStopsForNoProgressAndRetainsEarlierEvidence() {
        var script=new Script(adaptive(scalarPlan()),add(plan(metric("m2","trade.turnover","range_statistic","okx"))),"保留币安结果");
        var result=run(runtime(script,provider((q,c)->{if(q.binding().exchange().equals("okx"))throw new IllegalStateException("unavailable");return metricResult(q,c,24,true);})),"两所成交额",null);
        assertEquals("NO_PROGRESS",result.agentPayload().at("/execution/stop_reason").asText());
        assertEquals(2,result.evidence().size());assertEquals(1,facts.rows.size());assertEquals(3,script.calls.size());
    }
    @Test void equivalentAbsoluteWindowIsAlsoRecognizedAsDuplicateQuery() {
        var absolute=metric("m2","trade.turnover","range_statistic","binance");
        absolute.set("time",json("{\"type\":\"absolute_range\",\"start\":{\"year\":2026,\"month\":11,\"day\":5},\"end\":{\"year\":2026,\"month\":11,\"day\":5},\"end_mode\":\"inclusive_date\"}"));
        var script=new Script(adaptive(scalarPlan()),add(plan(absolute)),"停止");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"昨天成交额",null);
        assertEquals(1,result.metricFetchCount());assertEquals("NO_PROGRESS",result.agentPayload().at("/execution/stop_reason").asText());
    }
    @Test void fullTaskAndAllEvidenceAreCommittedForNextTurn() {
        var delta=plan(metric("m2","trade.turnover","range_statistic","okx"));
        calculation(delta,"difference","difference","{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m2.okx.value}}\"}");
        var script=new Script(adaptive(scalarPlan()),add(delta),finish(true),"完整答案");
        var repo=new InMemoryTaskRepository();var conversations=new ConversationService(repo,new ConversationConfig());
        var turn=conversations.beginTurn("c","先查币安，再查OKX并计算差值",null,REQUEST.requestTime());
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"先查币安，再查OKX并计算差值",null);
        conversations.finishTaskTurn("c",turn,"先查币安，再查OKX并计算差值","SUCCEEDED",result.answer(),"t",committed.get(),result.evidence(),
                result.taskScope(),REQUEST.requestTime(),REQUEST.userZone(),null);
        var next=new ConversationContextService(repo,new EvidenceStore(facts)).buildPlannerInput("c");
        assertEquals("先查币安，再查OKX并计算差值",next.currentTask().originQuestion());
        assertEquals(2,next.currentTask().logicalPlan().path("metrics").size());
        assertEquals(1,next.currentTask().logicalPlan().path("calculations").size());
        assertEquals(2,next.activeFrame().targets().size());assertEquals(3,next.activeFrame().focus().path("evidence_nodes").size());
    }
    @Test void continuationCannotIntroduceAnUnboundHistoricalReference() {
        var pinned=metric("m2","trade.turnover","range_statistic","okx").put("reference_mode","pinned_result").put("evidence_ref","fact_99");
        var script=new Script(adaptive(scalarPlan()),add(plan(pinned)),finish(false),"历史引用不可用");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额",null);
        assertEquals(1,result.metricFetchCount());assertEquals(1,committed.get().targets().size());
        assertTrue(script.calls.get(2).get(1).content().contains("不新增历史绑定"));
    }

    @ParameterizedTest @ValueSource(strings={"metric","market","operator","url"})
    void unauthorizedExtensionsAreRejectedBeforeExecutingAnyAddedNode(String kind) {
        var added=metric("m2","trade.turnover","range_statistic","okx");
        var delta=plan(added);
        switch(kind) {
            case "metric" -> added.put("metric","invented.metric");
            case "market" -> ((ObjectNode)added.path("instrument")).put("market_type","options");
            case "operator" -> calculation(delta,"bad","shell","{\"left\":\"{{m1.binance.value}}\",\"right\":\"{{m1.binance.value}}\"}");
            case "url" -> delta.put("url","http://127.0.0.1/unauthorized");
        }
        var script=new Script(adaptive(scalarPlan()),add(delta),finish(false),"能力不足");
        var result=run(runtime(script,provider((q,c)->{assertEquals("binance",q.binding().exchange());return metricResult(q,c,24,true);})),"成交额",null);
        assertEquals(1,result.metricFetchCount());assertEquals(0,result.calculationCount());
        assertEquals(1,committed.get().dagPlan().nodes().size());
    }
    @Test void historicalSnapshotRemainsPinnedAcrossContinuationAndIsNotResaved() {
        var snapshot=metric("m1","price.last","snapshot","binance");snapshot.remove(List.of("time","interval"));
        var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry(),catalogProviders());
        var seed=compiler.compileFull(plan(snapshot),8);
        var store=new EvidenceStore(facts);
        var observedAt=REQUEST.requestTime().minusSeconds(60);
        var payload=MetricResultJson.write(seed.dagPlan().nodes().get(0).metric(),new MetricResult.Point(
                new MetricResult.SnapshotMetadata(observedAt,REQUEST.userZone()),new BigDecimal("83000")));
        var historical=store.saveFact("c","old-turn","old-trace",seed.targets().get(0),payload,observedAt).orElseThrow();
        var candidates=EvidenceAssembler.assignRefs(store.listReusable("c",REQUEST.requestTime()));
        snapshot.put("reference_mode","pinned_result").put("evidence_ref","fact_1");
        var delta=plan();calculation(delta,"difference","difference","{\"left\":84000,\"right\":\"{{m1.binance.value}}\"}");
        var script=new Script(adaptive(plan(snapshot)),add(delta),finish(true),"原报价与84000差1000");
        var query=runtime(script,namedProvider("ticker",TickerMetric::valueOf,(q,c)->{throw new AssertionError("历史结果不得重新取数");}));
        var result=query.execute("84000比刚才原报价高多少","","t",events::add,null,REQUEST,null,committed::set,
                new PlannerInput(EvidenceAssembler.render(candidates),null,candidates),new EvidenceSession("c","turn"));
        assertEquals(0,result.metricFetchCount());assertEquals(1,result.reusedFactCount());assertEquals(1,result.calculationCount());
        assertEquals("1000",entry(result,"difference").at("/data/value").asText());
        assertEquals("old-turn",entry(result,"metric_0").path("source_turn_id").asText());
        assertEquals(historical.factId(),entry(result,"metric_0").path("fact_id").asText());
        assertEquals(2,facts.rows.size());assertEquals("pinned_result",committed.get().logicalPlan().metrics().get(0).referenceMode());
    }
    @Test void streamedDeadlineKeepsPublishedPrefixAndSuppressesLateDeltas() {
        policy.setTimeoutMs(2000);
        var snapshot=metric("m1","price.last","snapshot","binance");snapshot.remove(List.of("time","interval"));
        var callback=new AtomicReference<java.util.function.Consumer<String>>();
        var script=new Script(envelope(plan(snapshot))) {
            @Override public ChatResponse chatStream(List<ChatMessage> messages,List<ToolSpec> tools,java.util.function.Consumer<String> delta) {
                callback.set(delta);delta.accept("已取得结果。");
                try{new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                delta.accept("迟到文本");return new ChatResponse("迟到答案",List.of(),10,5);
            }
        };
        var deltas=new ArrayList<String>();
        var result=run(runtime(script,namedProvider("ticker",TickerMetric::valueOf,(q,c)->new MetricResult.Point(
                new MetricResult.SnapshotMetadata(c.requestTime(),c.userZone()),BigDecimal.TEN))),"BTC现价",deltas);
        assertEquals("DEADLINE",result.agentPayload().at("/execution/stop_reason").asText());
        assertTrue(result.answer().startsWith("已取得结果。"));assertEquals(result.answer(),String.join("",deltas));
        callback.get().accept("又一条迟到文本");assertEquals(result.answer(),String.join("",deltas));
        assertFalse(result.answer().contains("迟到"));assertNull(LlmUsage.totalCost(result.llmUsages()));
    }

    @Test void newLatestSnapshotIsDistinctFromPinnedHistoryForSameInstrument() {
        var snapshot=metric("m1","price.last","snapshot","binance");snapshot.remove(List.of("time","interval"));
        run(runtime(new Script(envelope(plan(snapshot)),"旧值"),namedProvider("ticker",TickerMetric::valueOf,(q,c)->new MetricResult.Point(
                new MetricResult.SnapshotMetadata(c.requestTime().minusSeconds(60),c.userZone()),new BigDecimal("83000")))),"旧价格",null);
        var candidates=EvidenceAssembler.assignRefs(new EvidenceStore(facts).listReusable("c",REQUEST.requestTime()));
        var pinned=snapshot.deepCopy().put("id","old").put("reference_mode","pinned_result").put("evidence_ref","fact_1");
        var delta=plan(snapshot.deepCopy().put("id","current"));
        calculation(delta,"difference","difference","{\"left\":\"{{current.binance.value}}\",\"right\":\"{{old.binance.value}}\"}");
        var script=new Script(adaptive(plan(pinned)),add(delta),finish(true),"现价比旧价高1000");
        var query=runtime(script,namedProvider("ticker",TickerMetric::valueOf,(q,c)->new MetricResult.Point(
                new MetricResult.SnapshotMetadata(c.requestTime(),c.userZone()),new BigDecimal("84000"))));
        var result=query.execute("现在比原报价高多少","","t",events::add,null,REQUEST,null,committed::set,
                new PlannerInput(EvidenceAssembler.render(candidates),null,candidates),new EvidenceSession("c","second-turn"));
        assertEquals(1,result.metricFetchCount());assertEquals(1,result.reusedFactCount());assertEquals(1,result.calculationCount());
        assertEquals("1000",entry(result,"difference").at("/data/value").asText());
        assertTrue(entry(result,"metric_0").path("reused").asBoolean());assertFalse(entry(result,"metric_1").path("reused").asBoolean());
        assertEquals(3,facts.rows.size());
    }

    @Test void reversedCalculationListStillPreservesDerivedFactDependencyChain() {
        var delta=plan();
        calculation(delta,"difference","difference","{\"left\":\"{{average.value}}\",\"right\":100}");
        calculation(delta,"average","avg","{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        var script=new Script(adaptive(scalarPlan()),add(delta),finish(true),"均值减100为20");
        var result=run(runtime(script,provider((q,c)->metricResult(q,c,24,true))),"成交额均值减100",null);
        assertEquals("20",entry(result,"difference").at("/data/value").asText());
        var average=facts.rows.values().stream().filter(f->"avg".equals(f.identity().path("operator").asText())).findFirst().orElseThrow();
        var difference=facts.rows.values().stream().filter(f->"difference".equals(f.identity().path("operator").asText())).findFirst().orElseThrow();
        assertEquals(List.of(average.factId()),difference.sourceFactIds());assertEquals(1,average.sourceFactIds().size());
    }

}
