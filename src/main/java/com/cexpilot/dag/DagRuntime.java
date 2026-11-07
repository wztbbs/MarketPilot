package com.cexpilot.dag;

import com.cexpilot.llm.ChatMessage;
import com.cexpilot.llm.ChatResponse;
import com.cexpilot.llm.LlmClient;
import com.cexpilot.llm.LlmTraceSerializer;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.ExecutionFailure;
import com.cexpilot.runtime.TurnCounters;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Query 运行时：首轮逻辑计划 → DAG；single 直接回答，adaptive 观察后有界追加计划，最终统一回答。
 *
 * 工具返回的完整 evidence 直接作为回答的 FACTS，不在回答前删除明细；统一按问题与证据适用事实约束。
 * 出域直接返回边界话术；planner 有意不规划且给出 reply（追问/能力缺口）时直接透传为答案；
 * 规划失败或没有计划时也直接返回，禁止再让 Answer 用空事实生成答案。
 */
@Component
public class DagRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(DagRuntime.class);
    private static final String ANSWER_PROMPT_NAME = "agent_system";
    // 与行情工具未携带用户时区时的默认口径一致，不使用服务器本地时区。
    private static final ZoneId DEFAULT_USER_ZONE = ZoneOffset.ofHours(8);
    private static final String OUT_OF_DOMAIN_FALLBACK =
            "我只支持 web3 / 加密货币交易领域的问题，暂时无法回答其他类型的问题。";

    private final LlmClient llm;
    private final DagPlanner planner;
    private final DagExecutor executor;
    private final PromptStore prompts;
    private final Clock clock;
    // 阶段二：历史事实存取；null 时跳过事实保存与 REUSE_FACT 绑定（单轮旧行为）
    private final com.cexpilot.evidence.EvidenceStore evidenceStore;
    private final com.cexpilot.config.QueryExecutionConfig queryConfig;

    public DagRuntime(LlmClient llm, DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      Clock clock) {
        this(llm, planner, executor, prompts, clock, null);
    }

    public DagRuntime(@org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.config.LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient llm,
                      DagPlanner planner, DagExecutor executor, PromptStore prompts,
                      Clock clock, com.cexpilot.evidence.EvidenceStore evidenceStore) {
        this(llm,planner,executor,prompts,clock,evidenceStore,new com.cexpilot.config.QueryExecutionConfig());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DagRuntime(@org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.config.LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient llm,
            DagPlanner planner,DagExecutor executor,PromptStore prompts,Clock clock,com.cexpilot.evidence.EvidenceStore evidenceStore,
            com.cexpilot.config.QueryExecutionConfig queryConfig) {
        this.queryConfig=queryConfig;
        this.llm = llm;
        this.planner = planner;
        this.executor = executor;
        this.prompts = prompts;
        this.clock = clock;
        this.evidenceStore = evidenceStore;
    }

    public ExecutionResult execute(String question, String conversationContext, String traceId, TraceSink sink) {
        return execute(question, conversationContext, traceId, sink, null, null);
    }

    /**
     * @param answerDelta 非 null 时 answer 阶段走流式调用，逐段回调答案增量（用于 SSE 推送）；
     *                    trace 落库与聚合逻辑不变。
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta) {
        return execute(question, conversationContext, traceId, sink, answerDelta, null);
    }

    /**
     * @param requestContext 请求时间上下文（用户时区 + 固定 requestTime），随 ToolContext
     *                       传给每个工具节点和 Answer；缺省值在规划前统一补齐
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, null);
    }

    /**
     * @param progress 非 null 时在阶段边界回调进度文案（SSE 推给前端展示"执行到哪一步"）
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress, null);
    }

    /**
     * @param compiledPlanSink 非 null 时，计划通过校验后、执行前回调完整编译产物（含 LogicalPlan 与
     *                         有效条件），供轮次/QueryFrame 持久化；不影响任何执行行为
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress,
                compiledPlanSink, null);
    }

    /**
     * @param plannerInput 结构化 Planner 输入（阶段一 c）：contextText 只注入 planner prompt，
     *                     activeFrame 供继承对账；Answer 侧仍用 conversationContext 纯文本，不受影响。
     *                     null 时 planner 沿用 conversationContext（旧行为）
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink,
                                   com.cexpilot.conversation.PlannerInput plannerInput) {
        return execute(question, conversationContext, traceId, sink, answerDelta, requestContext, progress,
                compiledPlanSink, plannerInput, null);
    }

    /**
     * @param evidenceSession 事实保存会话（阶段二）：非 null 且有 EvidenceStore 时，
     *                        执行后、Answer 前把本轮取数结果保存为 FACT（Answer 失败也已保存，F13），
     *                        并启用 pinned_result → REUSE_FACT 绑定
     */
    public ExecutionResult execute(String question, String conversationContext, String traceId,
                                   TraceSink sink, Consumer<String> answerDelta,
                                   RequestContext requestContext, Consumer<String> progress,
                                   Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink,
                                   com.cexpilot.conversation.PlannerInput plannerInput,
                                   com.cexpilot.evidence.EvidenceSession evidenceSession) {
        try(var scope=com.cexpilot.runtime.QueryBudget.open(queryConfig)) {
            return executeBudgeted(question,conversationContext,traceId,sink,answerDelta,requestContext,progress,compiledPlanSink,plannerInput,evidenceSession);
        }
    }
    private ExecutionResult executeBudgeted(String question,String conversationContext,String traceId,TraceSink sink,Consumer<String> answerDelta,
            RequestContext requestContext,Consumer<String> progress,Consumer<com.cexpilot.metric.CompiledPlan> compiledPlanSink,
            com.cexpilot.conversation.PlannerInput plannerInput,com.cexpilot.evidence.EvidenceSession evidenceSession) {
        RequestContext effectiveContext = resolveRequestContext(requestContext);
        ObjectNode timeContext = answerTimeContext(effectiveContext,
                requestContext != null && requestContext.userZone() != null);
        String plannerContext = plannerInput != null && plannerInput.contextText() != null
                ? plannerInput.contextText() : conversationContext;
        com.cexpilot.conversation.QueryFrame activeFrame = plannerInput != null ? plannerInput.activeFrame() : null;
        com.cexpilot.evidence.EvidenceBindingContext evidenceContext =
                plannerInput != null && evidenceSession != null && evidenceStore != null
                        ? new com.cexpilot.evidence.EvidenceBindingContext(
                                evidenceSession.conversationId(), plannerInput.evidence(), evidenceStore)
                        : null;
        emit(progress, "正在生成执行计划…");
        DagPlanner.PlanOutcome outcome;
        try {
            String plannerQuestion = plannerInput != null && plannerInput.resolvedQuestion() != null
                    ? plannerInput.resolvedQuestion() : question;
            outcome = planner.plan(plannerQuestion, plannerContext, traceId, sink,
                    activeFrame, effectiveContext, evidenceContext,
                    plannerInput == null ? List.of() : plannerInput.tasks(),
                    plannerInput == null ? null : plannerInput.currentTask(),
                    plannerInput != null && plannerInput.clarificationSubmission(),
                    plannerInput != null && plannerInput.resolvedQuestion() != null ? question : null);
        } catch (DagPlanner.PlanningFailure pf) {
            // 4.3：planner 阶段失败（LLM 欠费/超时）同样带出已发生的调用计数
            throw new ExecutionFailure(pf, new TurnCounters(pf.llmCalls, 0, 0, 0, 0, 0, List.of())
                    .withLlmUsages(pf.llmUsages), pf.taskScope);
        }
        if (outcome.taskScope() != null) {
            effectiveContext = outcome.taskScope().executionContext(effectiveContext);
            timeContext = answerTimeContext(effectiveContext, requestContext != null && requestContext.userZone() != null);
        }
        int totalPromptTokens = outcome.promptTokens();
        int totalCompletionTokens = outcome.completionTokens();
        int plannerLlmCalls = outcome.llmCalls();
        List<LlmUsage> usages = new ArrayList<>(outcome.llmUsages());

        if (!outcome.inDomain()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? OUT_OF_DOMAIN_FALLBACK : outcome.reply();
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, null, null, 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages);
        }

        // 澄清：不执行、不落 frame；编排层把轮次标 NEEDS_CLARIFICATION 并把澄清问题写入 pendingClarification
        if (outcome.needsClarification()) {
            String answer = outcome.reply() == null || outcome.reply().isBlank()
                    ? "这个问题我需要再确认一下，请补充说明。" : outcome.reply();
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            ObjectNode clarification = MAPPER.createObjectNode().put("status", "NEEDS_CLARIFICATION");
            clarification.set("clarify", MAPPER.valueToTree(outcome.clarificationFields()));
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, "NEEDS_CLARIFICATION", outcome.reply(), 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages).withAgent("query", clarification);
        }

        // 没有获准执行的计划，就没有 Answer 调用；拒答也走同一个 SSE 返回路径。
        if (outcome.plan().isEmpty()) {
            String answer = outcome.lastError() != null
                    ? outcome.reply() != null && !outcome.reply().isBlank() ? outcome.reply()
                    : "暂时无法可靠生成查询计划，本次未执行查询。请明确交易对和时间要求后重试。"
                    : outcome.reply() != null && !outcome.reply().isBlank() ? outcome.reply()
                    : "当前没有能够可靠回答此问题的查询计划，暂时无法处理。";
            if (answerDelta != null) {
                answerDelta.accept(answer);
            }
            return new ExecutionResult(answer, MAPPER.createArrayNode(), 0, 0,
                    totalPromptTokens, totalCompletionTokens, outcome.lastError() == null ? null : "FAILED", null, 0,
                    plannerLlmCalls, 0, 0, 0, 0, List.of()).withTaskScope(outcome.inDomain() ? outcome.taskScope() : null).withLlmUsages(usages);
        }

        ArrayNode evidence = MAPPER.createArrayNode();
        int toolCallCount = 0;
        int steps = 0;

        ObjectNode queryStatus = MAPPER.createObjectNode();
        // reply 是 planner 写的查询缺口说明；模型偶尔会把推理过程倒进来，截断防污染
        if (outcome.reply() != null) {
            String missing = outcome.reply();
            queryStatus.put("missing", missing.length() <= 200 ? missing : missing.substring(0, 200));
        }
        var compiled=outcome.plan().orElseThrow();
        var bindings=outcome.bindings();
        var budget=com.cexpilot.runtime.QueryBudget.current();
        boolean answerAttempted=false;
        Map<String,String> savedFactIds=new java.util.LinkedHashMap<>();
        List<String> newFactIds=new ArrayList<>();
        Map<String,ToolResult> results=new java.util.LinkedHashMap<>();
        int reusedFactCount=0,metricFetchCount=0,calculationCount=0,httpRequests=0;
        int continuationCalls=0,addedRounds=0;
        String stopReason="SINGLE";
        String turnStatus=null,clarify=null;
        List<ClarificationField> fields=List.of();
        boolean partial=outcome.reply()!=null && !outcome.reply().isBlank();
        if(compiledPlanSink!=null)compiledPlanSink.accept(compiled);
        try {
            boolean execute=true;
            int decisions=0;
            while(true) {
                if(execute) {
                    var previous=new java.util.HashSet<>(results.keySet());
                    var nodes=newNodesInDependencyOrder(compiled.dagPlan(),previous);
                    budget.admitTools(nodes.size());
                    emit(progress,addedRounds==0?"执行计划已生成（"+nodes.size()+" 项查询），正在获取数据…":"正在执行补充计划（"+nodes.size()+" 项查询或计算）…");
                    var execution=executor.executeIncremental(compiled.dagPlan(),traceId,sink,effectiveContext,bindings,results,budget);
                    httpRequests+=execution.httpRequests();steps+=execution.layers();
                    for(var node:nodes) {
                        var result=execution.context().get(node.id());if(result==null)continue;
                        results.put(node.id(),result);toolCallCount++;
                        boolean reused=bindings!=null && bindings.reuseFor(node.id())!=null;
                        if(node.metric()!=null && !reused)metricFetchCount++;
                        else if(node.metric()==null && result.ok())calculationCount++;
                        if(evidenceSession!=null && evidenceStore!=null) {
                            var saved=saveFacts(compiled,node,result,bindings,savedFactIds,evidenceSession,traceId,effectiveContext.requestTime());
                            savedFactIds.putAll(saved);if(!reused)newFactIds.addAll(saved.values());
                        }
                        appendEvidence(evidence,node,result,bindings,savedFactIds);
                        ((ObjectNode)evidence.get(evidence.size()-1)).put("execution_round",addedRounds);
                        if(reused && result.ok())reusedFactCount++;
                        if(!result.ok() || result.data()!=null && (result.data().has("sample_complete") && !result.data().path("sample_complete").asBoolean()
                                || result.data().at("/coverage/range_complete").isBoolean() && !result.data().at("/coverage/range_complete").asBoolean()))partial=true;
                    }
                    traceIteration(sink,traceId,"EXECUTED",compiled,question,budget.snapshot(),null);
                    if(addedRounds>0 && nodes.stream().map(n->results.get(n.id())).noneMatch(DagRuntime::usableResult)) {
                        stopReason="NO_PROGRESS";partial=true;break;
                    }
                }
                if(!outcome.adaptive())break;
                if(!queryConfig.isAdaptiveEnabled() || !budget.config().isAdaptiveEnabled()){stopReason="ADAPTIVE_DISABLED";partial=true;break;}
                if(decisions++>budget.config().getMaxContinuations()){stopReason="CONTINUATION_LIMIT";partial=true;break;}
                emit(progress,"正在检查已有结果，决定是否补充查询或计算…");
                com.cexpilot.llm.ChatResponse response;
                int before=usages.size();
                try {response=planner.observe(question,compiled,evidence,queryStatus,effectiveContext,traceId,sink,usages);}
                finally {continuationCalls+=usages.size()-before;}
                try {
                    if(response.hasToolCalls())throw new IllegalArgumentException("后续规划不能直接调用工具");
                    var decision=com.cexpilot.agent.PlanningJson.parse(response.content());
                    com.cexpilot.agent.PlanningJson.object(decision,java.util.Set.of("action","reason","plan","completion","clarify"),"后续规划");
                    String action=com.cexpilot.agent.PlanningJson.text(decision,"action",20);
                    String reason=com.cexpilot.agent.PlanningJson.text(decision,"reason",200);
                    traceIteration(sink,traceId,action,compiled,question,decision,null);
                    if("finish".equals(action)) {
                        if(decision.hasNonNull("plan") || decision.hasNonNull("clarify"))throw new IllegalArgumentException("结束不能同时提出计划或澄清");
                        String claim=com.cexpilot.agent.PlanningJson.text(decision,"completion",12);
                        if(!java.util.Set.of("complete","partial").contains(claim))throw new IllegalArgumentException("completion 只能 complete/partial");
                        queryStatus.put("model_completion_claim",claim).put("model_note",reason);
                        partial|="partial".equals(claim) || queryStatus.has("last_validation_error");stopReason="MODEL_FINISH";break;
                    }
                    if("clarify".equals(action)) {
                        if(decision.hasNonNull("plan"))throw new IllegalArgumentException("澄清不能同时提出计划");
                        fields=ClarificationField.parse(decision.path("clarify"));
                        if(fields.isEmpty())throw new IllegalArgumentException("澄清必须提供有效字段");
                        clarify=ClarificationField.question(fields);turnStatus="NEEDS_CLARIFICATION";stopReason="CLARIFY";partial=true;break;
                    }
                    if(!"continue".equals(action))throw new IllegalArgumentException("未知后续动作");
                    if(addedRounds>=budget.config().getMaxContinuations()){stopReason="CONTINUATION_LIMIT";partial=true;break;}
                    var next=planner.compileContinuation(compiled,decision.path("plan"),question,effectiveContext);
                    compiled=next;addedRounds++;execute=true;queryStatus.remove("last_validation_error");
                    if(compiledPlanSink!=null)compiledPlanSink.accept(compiled);
                } catch(IllegalArgumentException invalid) {
                    traceIteration(sink,traceId,"REJECTED",compiled,question,null,invalid.getMessage());
                    queryStatus.put("last_validation_error",invalid.getMessage());execute=false;
                    if(invalid.getMessage()!=null && invalid.getMessage().contains("NO_PROGRESS")){stopReason="NO_PROGRESS";partial=true;break;}
                }
            }
        } catch(com.cexpilot.runtime.QueryBudget.Exhausted exhausted) {
            stopReason=exhausted.reason;partial=true;
        } catch(Exception failure) {
            throw new ExecutionFailure(failure,new TurnCounters(plannerLlmCalls+continuationCalls,0,metricFetchCount,calculationCount,httpRequests,reusedFactCount,newFactIds)
                    .withLlmUsages(usages),outcome.taskScope(),evidence);
        }
        queryStatus.put("stop_reason",stopReason).put("execution_status",partial?"PARTIAL":"FINISHED");
        queryStatus.set("budget",budget.snapshot());
        traceIteration(sink,traceId,"STOP",compiled,question,queryStatus,null);
        try {
            String answerText;
            var streamed=new StringBuffer();
            Consumer<String> trackedDelta=answerDelta==null?null:delta->{streamed.append(delta);answerDelta.accept(delta);};
            if(clarify!=null)answerText=clarify;
            else {
                String userContent=question+"\n\n<FACTS>\n"+evidence+"\n</FACTS>\n<QUERY_STATUS>\n"+queryStatus+"\n</QUERY_STATUS>";
                var systemPrompt=prompts.render(ANSWER_PROMPT_NAME,Map.of("time_context",timeContext.toString(),"conversation_context",conversationContext==null?"":conversationContext));
                var messages=List.of(ChatMessage.system(systemPrompt),ChatMessage.user(userContent));
                try {
                    budget.beforeModel(false);answerAttempted=true;
                    emit(progress,"执行已结束，正在根据证据生成回答…");
                    answerText=callAnswerLlm(messages,traceId,sink,trackedDelta,timeContext,usages).content();
                } catch(com.cexpilot.runtime.QueryBudget.Exhausted exhausted) {
                    stopReason=exhausted.reason;partial=true;
                    queryStatus.put("stop_reason",stopReason).put("execution_status","PARTIAL");
                    String suffix=(streamed.isEmpty()?"":"\n\n")+"本次查询因执行预算或时间限制结束，任务尚未完整完成；已取得的结果和缺口保留在本次记录中。";
                    answerText=streamed+suffix;
                    if(answerDelta!=null)answerDelta.accept(suffix);
                    traceIteration(sink,traceId,"STOP",compiled,question,queryStatus,null);
                }
            }
            if(clarify!=null && answerDelta!=null)answerDelta.accept(answerText);
            queryStatus.set("budget",budget.snapshot());
            traceIteration(sink,traceId,"FINAL",compiled,question,queryStatus,null);
            var result=new ExecutionResult(answerText,evidence,toolCallCount,steps,LlmUsage.knownPromptTokens(usages),LlmUsage.knownCompletionTokens(usages),turnStatus,clarify,
                    reusedFactCount,plannerLlmCalls+continuationCalls,answerAttempted?1:0,metricFetchCount,calculationCount,httpRequests,newFactIds)
                    .withTaskScope(outcome.taskScope()).withLlmUsages(usages);
            if(outcome.adaptive() || partial) {
                var payload=MAPPER.createObjectNode().put("status",clarify==null?"SUCCEEDED":"NEEDS_CLARIFICATION");
                payload.set("execution",queryStatus);
                if(!fields.isEmpty())payload.set("clarify",MAPPER.valueToTree(fields));
                result=result.withAgent("query",payload);
            }
            return result;
        } catch(Exception failure) {
            throw new ExecutionFailure(failure,new TurnCounters(plannerLlmCalls+continuationCalls,answerAttempted?1:0,metricFetchCount,calculationCount,httpRequests,reusedFactCount,newFactIds)
                    .withLlmUsages(usages),outcome.taskScope(),evidence);
        }
    }

    /** 事实按依赖顺序保存；模型的 calculations 列表顺序不代表执行顺序。 */
    private static List<PlanNode> newNodesInDependencyOrder(DagPlan plan,java.util.Set<String> existing) {
        var ready=new java.util.HashSet<>(existing);
        var pending=new ArrayList<>(plan.nodes().stream().filter(n->!existing.contains(n.id())).toList());
        var ordered=new ArrayList<PlanNode>();
        while(!pending.isEmpty()) {
            int before=pending.size();
            var iterator=pending.iterator();
            while(iterator.hasNext()) {
                var node=iterator.next();
                if(ready.containsAll(node.dependsOn())) {ordered.add(node);ready.add(node.id());iterator.remove();}
            }
            if(before==pending.size())throw new IllegalStateException("累积计划包含未满足的依赖或环");
        }
        return ordered;
    }

    private static boolean usableResult(ToolResult result) {
        if(result==null || !result.ok() || result.data()==null)return false;
        var data=result.data();
        return !(data.has("sample_complete") && !data.path("sample_complete").asBoolean())
                && !(data.at("/coverage/range_complete").isBoolean() && !data.at("/coverage/range_complete").asBoolean())
                && !data.hasNonNull("statistics_omitted");
    }

    private static void traceIteration(TraceSink sink,String traceId,String action,com.cexpilot.metric.CompiledPlan compiled,
            String question,JsonNode details,String error) {
        var event=MAPPER.createObjectNode().put("action",action).put("original_question",question);
        event.set("cumulative_plan",compiled.logicalPlanJson());if(details!=null)event.set("details",details);
        sink.record(new TraceEvent(traceId,"QUERY_ITERATION","query_runtime",null,event.toString(),null,null,null,null,null,error));
    }

    /**
     * 单节点事实保存：metric 节点存原始 FACT（REUSE_FACT 节点不重复入库，沿用原 factId）；
     * 计算节点存派生 FACT（derived=true + sourceFactIds，不作为样本集来源）。
     * 返回本节点 nodeId→factId 映射（供下游派生事实记录来源与 evidence 标注）。保存失败只记日志。
     */
    private Map<String, String> saveFacts(com.cexpilot.metric.CompiledPlan compiled, PlanNode node,
                                          ToolResult result, com.cexpilot.metric.ExecutionBindings bindings,
                                          Map<String, String> savedFactIds,
                                          com.cexpilot.evidence.EvidenceSession session,
                                          String traceId, java.time.Instant now) {
        if (!result.ok() || result.data() == null) {
            return Map.of();
        }
        try {
            if (node.metric() != null) {
                if (bindings != null && bindings.reuseFor(node.id()) != null) {
                    return Map.of(node.id(), bindings.reuseFor(node.id()).factId());
                }
                com.cexpilot.metric.EffectiveTarget target = compiled.targets().stream()
                        .filter(t -> t.physicalId().equals(node.id())).findFirst().orElse(null);
                if (target == null) {
                    return Map.of();
                }
                return evidenceStore.saveFact(session.conversationId(), session.turnId(), traceId,
                                target, result.data(), now)
                        .map(f -> Map.of(node.id(), f.factId())).orElse(Map.of());
            }
            if (node.tool() != null) {
                List<String> sourceFactIds = node.dependsOn().stream()
                        .map(dep -> savedFactIds.get(dep)).filter(Objects::nonNull).distinct().toList();
                return evidenceStore.saveDerivedFact(session.conversationId(), session.turnId(), traceId,
                                node.tool(), result.data(), sourceFactIds, now)
                        .map(f -> Map.of(node.id(), f.factId())).orElse(Map.of());
            }
        } catch (Exception e) {
            log.warn("事实保存失败（不影响本轮回答）node={}: {}", node.id(), e.getMessage());
        }
        return Map.of();
    }

    private static void emit(Consumer<String> progress, String text) {
        if (progress != null) {
            progress.accept(text);
        }
    }

    private ChatResponse callAnswerLlm(List<ChatMessage> messages, String traceId, TraceSink sink,
                                       Consumer<String> answerDelta, ObjectNode timeContext, List<LlmUsage> usages) {
        long start = System.currentTimeMillis();
        boolean received = false;
        var publishing=new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            var budget=com.cexpilot.runtime.QueryBudget.current();
            Consumer<String> guarded=delta->{
                synchronized(publishing) {
                    if(publishing.get() && (budget==null || budget.remainingMs()>0))answerDelta.accept(delta);
                }
            };
            ChatResponse response = com.cexpilot.runtime.QueryBudget.invoke(()->answerDelta == null
                    ? llm.chat(messages, null)
                    : llm.chatStream(messages, null, guarded));
            if(budget!=null)budget.observe(response);
            LlmUsage usage = planner.answerUsage(response);
            usages.add(usage);
            received = true;
            ObjectNode output = LlmTraceSerializer.responseToObject(response);
            output.put("model_role", "normal").put("model", usage.model());
            output.set("estimated_cost", MAPPER.valueToTree(usage.estimatedCost()));
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), output.toString(),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            if (!received) usages.add(planner.answerUsage(null));
            sink.record(TraceEvent.llmCall(traceId, "answer",
                    answerEventInput(messages, timeContext), LlmTraceSerializer.failureToJson(e),
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        } finally {
            synchronized(publishing) {publishing.set(false);}
        }
    }

    private RequestContext resolveRequestContext(RequestContext context) {
        return new RequestContext(
                context != null && context.userZone() != null ? context.userZone() : DEFAULT_USER_ZONE,
                context != null && context.requestTime() != null ? context.requestTime() : clock.instant());
    }

    private static ObjectNode answerTimeContext(RequestContext context, boolean userZoneProvided) {
        var localTime = context.requestTime().atZone(context.userZone());
        ObjectNode node = MAPPER.createObjectNode();
        node.put("request_time_utc", context.requestTime().toString());
        node.put("timezone", context.userZone().getId());
        node.put("timezone_source", userZoneProvided ? "request" : "default");
        node.put("request_time_local", localTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        node.put("current_date", localTime.toLocalDate().toString());
        return node;
    }

    private void appendEvidence(ArrayNode evidence, PlanNode node, ToolResult result,
                                com.cexpilot.metric.ExecutionBindings bindings,
                                Map<String, String> savedFactIds) {
        ObjectNode entry = evidence.addObject();
        entry.put("node_id", node.id());
        if (node.metric() != null) {
            entry.put("type", "metric");
            entry.set("identity", node.metric().identity());
        } else {
            entry.put("type", "calculation");
            entry.put("operator", node.tool());
            entry.set("input_references", node.args());
        }
        entry.put("ok", result.ok());
        String factId = savedFactIds.get(node.id());
        if (factId != null) {
            entry.put("fact_id", factId);
        }
        com.cexpilot.metric.ExecutionBindings.ReuseFact reuse =
                bindings == null ? null : bindings.reuseFor(node.id());
        if (reuse != null) {
            // 复用事实的口径标注：Answer 必须说明数据时间点，不得当成本轮新取
            entry.put("reused", true);
            entry.put("source_turn_id", reuse.sourceTurnId());
            if (reuse.observedAt() != null) entry.put("observed_at", reuse.observedAt());
            entry.put("fetched_at", reuse.fetchedAt());
        }
        if (result.data() != null) {
            entry.set("data", result.data());
        }
        if (result.error() != null) {
            entry.put("error", result.error());
        }
    }

    private String answerEventInput(List<ChatMessage> messages, ObjectNode timeContext) {
        ObjectNode node = MAPPER.createObjectNode();
        llm.requestParameters(null).fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
        node.put("stage", "answer");
        node.put("model_role", "normal");
        if (!node.hasNonNull("model")) node.put("model", planner.answerUsage(null).model());
        node.put("message_count", messages.size());
        node.set("time_context", timeContext);
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        return node.toString();
    }

    public String promptVersion() {
        return PromptStore.fingerprint(planner.promptVersion() + prompts.version(ANSWER_PROMPT_NAME));
    }
}
