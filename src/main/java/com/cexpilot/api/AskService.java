package com.cexpilot.api;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.conversation.ConversationService;
import com.cexpilot.dag.DagRuntime;
import com.cexpilot.runtime.ExecutionResult;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.trace.DbTraceSink;
import com.cexpilot.trace.TraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 公共问答编排：轮次与 Trace → 独立路由 → 原查询链或仅监控规划 → 按 Agent 分别提交上下文。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ConversationService conversation;
    private final com.cexpilot.conversation.ConversationContextService contextService;
    private final DagRuntime runtime;
    private final com.cexpilot.agent.AgentDispatcher dispatcher;
    private final TraceRepository traceRepository;
    private final DbTraceSink traceSink;
    private final LlmConfig llmConfig;
    private final Clock clock;
    private final HomeSuggestions homeSuggestions;
    // 构建时打入的 git commit 指纹；IDE 直跑（无 build-info.properties）时为 null
    private final String buildCommit;

    public AskService(ConversationService conversation,
                      com.cexpilot.conversation.ConversationContextService contextService,
                      DagRuntime runtime,
                      TraceRepository traceRepository,
                      DbTraceSink traceSink,
                      LlmConfig llmConfig,
                      Clock clock,
                      ObjectProvider<BuildProperties> buildProperties) {
        this(conversation,contextService,runtime,traceRepository,traceSink,llmConfig,clock,buildProperties,null);
    }

    public AskService(ConversationService conversation,
                      com.cexpilot.conversation.ConversationContextService contextService,DagRuntime runtime,
                      TraceRepository traceRepository,DbTraceSink traceSink,LlmConfig llmConfig,Clock clock,
                      ObjectProvider<BuildProperties> buildProperties,com.cexpilot.agent.AgentDispatcher dispatcher) {
        this(conversation,contextService,runtime,traceRepository,traceSink,llmConfig,clock,buildProperties,dispatcher,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AskService(ConversationService conversation,
                      com.cexpilot.conversation.ConversationContextService contextService,DagRuntime runtime,
                      TraceRepository traceRepository,DbTraceSink traceSink,LlmConfig llmConfig,Clock clock,
                      ObjectProvider<BuildProperties> buildProperties,com.cexpilot.agent.AgentDispatcher dispatcher,
                      HomeSuggestions homeSuggestions) {
        this.homeSuggestions = homeSuggestions;
        this.conversation = conversation;
        this.contextService = contextService;
        this.runtime = runtime;
        this.dispatcher = dispatcher;
        this.traceRepository = traceRepository;
        this.traceSink = traceSink;
        this.llmConfig = llmConfig;
        this.clock = clock;
        BuildProperties props = buildProperties.getIfAvailable();
        this.buildCommit = props != null ? props.get("commit") : null;
    }

    /** 流式问答的事件出口：meta 在建 trace 后触发，progress 为阶段进度，delta 为答案增量，done 带完整结果。 */
    public interface AskStreamListener {
        /** 失败也可能已经提交为完成轮次；仅在收尾成功后确认，客户端才推进父轮次。 */
        default void onTurnFinished(String turnId) {}
        void onMeta(String conversationId, String traceId, String turnId);

        void onProgress(String text);

        void onDelta(String text);

        void onDone(AskResponse response);
    }

    /** 一轮问答的执行现场：贯穿准备、执行、收尾的可变状态，由 ask 的 try/catch/finally 各段共享。 */
    private final class TurnExecution {
        String traceId;
        String visitorId;
        RequestContext requestContext;
        String conversationId;
        ConversationService.TurnHandle turn;
        /** 本轮提交的澄清表单填写值；无提交为空列表。 */
        java.util.List<com.cexpilot.conversation.ClarificationAnswer> submittedAnswers = java.util.List.of();
        /** Answer 侧使用的近期对话纯文本（renderRecentDialogue 口径）。 */
        String answerHistory;
        com.cexpilot.conversation.PlanningContext plannerInput;
        // 计划通过校验后（执行前）由 runtime 回调，成功及失败路径都以此构造任务版本中的查询定义
        final java.util.concurrent.atomic.AtomicReference<com.cexpilot.metric.CompiledPlan> compiledPlanRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<com.cexpilot.conversation.TaskScope> taskScopeRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new com.cexpilot.conversation.TaskScope(com.cexpilot.conversation.TaskScope.Action.NEW, null));
        final java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> failureEvidenceRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> agentRef =
                new java.util.concurrent.atomic.AtomicReference<>(dispatcher == null ? "query" : "clarify");
        /** 命中的首页固定回复；null = 走正常执行链。 */
        HomeSuggestions.FixedReply preset;
        boolean turnFinished;
        boolean traceStarted;
        boolean traceFinished;
        long startMillis;
    }

    public AskResponse ask(String conversationId, String question) {
        return ask(conversationId, question, null, null, null, null);
    }

    /**
     * 一次问答的完整编排。生产经 AgentDispatcher 分发；兼容构造保留原查询链供旧调用方使用。
     *
     * @param visitorId 访客标识（cexpilot_uid cookie），随 trace 落库用于统计；允许为 null
     */
    public AskResponse ask(String conversationId, String question, String visitorId) {
        return ask(conversationId, question, visitorId, null, null, null);
    }

    /**
     * @param listener 非 null 时走流式：建 trace 后立刻回调 meta，answer 阶段逐段回调 delta，
     *                 完成后回调 done。trace 落库与非流式路径完全一致。
     */
    public AskResponse ask(String conversationId, String question, String visitorId,
                           AskStreamListener listener) {
        return ask(conversationId, question, visitorId, null, null, listener);
    }

    /**
     * @param timezone 请求携带的 IANA 时区（如 Asia/Shanghai），作为时间消解的请求上下文；
     *                 null 或空表示未携带，由各工具按默认口径回落
     */
    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           AskStreamListener listener) {
        return ask(conversationId, question, visitorId, timezone, null, listener);
    }

    /**
     * @param parentTurnId 页面看到的最近完成轮次，登记前校验；null 兼容旧客户端
     */
    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           String parentTurnId, AskStreamListener listener) {
        return ask(conversationId, question, visitorId, timezone, parentTurnId, listener, java.util.Map.of());
    }

    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           String parentTurnId, AskStreamListener listener, java.util.Map<String, ?> answers) {
        return ask(conversationId, question, visitorId, timezone, parentTurnId, listener, answers, true);
    }

    /** 来源标记仅写入 trace，不参与路由、上下文、模型输入或工具执行。 */
    public AskResponse ask(String conversationId, String question, String visitorId, String timezone,
                           String parentTurnId, AskStreamListener listener, java.util.Map<String, ?> answers,
                           boolean isRealUser) {
        TurnExecution exec = prepareTurn(conversationId, question, visitorId, timezone, parentTurnId, answers);
        if (exec.turn == null) {
            // 同会话已有未过期的执行中请求：不建 trace、不执行 DAG，返回明确的忙碌响应（首期串行约束）
            AskResponse busy = new AskResponse(exec.conversationId, null,
                    "上一个问题还在处理中，请稍后再试", null, 0, 0, 0, null, true);
            if (listener != null) {
                listener.onMeta(exec.conversationId, null, null);
                listener.onDone(busy);
            }
            return busy;
        }
        // 固定回复直达：命中首页预写答案时不组装上下文、不规划、不执行，只落最小化 trace 与轮次
        if (exec.preset != null) {
            return presetTurn(exec, question, listener, isRealUser);
        }
        // 取得轮次后立即进入 try/finally（R8）：上下文读取、建 trace、SSE meta 任何一步失败
        // 都要走到轮次收尾，不留 RUNNING 行和执行占用卡死会话。
        try {
            resolveContext(exec, question, listener, isRealUser);
            ExecutionResult result = executeTurn(exec, question, listener);
            return completeTurn(exec, question, result, listener);
        } catch (Exception e) {
            failTurn(exec, question, e, listener);
            throw e;
        } finally {
            // 兜底释放执行占用（如 catch 里收尾也失败、或抛出的是 Error），避免会话被永久锁死
            if (!exec.turnFinished) {
                failTurnQuietly(exec, question, "请求未完成", java.util.List.of());
            }
        }
    }

    /**
     * 准备阶段：参数校验、固定时间基准、落定对话、校验澄清表单、登记轮次（短事务占用执行权）。
     * 同会话已有未过期执行中请求时，返回的 exec.turn 为 null，由调用方走忙碌短路；其余字段已就位。
     */
    private TurnExecution prepareTurn(String conversationId, String question, String visitorId, String timezone,
                                      String parentTurnId, java.util.Map<String, ?> answers) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        var exec = new TurnExecution();
        exec.visitorId = visitorId;
        // 请求开始即固定时间基准：同一次问答里所有工具节点共用同一个“现在”，
        // 不允许各环节各自读取时钟（跨午夜会导致“昨天”在不同节点变成不同日期）
        exec.requestContext = new RequestContext(parseZone(timezone), clock.instant());
        // 落定对话：conversationId 为空则新建对话；不为空则沿用（多轮追问的载体）。
        // 对话只保留最近几个 Query，不做长期记忆。
        exec.conversationId = conversation.getOrCreateConversation(conversationId, question, visitorId);
        exec.submittedAnswers = answers == null || answers.isEmpty() ? java.util.List.<com.cexpilot.conversation.ClarificationAnswer>of()
                : com.cexpilot.conversation.ClarificationAnswer.validate(parentTurnId, conversation.history(exec.conversationId), answers);
        // 登记轮次（短事务）：原子分配 query_no + turnId、占用会话执行权、写入 RUNNING 行
        exec.turn = conversation.beginTurn(exec.conversationId, question, parentTurnId, exec.requestContext.requestTime());
        if (exec.turn == null) {
            return exec;
        }
        exec.preset = homeSuggestions == null || !exec.submittedAnswers.isEmpty()
                ? null : homeSuggestions.fixedReply(question).orElse(null);
        exec.startMillis = System.currentTimeMillis();
        exec.traceId = UUID.randomUUID().toString();
        return exec;
    }

    /**
     * 固定回复直达路径：不调 LLM、不规划、不取数，直接返回预写答案。
     * 仍登记轮次、落最小化 trace（STATIC_REPLY）并提交，让固定回复正常进入对话历史；
     * 失败同样走 failTurn/failTurnQuietly，不留 RUNNING 轮次卡死会话（R8）。
     */
    private AskResponse presetTurn(TurnExecution exec, String question, AskStreamListener listener, boolean isRealUser) {
        try {
            // preset 不参与任务体系：taskScope 固定为 null，失败收尾走 finishQueryTurn 分支
            exec.taskScopeRef.set(null);
            traceRepository.startTrace(exec.traceId, exec.conversationId, question,
                    null, homeSuggestions.version(), exec.visitorId, buildCommit, isRealUser);
            exec.traceStarted = true;
            if (listener != null) {
                listener.onMeta(exec.conversationId, exec.traceId, exec.turn.turnId());
            }
            traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "CONTEXT_RESOLVED", "context_service",
                    null, contextResolvedSummary(exec.conversationId, exec.turn,
                            new com.cexpilot.conversation.PlanningContext("", null)),
                    null, null, null, null, null, null));

            exec.agentRef.set("query");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var payload = json.createObjectNode().put("status", "ANSWERED").put("source", "preset").put("preset_id", exec.preset.id());
            var result = new ExecutionResult(exec.preset.answer(), json.createArrayNode(), 0, 0, 0, 0, "SUCCEEDED", null)
                    .withLlmUsages(java.util.List.of()).withAgent("query", payload);
            var output = payload.deepCopy().put("answer", exec.preset.answer());
            traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "STATIC_REPLY", "preset_reply", null,
                    output.toString(), null, null, null, null, null, null));

            long durationMs = System.currentTimeMillis() - exec.startMillis;
            traceRepository.saveRouting(exec.traceId, exec.agentRef.get(), 0);
            traceRepository.finishTrace(exec.traceId, "SUCCESS", result.answer(), 0, 0, 0, 0,
                    0.0, durationMs, null, 0, 0, 0, 0, 0, 0);
            exec.traceFinished = true;
            var commit = conversation.finishQueryTurn(exec.conversationId, exec.turn, question, "SUCCEEDED",
                    result.answer(), exec.traceId, null, result.evidence(), null,
                    exec.requestContext.requestTime(), exec.requestContext.userZone(), null, payload);
            exec.turnFinished = true;
            traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "CONTEXT_COMMITTED", "ask_service",
                    null, contextCommittedSummary("SUCCEEDED", java.util.List.of(), commit.updated()),
                    null, null, null, null, null, null));

            AskResponse response = new AskResponse(exec.conversationId, exec.traceId, result.answer(),
                    result.evidence(), 0, 0, durationMs, exec.turn.turnId(), false,
                    0, 0, 0, 0, 0, 0, exec.agentRef.get(), payload, 0);
            if (listener != null) {
                try {
                    // 固定回复先持久化，再推送正文；断连不会污染原有任务或丢失固定答案。
                    listener.onDelta(result.answer());
                    listener.onDone(response);
                } catch (Exception ex) {
                    // 答案与轮次状态已提交成功，SSE 推送失败只影响本次连接，不得把轮次改写成 FAILED（R8）
                    log.warn("onDone 推送失败（答案已提交）turnId={}: {}", exec.turn.turnId(), ex.getMessage());
                }
            }
            return response;
        } catch (Exception e) {
            failTurn(exec, question, e, listener);
            throw e;
        } finally {
            if (!exec.turnFinished) {
                failTurnQuietly(exec, question, "请求未完成", java.util.List.of());
            }
        }
    }

    /**
     * 上下文与 trace 建立：渲染对话上下文与 Planner 输入、写入 RUNNING trace、回调 meta、落 CONTEXT_RESOLVED。
     */
    private void resolveContext(TurnExecution exec, String question, AskStreamListener listener, boolean isRealUser) {
        // Answer 侧用 renderRecentDialogue 纯文本（口径不变）；
        // Planner 侧用分层结构化上下文（活动 definition + 待补任务/失败尝试 + 带时间的近期问答）。
        exec.answerHistory = conversation.renderRecentDialogue(exec.conversationId);
        exec.plannerInput = contextService.buildPlanningContext(exec.conversationId)
                .withClarificationAnswers(exec.submittedAnswers);
        exec.taskScopeRef.set(com.cexpilot.conversation.TaskScope.current(exec.plannerInput.currentVersion()));

        // 开启 trace：写入一条 RUNNING 记录。traceId 是本次问答的全局标识——
        // 后续每次 LLM 调用、每次工具调用都会以它为外键落 trace_event，
        // 用户点 👎 后凭它完整回放"当时调了什么工具、拿到了什么数据"。
        // 同时记录 model、promptVersion（prompt 内容的哈希）和 buildCommit（代码版本指纹），
        // 便于排查答案可复现性。
        traceRepository.startTrace(exec.traceId, exec.conversationId, question,
                llmConfig.getNormal().getModel(),
                dispatcher == null ? runtime.promptVersion() : dispatcher.promptVersion(),
                exec.visitorId, buildCommit, isRealUser);
        exec.traceStarted = true;
        if (listener != null) {
            listener.onMeta(exec.conversationId, exec.traceId, exec.turn.turnId());
        }
        // CONTEXT_RESOLVED（阶段三观测）：Planner 输入组装完成后的上下文摘要
        traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "CONTEXT_RESOLVED", "context_service",
                null, contextResolvedSummary(exec.conversationId, exec.turn, exec.plannerInput),
                null, null, null, null, null, null));
    }

    /**
     * 执行分发：走兼容查询链（dispatcher 为 null）或生产 AgentDispatcher。
     * 不做任何持久化，成功/失败都交给调用方收尾。
     */
    private ExecutionResult executeTurn(TurnExecution exec, String question, AskStreamListener listener) {
        if (dispatcher == null) {
            return runtime.execute(question, exec.answerHistory, exec.traceId, traceSink,
                    listener == null ? null : listener::onDelta, exec.requestContext,
                    listener == null ? null : listener::onProgress, exec.compiledPlanRef::set, exec.plannerInput,
                    new com.cexpilot.evidence.EvidenceSession(exec.conversationId, exec.turn.turnId()));
        }
        return dispatcher.execute(com.cexpilot.agent.AgentExecutionRequest.builder(question, exec.traceId, traceSink)
                .history(exec.answerHistory)
                .delta(listener == null ? null : listener::onDelta)
                .requestContext(exec.requestContext)
                .progress(listener == null ? null : listener::onProgress)
                .compiledPlanSink(exec.compiledPlanRef::set)
                .planningContext(exec.plannerInput)
                .session(new com.cexpilot.evidence.EvidenceSession(exec.conversationId, exec.turn.turnId()))
                .routingState(conversation.routingState(exec.conversationId))
                .selectedAgent(exec.agentRef::set)
                .visitor(exec.visitorId)
                .build());
    }

    /**
     * 成功收尾：折算成本、落路由与 trace 终态、按路由结果提交轮次、落 CONTEXT_COMMITTED，
     * 组装响应并触发流式回调。
     */
    private AskResponse completeTurn(TurnExecution exec, String question, ExecutionResult result,
                                     AskStreamListener listener) {
        long durationMs = System.currentTimeMillis() - exec.startMillis;
        Double cost;
        if (result.llmUsages() == null) cost = computeCost(result.promptTokens(), result.completionTokens());
        else cost = com.cexpilot.llm.LlmUsage.totalCost(result.llmUsages());

        if (dispatcher != null) {
            traceRepository.saveRouting(exec.traceId, exec.agentRef.get(), result.routerLlmCalls());
        }
        traceRepository.finishTrace(exec.traceId, "FAILED".equals(result.turnStatus()) ? "FAILED" : "SUCCESS",
                result.answer(), result.steps(),
                result.toolCallCount(), result.promptTokens(), result.completionTokens(),
                cost, durationMs, null, result.reusedFactCount(),
                result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                result.calculationCount(), result.httpRequestCount());
        exec.traceFinished = true;

        String turnStatus = result.turnStatus() != null ? result.turnStatus() : "SUCCEEDED";
        com.cexpilot.metric.CompiledPlan compiledPlan = exec.compiledPlanRef.get();
        var scope = result.taskScope();
        if ("query".equals(exec.agentRef.get()) && scope == null && (compiledPlan != null || "NEEDS_CLARIFICATION".equals(turnStatus)
                || "FAILED".equals(turnStatus))) scope = exec.taskScopeRef.get();
        if (scope != null) exec.taskScopeRef.set(scope);
        var selectedContext = scope == null ? exec.requestContext : scope.executionContext(exec.requestContext);
        var commit = dispatcher == null && result.agentPayload() != null
                ? conversation.finishQueryTurn(exec.conversationId, exec.turn, question, turnStatus,
                        result.answer(), exec.traceId, compiledPlan, result.evidence(), scope,
                        selectedContext.requestTime(), selectedContext.userZone(), result.clarify(), result.agentPayload())
                : dispatcher == null ? conversation.finishTaskTurn(exec.conversationId, exec.turn, question, turnStatus,
                        result.answer(), exec.traceId, compiledPlan, result.evidence(), scope,
                        selectedContext.requestTime(), selectedContext.userZone(),
                        result.clarify() != null ? result.clarify() : "FAILED".equals(turnStatus) ? result.answer() : null)
                : conversation.finishRoutedTurn(exec.conversationId, exec.turn, question, turnStatus, result.answer(), exec.traceId,
                        compiledPlan, result.evidence(), scope, selectedContext.requestTime(), selectedContext.userZone(),
                        result.clarify() != null ? result.clarify() : "FAILED".equals(turnStatus) ? result.answer() : null,
                        exec.agentRef.get(), result.agentPayload());
        boolean taskUpdated = commit.updated();
        exec.turnFinished = true;
        // CONTEXT_COMMITTED（阶段三观测）：轮次状态提交后的最终状态摘要（task_updated 为真实写入结果）
        traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "CONTEXT_COMMITTED", "ask_service",
                null, contextCommittedSummary(turnStatus, result.savedFactIds(), taskUpdated),
                null, null, null, null, null, null));

        // evidence（工具产出的事实集）随答案一起返回，前端可展示"答案引用了哪些数据"。
        AskResponse response = new AskResponse(exec.conversationId, exec.traceId, result.answer(),
                result.evidence(), result.toolCallCount(), result.steps(), durationMs, exec.turn.turnId(), false,
                result.plannerLlmCalls(), result.answerLlmCalls(), result.metricFetchCount(),
                result.calculationCount(), result.httpRequestCount(), result.reusedFactCount(),
                exec.agentRef.get(), result.agentPayload(), result.routerLlmCalls());
        if (listener != null) {
            try {
                listener.onDone(response);
            } catch (Exception ex) {
                // 答案与轮次状态已提交成功，SSE 推送失败只影响本次连接，不得把轮次改写成 FAILED（R8）
                log.warn("onDone 推送失败（答案已提交）turnId={}: {}", exec.turn.turnId(), ex.getMessage());
            }
        }
        return response;
    }

    /**
     * 失败收尾：失败的现场数据正是后续定位问题、固化评测 case 的原料——同样要落 trace 与轮次。
     * ExecutionFailure 携带已发生的调用计数与已保存事实，失败不统计归零。本方法只收尾，不重抛。
     */
    private void failTurn(TurnExecution exec, String question, Exception e, AskStreamListener listener) {
        long durationMs = System.currentTimeMillis() - exec.startMillis;
        Exception failure = e;
        com.cexpilot.runtime.TurnCounters counters = null;
        if (e instanceof com.cexpilot.runtime.ExecutionFailure f) {
            failure = f.getCause() instanceof Exception ex ? ex : e;
            counters = f.counters;
            if (f.taskScope != null) exec.taskScopeRef.set(f.taskScope);
            exec.failureEvidenceRef.set(f.evidence);
        }
        log.warn("问答失败 traceId={}: {}", exec.traceId, failure.getMessage());
        if (exec.traceStarted && !exec.traceFinished) {
            if (dispatcher != null) {
                traceRepository.saveRouting(exec.traceId, exec.agentRef.get(), counters == null ? 0 : counters.routerLlmCalls());
            }
            traceRepository.finishTrace(exec.traceId, "FAILED", null, 0, counters == null ? 0 : counters.toolCallCount(),
                    counters == null || counters.llmUsages() == null ? null
                            : com.cexpilot.llm.LlmUsage.knownPromptTokens(counters.llmUsages()),
                    counters == null || counters.llmUsages() == null ? null
                            : com.cexpilot.llm.LlmUsage.knownCompletionTokens(counters.llmUsages()),
                    counters == null || counters.llmUsages() == null ? null
                            : com.cexpilot.llm.LlmUsage.totalCost(counters.llmUsages()), durationMs, failure.getMessage(),
                    counters == null ? null : counters.reusedFactCount(),
                    counters == null ? null : counters.plannerLlmCalls(),
                    counters == null ? null : counters.answerLlmCalls(),
                    counters == null ? null : counters.metricFetchCount(),
                    counters == null ? null : counters.calculationCount(),
                    counters == null ? null : counters.httpRequestCount());
        }
        if (!exec.turnFinished) {
            exec.turnFinished = failTurnQuietly(exec, question, failure.getMessage(),
                    counters == null ? java.util.List.of() : counters.savedFactIds());
        }
        if (exec.turnFinished && listener != null) {
            try { listener.onTurnFinished(exec.turn.turnId()); }
            catch (Exception ignored) { /* 失败已提交，不因通知失败改写状态。 */ }
        }
    }

    /**
     * 失败收尾：在所选任务上保存失败版本、有效计划和事实焦点，保留最近成功状态并释放占用；
     * CONTEXT_COMMITTED 记录失败前已保存的事实（4.3）；自身再失败只记日志，不掩盖原始异常。返回是否完成收尾。
     */
    private boolean failTurnQuietly(TurnExecution exec, String question, String failureMessage,
                                    java.util.List<String> savedFactIds) {
        try {
            var scope = exec.taskScopeRef.get();
            var selected = scope == null ? exec.requestContext : scope.executionContext(exec.requestContext);
            if (scope == null) conversation.finishQueryTurn(exec.conversationId, exec.turn, question, "FAILED", null,
                    exec.traceId, null, exec.failureEvidenceRef.get(), null,
                    selected.requestTime(), selected.userZone(), failureMessage, null);
            else if (dispatcher == null) conversation.finishTaskTurn(exec.conversationId, exec.turn, question, "FAILED",
                    null, exec.traceId, exec.compiledPlanRef.get(), exec.failureEvidenceRef.get(), scope,
                    selected.requestTime(), selected.userZone(), failureMessage);
            else conversation.finishRoutedTurn(exec.conversationId, exec.turn, question, "FAILED", null, exec.traceId,
                    exec.compiledPlanRef.get(), exec.failureEvidenceRef.get(), scope,
                    exec.requestContext.requestTime(), exec.requestContext.userZone(), failureMessage, exec.agentRef.get(), null);
            if (exec.traceStarted) {
                traceSink.record(new com.cexpilot.runtime.TraceEvent(exec.traceId, "CONTEXT_COMMITTED", "ask_service",
                        null, contextCommittedSummary("FAILED", savedFactIds, false),
                        null, null, null, null, null, null));
            }
            return true;
        } catch (Exception ex) {
            log.warn("轮次失败收尾失败 turnId={}: {}", exec.turn.turnId(), ex.getMessage());
            return false;
        }
    }

    /** CONTEXT_RESOLVED 的 output_json：轮次序号、活动 definition 摘要（targets 关键条件）、可引用事实候选数、文本历史条数。 */
    private static String contextResolvedSummary(String conversationId, ConversationService.TurnHandle turn,
                                                 com.cexpilot.conversation.PlanningContext plannerInput) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var out = mapper.createObjectNode();
        out.put("conversation_id", conversationId);
        out.put("query_no", turn.queryNo());
        com.cexpilot.conversation.QueryDefinition definition = plannerInput == null ? null : plannerInput.activeDefinition();
        if (plannerInput == null || plannerInput.currentVersion() == null) {
            out.putNull("active_task_version");
        } else {
            var taskNode = out.putObject("active_task_version");
            taskNode.put("task_id", plannerInput.currentVersion().taskId());
            taskNode.put("version", plannerInput.currentVersion().version());
            taskNode.put("source_turn_id", plannerInput.currentVersion().turnId());
            var targets = taskNode.putArray("targets");
            for (com.cexpilot.metric.EffectiveTarget t : definition == null ? java.util.List.<com.cexpilot.metric.EffectiveTarget>of() : definition.targets()) {
                var target = targets.addObject();
                target.put("group", t.groupId());
                target.put("exchange", t.exchange());
                target.put("metric", t.metric());
                target.put("shape", t.queryShape());
                if (t.instrument() != null) {
                    target.put("base", t.instrument().path("base").asText(null));
                }
                if (t.timeExpression() != null) {
                    target.put("time", t.timeExpression().toString());
                }
            }
            taskNode.put("has_pending_clarification",
                    "NEEDS_CLARIFICATION".equals(plannerInput.currentVersion().status()));
        }
        out.put("evidence_candidates", plannerInput == null ? 0 : plannerInput.evidence().size());
        out.put("text_history_count", plannerInput == null ? 0 : plannerInput.textHistoryCount());
        return out.toString();
    }

    /** CONTEXT_COMMITTED 的 output_json：轮次终态、本轮新保存的 factId 列表、活动 definition 是否更新。 */
    private static String contextCommittedSummary(String turnStatus, java.util.List<String> savedFactIds,
                                                  boolean taskUpdated) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var out = mapper.createObjectNode();
        out.put("status", turnStatus);
        var facts = out.putArray("saved_fact_ids");
        savedFactIds.forEach(facts::add);
        out.put("task_updated", taskUpdated);
        return out.toString();
    }

    /** 按普通模型的每千 token 单价折算成本；单价为 0 时成本为 0，不影响主流程。 */
    private double computeCost(int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * llmConfig.getNormal().getPriceInputPer1k()
                + completionTokens / 1000.0 * llmConfig.getNormal().getPriceOutputPer1k();
    }

    private static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("非法的 timezone（需为 IANA 时区，如 Asia/Shanghai）: " + timezone);
        }
    }
}
