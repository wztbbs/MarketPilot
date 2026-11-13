package com.cexpilot.business.cex.query;

import com.cexpilot.platform.agent.ClarificationField;
import com.cexpilot.platform.engine.dag.DagPlan;
import com.cexpilot.platform.engine.dag.PlanValidator;

import com.cexpilot.platform.engine.dag.DagConfig;
import com.cexpilot.business.cex.evidence.EvidenceBindingContext;
import com.cexpilot.business.cex.metric.MetricCatalog;
import com.cexpilot.business.cex.conversation.TaskVersion;
import com.cexpilot.business.cex.conversation.TaskScope;
import com.cexpilot.business.cex.metric.MetricPlanCompiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import com.cexpilot.platform.llm.LlmConfig;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.LlmClient;
import com.cexpilot.platform.llm.LlmJson;
import com.cexpilot.platform.llm.LlmTraceSerializer;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 单次 LLM 生成紧凑指标计划；确定性编译为 Tool DAG，再校验和有限次数修复。 */
@Component
public class DagPlanner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DagPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROMPT_NAME = "dag_planner";
    private static final String TRACE_NAME = "dag_planner";
    /** 透传 reply 的长度上限：合法追问/边界话术都很短，超长基本是把推理过程倒进了 reply。 */
    private static final int REPLY_MAX_LENGTH = 100;

    private final LlmClient llm;
    private final LlmClient repairLlm;
    private final ToolRegistry registry;
    private final LlmConfig llmConfig;
    private final DagConfig dagConfig;
    private final PromptStore prompts;
    private final PlanValidator validator;
    private final MetricCatalog catalog;
    private final MetricPlanCompiler compiler;
    private final InheritanceValidator inheritanceValidator = new InheritanceValidator();

    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, com.cexpilot.business.cex.metric.MetricProviderRegistry metricProviders) {
        this(llm, registry, llmConfig, dagConfig, prompts, validator,
                new MetricCatalog(new DefaultResourceLoader()), metricProviders);
    }

    public DagPlanner(LlmClient llm, ToolRegistry registry,
                      LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
                      PlanValidator validator, MetricCatalog catalog,
                      com.cexpilot.business.cex.metric.MetricProviderRegistry metricProviders) {
        // 兼容测试/显式单客户端调用；生产由下面的双客户端构造注入。
        this(llm, llm, registry, llmConfig, dagConfig, prompts, validator, catalog, metricProviders);
    }

    @Autowired
    public DagPlanner(
            @org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.platform.llm.LlmClient.NORMAL_LLM_CLIENT) LlmClient llm,
            @org.springframework.beans.factory.annotation.Qualifier(com.cexpilot.platform.llm.LlmClient.FLAGSHIP_LLM_CLIENT) LlmClient repairLlm,
            ToolRegistry registry, LlmConfig llmConfig, DagConfig dagConfig, PromptStore prompts,
            PlanValidator validator, MetricCatalog catalog,
            com.cexpilot.business.cex.metric.MetricProviderRegistry metricProviders) {
        this.catalog = catalog;
        this.compiler = new MetricPlanCompiler(catalog, registry, metricProviders);
        this.llm = llm;
        this.repairLlm = repairLlm;
        this.registry = registry;
        this.llmConfig = llmConfig;
        this.dagConfig = dagConfig;
        this.prompts = prompts;
        this.validator = validator;
    }

    /**
     * 规划结果。inDomain=false 时 reply 为边界话术；inDomain=true 且 plan 为空表示
     * 模型判断工具不足以回答（reply 说明缺口）或 repair 耗尽（lastError 非空）。
     * plan 持有完整编译产物 CompiledPlan（含 LogicalPlan 与逻辑→物理映射），执行用其中的 dagPlan。
     * needsClarification=true 时不执行 DAG，编排层在所选任务上追加澄清版本。
     * bindings 为服务端生成的执行期绑定（阶段二 REUSE_FACT）；无证据上下文或未绑定时为 null。
     * llmCalls 为本轮 planner 阶段真实 LLM 调用次数（含修复重试，阶段三计数拆分）。
     */
    public record PlanOutcome(boolean inDomain, String reply,
                              Optional<com.cexpilot.business.cex.metric.CompiledPlan> plan, int promptTokens, int completionTokens,
                              String lastError, boolean needsClarification,
                              com.cexpilot.business.cex.metric.ExecutionBindings bindings, int llmCalls, TaskScope taskScope,
                              List<LlmUsage> llmUsages, List<ClarificationField> clarificationFields, boolean adaptive) {
        public PlanOutcome(boolean inDomain,String reply,Optional<com.cexpilot.business.cex.metric.CompiledPlan> plan,int promptTokens,int completionTokens,
                String lastError,boolean needsClarification,com.cexpilot.business.cex.metric.ExecutionBindings bindings,int llmCalls,TaskScope taskScope,
                List<LlmUsage> llmUsages,List<ClarificationField> fields) {
            this(inDomain,reply,plan,promptTokens,completionTokens,lastError,needsClarification,bindings,llmCalls,taskScope,llmUsages,fields,false);
        }
        public PlanOutcome withAdaptive(boolean enabled) {
            return new PlanOutcome(inDomain,reply,plan,promptTokens,completionTokens,lastError,needsClarification,bindings,llmCalls,taskScope,llmUsages,clarificationFields,enabled);
        }
        public PlanOutcome {
            clarificationFields = clarificationFields == null ? List.of() : List.copyOf(clarificationFields);
            llmUsages = llmUsages == null ? List.of() : List.copyOf(llmUsages);
        }
        public PlanOutcome(boolean inDomain, String reply, Optional<com.cexpilot.business.cex.metric.CompiledPlan> plan,
                int promptTokens, int completionTokens, String lastError, boolean needsClarification,
                com.cexpilot.business.cex.metric.ExecutionBindings bindings, int llmCalls, TaskScope taskScope, List<LlmUsage> usages) {
            this(inDomain, reply, plan, promptTokens, completionTokens, lastError, needsClarification,
                    bindings, llmCalls, taskScope, usages, List.of());
        }
        public PlanOutcome(boolean inDomain, String reply, Optional<com.cexpilot.business.cex.metric.CompiledPlan> plan,
                int promptTokens, int completionTokens, String lastError, boolean needsClarification,
                com.cexpilot.business.cex.metric.ExecutionBindings bindings, int llmCalls, TaskScope taskScope) {
            this(inDomain, reply, plan, promptTokens, completionTokens, lastError, needsClarification,
                    bindings, llmCalls, taskScope, List.of());
        }
        public PlanOutcome(boolean inDomain, String reply, Optional<com.cexpilot.business.cex.metric.CompiledPlan> plan,
                int promptTokens, int completionTokens, String lastError, boolean needsClarification,
                com.cexpilot.business.cex.metric.ExecutionBindings bindings, int llmCalls) {
            this(inDomain, reply, plan, promptTokens, completionTokens, lastError, needsClarification,
                    bindings, llmCalls, null);
        }
    }

    public PlanOutcome plan(String question, String plannerContext, String traceId, TraceSink sink) {
        return plan(question, plannerContext, traceId, sink, null, null);
    }

    public PlanOutcome plan(String question, String plannerContext, String traceId, TraceSink sink,
            TaskVersion currentVersion, com.cexpilot.platform.execution.RequestContext requestContext) {
        return plan(question, plannerContext, traceId, sink, currentVersion, requestContext, null, List.of());
    }
    public PlanOutcome plan(String question, String plannerContext, String traceId, TraceSink sink,
            TaskVersion currentVersion, com.cexpilot.platform.execution.RequestContext requestContext,
            EvidenceBindingContext evidenceContext) {
        return plan(question, plannerContext, traceId, sink, currentVersion, requestContext, evidenceContext, List.of());
    }
    public PlanOutcome plan(String question, String plannerContext, String traceId, TraceSink sink,
            TaskVersion currentVersion, com.cexpilot.platform.execution.RequestContext requestContext,
            EvidenceBindingContext evidenceContext, List<com.cexpilot.business.cex.conversation.TaskVersionCandidate> taskIndex) {
        return plan(question, plannerContext, traceId, sink, currentVersion, requestContext, evidenceContext, taskIndex, false, null);
    }
    /** 原始输入用于消息呈现；question 是本轮编译校验使用的独立查询目标。 */
    public PlanOutcome plan(String question, String plannerContext, String traceId, TraceSink sink,
            TaskVersion currentVersion, com.cexpilot.platform.execution.RequestContext requestContext,
            EvidenceBindingContext evidenceContext, List<com.cexpilot.business.cex.conversation.TaskVersionCandidate> taskIndex,
            boolean clarificationSubmission, String originalQuestion) {
        TaskScope lastScope = TaskScope.current(currentVersion);
        int maxToolCalls = llmConfig.getMaxToolCalls();
        String systemPrompt = systemPrompt(plannerContext);
        var requestBudget=com.cexpilot.platform.execution.QueryBudget.current();
        if(requestBudget!=null && !requestBudget.config().isAdaptiveEnabled())systemPrompt+="\n本次 adaptive 已关闭，必须使用 single 并说明无法完成的结果驱动子问题。";
        JsonNode responseFormat = plannerResponseFormat();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(plannerUserContent(question,originalQuestion)));

        int promptTokens = 0;
        int completionTokens = 0;
        int llmCalls = 0;
        List<LlmUsage> usages = new ArrayList<>();
        String lastError = null;
        boolean bindingRetried = false;
        boolean repairingClarification = false;
        boolean clarificationFailed = false;
        // R4：首次绑定失败后，逐项保留历史输入的条件及计算用途；修复改名不影响约束。
        PinnedRequirements pinnedRequirements = new PinnedRequirements();
        int attempts = dagConfig.getPlannerMaxRetries() + 1;
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                // 4.3 口径：按尝试次数计——传输失败的调用（欠费/超时）也是已发生的成本
                var budget=com.cexpilot.platform.execution.QueryBudget.current();
                if(budget!=null && com.cexpilot.platform.run.RunScope.current()==null)budget.beforeModel(true);
                llmCalls++;
                ChatResponse response;
                try {
                    response = callLlm(messages, traceId, sink, attempt, responseFormat, usages);
                } catch (Exception e) {
                    recordAttempt(traceId, sink, attempt, lastError, "FAILED", "NOT_RUN", "CALL_FAILED",
                            e.getMessage(), null);
                    throw e;
                }
                promptTokens += response.promptTokens() == null ? 0 : response.promptTokens();
                completionTokens += response.completionTokens() == null ? 0 : response.completionTokens();

                PlanDecision decision = repairingClarification
                        ? evaluateClarificationRepair(response.content(), traceId, sink).withScope(lastScope)
                        : evaluateResponse(response.content(), maxToolCalls, traceId, sink,
                                requestContext, evidenceContext, attempt, pinnedRequirements, taskIndex, currentVersion, question, clarificationSubmission);
                if (clarificationSubmission && currentVersion != null) {
                    decision = withSubmittedValues(decision, currentVersion.clarificationAnswers());
                }
                if (decision.taskScope() != null) lastScope = decision.taskScope();
                if (decision.error() == null) {
                    if (!pinnedRequirements.isEmpty() && decision.plan() != null
                            && !pinnedRequirements.preservedBy(decision.plan())) {
                        sink.record(new TraceEvent(traceId, "PLAN", "dag_planner", null,
                                MAPPER.createObjectNode().put("note", "pinned_downgrade_blocked").toString(),
                                null, null, null, null, null, "修复轮改变或删除了必要的历史输入，按原快照不可恢复处理"));
                        recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED", "NEEDS_CLARIFICATION",
                                "修复轮改变或删除了必要的历史输入，按原快照不可恢复处理", null);
                        return new PlanOutcome(true, BINDING_UNAVAILABLE_CLARIFY, Optional.empty(),
                                promptTokens, completionTokens, null, true, null, llmCalls, lastScope, usages);
                    }
                    String result = decision.plan() != null ? "EXECUTABLE"
                            : decision.needsClarification() ? "NEEDS_CLARIFICATION"
                            : decision.inDomain() ? "NO_PLAN" : "OUT_OF_DOMAIN";
                    recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED",
                            decision.plan() == null ? "NOT_APPLICABLE" : "PASSED", result, null, decision.plan());
                    return decision.toOutcome(promptTokens, completionTokens, llmCalls, usages)
                            .withAdaptive("adaptive".equals(LlmJson.parse(response.content()).path("execution_mode").asText()));
                }
                if (repairingClarification) {
                    // 专项修复只有一次；即使配置允许更多 planner 重试，也不能循环补表单。
                    recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED", "REPAIR_EXHAUSTED",
                            decision.error(), null);
                    lastError = decision.error();
                    break;
                }
                if (decision.needsClarification()) {
                    clarificationFailed = true;
                    recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED",
                            attempt == attempts ? "REPAIR_EXHAUSTED" : "REPAIR_REQUIRED", decision.error(), null);
                    lastError = decision.error();
                    if (attempt == attempts) break;
                    repairingClarification = true;
                    messages = clarificationRepairMessages(question, originalQuestion, plannerContext, response.content(), lastError);
                    responseFormat = clarificationRepairFormat(responseFormat);
                    continue;
                }
                if (decision.bindingFailure()) {
                    // 绑定失败只修复一次；仍不行则澄清"原快照不可恢复"，禁止重查当前值冒充（F21）
                    if (bindingRetried) {
                        recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED", "NEEDS_CLARIFICATION",
                                decision.error(), null);
                        return new PlanOutcome(true, BINDING_UNAVAILABLE_CLARIFY, Optional.empty(),
                                promptTokens, completionTokens, null, true, null, llmCalls, lastScope, usages);
                    }
                    bindingRetried = true;
                }
                recordAttempt(traceId, sink, attempt, lastError, "SUCCEEDED", "FAILED",
                        attempt == attempts ? "REPAIR_EXHAUSTED" : "REPAIR_REQUIRED",
                        decision.error(), null);
                lastError = decision.error();
                appendRepair(messages, response.content(), lastError);
            }
        } catch (Exception e) {
            // 4.3：planner 阶段失败（LLM 欠费/超时等）也要把已发生的 LLM 调用次数带出——
            // planner 是最常见的失败源，裸抛会让失败 trace 的计数归零
            throw new PlanningFailure(e, llmCalls, lastScope, usages);
        }
        // repair 耗尽：runtime 直接返回固定失败话术，禁止继续生成无事实答案。
        String failureReply = clarificationFailed
                ? "澄清表单生成失败，本次未执行查询。请在输入框直接补充具体查询要求，或稍后重试。" : null;
        return new PlanOutcome(true, failureReply,
                Optional.empty(), promptTokens, completionTokens, lastError, false, null, llmCalls, lastScope, usages);
    }

    /** planner 阶段失败（LLM 传输错误等）携带已发生的调用次数（4.3），由 DagRuntime 转成 ExecutionFailure。 */
    public static final class PlanningFailure extends RuntimeException {

        public final int llmCalls;
        public final TaskScope taskScope;
        public final List<LlmUsage> llmUsages;

        public PlanningFailure(Throwable cause, int llmCalls) { this(cause, llmCalls, null); }
        public PlanningFailure(Throwable cause, int llmCalls, TaskScope taskScope) {
            this(cause, llmCalls, taskScope, List.of());
        }
        public PlanningFailure(Throwable cause, int llmCalls, TaskScope taskScope, List<LlmUsage> usages) {
            super(cause.getMessage(), cause);
            this.llmCalls = llmCalls;
            this.taskScope = taskScope;
            this.llmUsages = List.copyOf(usages);
        }
    }

    /** 绑定修复仍失败时的澄清话术：说明原快照不可恢复，把"是否按最新数据重查"的选择权交还用户。 */
    static final String BINDING_UNAVAILABLE_CLARIFY =
            "您想引用的那条历史数据快照已不可恢复（可能已过期、被清理或查询口径已变化），无法按原样复用；"
                    + "请确认是否按当前最新数据重新查询。";

    /** 单轮判断不负责 token 累计；error 非空时由 plan 统一追加修复消息并重试。 */
    private record PlanDecision(boolean inDomain, String reply, com.cexpilot.business.cex.metric.CompiledPlan plan,
                                String error, boolean needsClarification,
                                com.cexpilot.business.cex.metric.ExecutionBindings bindings, boolean bindingFailure, TaskScope taskScope,
                                List<ClarificationField> clarificationFields) {
        private PlanDecision(boolean inDomain, String reply, com.cexpilot.business.cex.metric.CompiledPlan plan,
                String error, boolean needsClarification, com.cexpilot.business.cex.metric.ExecutionBindings bindings,
                boolean bindingFailure, TaskScope taskScope) {
            this(inDomain, reply, plan, error, needsClarification, bindings, bindingFailure, taskScope, List.of());
        }
        private static PlanDecision clarification(List<ClarificationField> fields) {
            return new PlanDecision(true, ClarificationField.question(fields), null, null, true, null, false, null, fields);
        }
        private static PlanDecision accepted(boolean inDomain, String reply, com.cexpilot.business.cex.metric.CompiledPlan plan) {
            return new PlanDecision(inDomain, reply, plan, null, false, null, false, null);
        }

        private static PlanDecision accepted(boolean inDomain, String reply, com.cexpilot.business.cex.metric.CompiledPlan plan,
                                             com.cexpilot.business.cex.metric.ExecutionBindings bindings) {
            return new PlanDecision(inDomain, reply, plan, null, false, bindings, false, null);
        }

        private static PlanDecision clarification(String reply) {
            return new PlanDecision(true, reply, null, null, true, null, false, null);
        }

        private static PlanDecision bindingError(String error) {
            return new PlanDecision(true, null, null, error, false, null, true, null);
        }

        private PlanDecision withScope(TaskScope scope) {
            return new PlanDecision(inDomain, reply, plan, error, needsClarification, bindings, bindingFailure, scope, clarificationFields);
        }

        private PlanOutcome toOutcome(int promptTokens, int completionTokens, int llmCalls, List<LlmUsage> usages) {
            return new PlanOutcome(inDomain, reply, Optional.ofNullable(plan),
                    promptTokens, completionTokens, error, needsClarification, bindings, llmCalls, taskScope, usages, clarificationFields);
        }
    }

    /** 仅回显用户填写值，不按 key 删除澄清项：已有值也可能需要纠正或确认。 */
    private PlanDecision withSubmittedValues(PlanDecision decision,
            List<com.cexpilot.business.cex.conversation.ClarificationAnswer> supplied) {
        if (!decision.needsClarification() || decision.error() != null || decision.clarificationFields().isEmpty()) return decision;
        var fields = decision.clarificationFields().stream().map(field -> supplied.stream()
                .filter(a -> a.key().equals(field.key())).findFirst()
                .map(a -> field.withValue(a.value())).orElse(field)).toList();
        return PlanDecision.clarification(fields).withScope(decision.taskScope());
    }

    private PlanDecision clarificationError(String traceId, TraceSink sink, String output, String error) {
        log.warn("澄清表单校验失败 traceId={} error={}", traceId, error);
        sink.record(new TraceEvent(traceId, "CLARIFY_INVALID", TRACE_NAME, null, output,
                null, null, null, null, null, error));
        return new PlanDecision(true, null, null, error, true, null, false, null);
    }

    private PlanDecision evaluateClarificationRepair(String content, String traceId, TraceSink sink) {
        try {
            JsonNode parsed = LlmJson.parse(content);
            List<ClarificationField> fields = ClarificationField.parse(parsed.path("clarify"));
            if (fields.isEmpty()) throw new IllegalArgumentException("澄清表单修复仍未返回任何有效字段");
            // 专项响应只接受字段列表；不能借修复文案改任务、执行计划或绕回取数。
            recordPlanDropped(traceId, sink, parsed, "澄清表单修复不得携带执行计划，服务端已丢弃");
            sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
            return PlanDecision.clarification(fields);
        } catch (Exception e) {
            return clarificationError(traceId, sink, content, "澄清表单修复失败: " + e.getMessage());
        }
    }

    private static String plannerUserContent(String question,String originalQuestion) {
        if(originalQuestion==null)return question;
        return "【本轮完整查询】（规划目标）\n"+question
                +"\n\n【本轮原始输入】（仅用于任务延续和本轮参数操作识别；规划目标以完整查询为准）\n"+originalQuestion;
    }

    private List<ChatMessage> clarificationRepairMessages(String question, String originalQuestion, String context, String output, String error) {
        ObjectNode input = MAPPER.createObjectNode().put("question", question)
                .put("conversation_context", context == null ? "" : context)
                .put("invalid_output", output).put("validation_error", error);
        if(originalQuestion!=null)input.put("original_question",originalQuestion);
        return new ArrayList<>(List.of(ChatMessage.system(prompts.render("clarification_repair", Map.of(
                "concepts", catalog.concepts(), "metrics", catalog.describeMetrics()))), ChatMessage.user(input.toString())));
    }

    private JsonNode clarificationRepairFormat(JsonNode original) {
        if (original == null || !"json_schema".equals(original.path("type").asText())) return original;
        ObjectNode format = MAPPER.createObjectNode().put("type", "json_schema");
        ObjectNode contract = format.putObject("json_schema").put("name", "clarification_fields").put("strict", true);
        ObjectNode schema = contract.putObject("schema").put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("clarify");
        ObjectNode fields = ((ObjectNode) original.at("/json_schema/schema/properties/clarify")).deepCopy();
        fields.put("minItems", 1);
        fields.put("description", "必须给出至少一个具体待补条件，不允许空数组");
        schema.putObject("properties").set("clarify", fields);
        return format;
    }

    private PlanDecision evaluateResponse(String content, int maxToolCalls,
                                          String traceId, TraceSink sink,
                                          com.cexpilot.platform.execution.RequestContext requestContext,
                                          EvidenceBindingContext evidenceContext,
                                          int attempt, PinnedRequirements pinnedRequirements,
                                          List<com.cexpilot.business.cex.conversation.TaskVersionCandidate> taskIndex, TaskVersion currentVersion, String question, boolean clarificationSubmission) {
        JsonNode parsed;
        try {
            parsed = LlmJson.parse(content);
        } catch (Exception e) {
            return repairDecision(traceId, sink, planOutput(content), "输出 JSON 解析失败: " + e.getMessage());
        }

        if (!parsed.isObject() || !parsed.path("in_domain").isBoolean()) {
            String error = "输出缺少信封：必须输出完整 JSON 对象 {\"in_domain\", \"reply\", \"plan\"}，"
                    + "不要直接输出指标数组或旧版 nodes";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if(parsed.has("execution_mode") && !Set.of("single","adaptive").contains(parsed.path("execution_mode").asText()))
            return repairDecision(traceId,sink,parsed.toString(),"execution_mode 仅支持 single/adaptive");
        if (!parsed.path("in_domain").asBoolean(false)) {
            recordPlanDropped(traceId, sink, parsed, "in_domain=false 时 plan 必须为空，服务端已丢弃");
            sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
            return PlanDecision.accepted(false, textOrNull(parsed.path("reply")), null);
        }
        return evaluateInDomainResponse(parsed, maxToolCalls, traceId, sink, requestContext,
                evidenceContext, attempt, pinnedRequirements, taskIndex, currentVersion, question, clarificationSubmission);
    }

    private PlanDecision evaluateInDomainResponse(JsonNode parsed, int maxToolCalls,
                                                  String traceId, TraceSink sink,
                                                  com.cexpilot.platform.execution.RequestContext requestContext,
                                                  EvidenceBindingContext evidenceContext,
                                                  int attempt, PinnedRequirements pinnedRequirements,
                                                  List<com.cexpilot.business.cex.conversation.TaskVersionCandidate> taskIndex, TaskVersion currentVersion, String question, boolean clarificationSubmission) {
        TaskScope selected;
        try { selected = clarificationSubmission ? TaskScope.current(currentVersion) : selectTask(parsed, currentVersion, taskIndex); }
        catch (IllegalArgumentException e) {
            return PlanDecision.clarification(e.getMessage()).withScope(
                    currentVersion != null && !currentVersion.dialogue().isEmpty()
                            ? TaskScope.current(currentVersion) : new TaskScope(TaskScope.Action.NEW, null));
        }
        ((ObjectNode) parsed).put("task_action", selected.action().name());
        if (selected.action() != TaskScope.Action.RESUME) ((ObjectNode) parsed).remove("candidate_ref");
        String reply = textOrNull(parsed.path("reply"));
        // 有效结构化列表本身就是澄清决策，兼容模型漏写 needs_clarification；绝不执行附带计划。
        JsonNode clarifyNode = parsed.path("clarify");
        if (parsed.path("needs_clarification").asBoolean(false) || clarifyNode.isArray() && !clarifyNode.isEmpty()) {
            recordPlanDropped(traceId, sink, parsed, "needs_clarification=true 时 plan 必须为空，服务端已丢弃");
            try {
                List<ClarificationField> fields = ClarificationField.parse(clarifyNode);
                if (!fields.isEmpty()) {
                    sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                    return PlanDecision.clarification(fields).withScope(selected);
                }
            } catch (IllegalArgumentException e) {
                return clarificationError(traceId, sink, parsed.toString(), e.getMessage()).withScope(selected);
            }
            // 兼容有文字的旧协议；空数组/缺字段是必要数据缺失，不能当成可选文案吞掉。
            String legacy = textOrNull(clarifyNode);
            if (legacy != null && !legacy.isBlank()) {
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                return PlanDecision.clarification(legacy).withScope(selected);
            }
            return clarificationError(traceId, sink, parsed.toString(),
                    "needs_clarification=true 但 clarify 未提供任何有效字段；必须输出至少一个待补条件").withScope(selected);
        }
        JsonNode planNode = parsed.path("plan");
        if (!planNode.isObject()) {
            return evaluateReplyWithoutPlan(parsed, reply, traceId, sink).withScope(selected);
        }
        var context = requestContext == null ? null : selected.executionContext(requestContext);
        return validatePlan(parsed, reply, maxToolCalls, traceId, sink, selected.base(), context,
                evidenceContext, attempt, pinnedRequirements, question).withScope(selected);
    }

    /**
     * 终态分支（出域/澄清）附带可执行 plan 属协议违规：plan 不可执行，服务端丢弃并落
     * PLAN_DROPPED 审计事件供观测违规率（eval 按模型原始输出独立判协议，不受影响）。
     * 空计划对象（metrics 与 calculations 均空）与 plan=null 等价，不算违规、不落事件。
     */
    private static void recordPlanDropped(String traceId, TraceSink sink, JsonNode parsed, String reason) {
        JsonNode plan = parsed.path("plan");
        if (plan.isMissingNode() || plan.isNull() || !hasExecutableContent(plan)) {
            return;
        }
        sink.record(new TraceEvent(traceId, "PLAN_DROPPED", TRACE_NAME,
                MAPPER.createObjectNode().put("reason", reason).toString(),
                plan.toString(), null, null, null, null, null, null));
    }

    /** 空计划对象（metrics 与 calculations 均空）视同 null；非对象 plan 按违规内容处理。 */
    private static boolean hasExecutableContent(JsonNode plan) {
        if (!plan.isObject()) {
            return true;
        }
        return plan.path("metrics").size() > 0 || plan.path("calculations").size() > 0;
    }

    /** 模型常在引用尾部带出自然语言标点（如 task_2,），匹配前归一；归一后为空按未提供处理。 */
    private static String normalizeRef(String ref) {
        if (ref == null) {
            return null;
        }
        String trimmed = ref.replaceAll("^[\\s,，。、；;:：'\"“”‘’]+|[\\s,，。、；;:：'\"“”‘’]+$", "");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static TaskScope selectTask(JsonNode parsed, TaskVersion current,
            List<com.cexpilot.business.cex.conversation.TaskVersionCandidate> candidates) {
        String action = textOrNull(parsed.path("task_action"));
        if (action == null) action = current == null ? "NEW" : "CONTINUE";
        TaskScope.Action type;
        try { type = TaskScope.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("task_action 必须是 NEW、CONTINUE 或 RESUME"); }
        if (parsed.has("task_ref") || parsed.has("state_ref") || parsed.has("new_frame"))
            throw new IllegalArgumentException("任务选择只接受 task_action 和 candidate_ref，不接受旧引用字段");
        String ref = normalizeRef(textOrNull(parsed.path("candidate_ref")));
        if (type == TaskScope.Action.NEW) {
            if (ref != null) throw new IllegalArgumentException("新任务不能引用历史版本");
            return new TaskScope(type, null);
        }
        if (type == TaskScope.Action.CONTINUE) {
            if (ref != null) throw new IllegalArgumentException("CONTINUE 使用当前最新版本；指定版本请使用 RESUME");
            if (current == null) throw new IllegalArgumentException("没有当前任务，请使用 NEW");
            return new TaskScope(type, current);
        }
        var matched = candidates.stream().filter(t -> ref != null && t.candidateRef().equals(ref)).findFirst().orElse(null);
        if (matched == null) throw new IllegalArgumentException("无法定位 candidate_ref 指向的任务版本，请说明要回到哪个问题");
        if (current != null && !current.conversationId().equals(matched.taskVersion().conversationId()))
            throw new IllegalArgumentException("版本候选不属于当前会话");
        return new TaskScope(type, matched.taskVersion());
    }

    private PlanDecision evaluateReplyWithoutPlan(JsonNode parsed, String reply,
                                                  String traceId, TraceSink sink) {
        if (reply == null || reply.isBlank()) {
            // plan 缺失且无任何话术 = 协议违约，必须 repair。
            String error = "缺少 plan 且未给出 reply：可查询时必须输出 plan.metrics 和 plan.calculations；"
                    + "确需拒绝时必须在 reply 写明原因";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        if (isReasoningDump(reply)) {
            // 没有 plan 时，超长 reply 按协议错误修复，不能把推理原文透传成答案。
            String error = "reply 只能写一句要对用户说的简短话术（" + REPLY_MAX_LENGTH
                    + " 字以内），禁止输出推理过程；问题可查询时必须输出 plan 字段";
            return repairDecision(traceId, sink, parsed.toString(), error);
        }
        // plan=null 或空 metrics/calculations + 非空 reply：模型有意不规划，直接接受不 repair。
        sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
        return PlanDecision.accepted(true, reply, null);
    }

    private PlanDecision validatePlan(JsonNode parsed, String reply, int maxToolCalls,
                                      String traceId, TraceSink sink,
                                      TaskVersion baseVersion,
                                      com.cexpilot.platform.execution.RequestContext requestContext,
                                      EvidenceBindingContext evidenceContext,
                                      int attempt, PinnedRequirements pinnedRequirements, String question) {
        var activeDefinition = baseVersion == null ? null : baseVersion.queryDefinition();
        String error;
        try {
            com.cexpilot.business.cex.metric.CompiledPlan compiled =
                    new ConditionProvenanceResolver().resolve(compiler, parsed.path("plan"),
                            com.fasterxml.jackson.databind.node.MissingNode.getInstance(), parsed.path("condition_updates"), question, baseVersion, requestContext,
                            Math.min(dagConfig.getMaxNodes(), maxToolCalls), warning -> {
                                log.warn("规划条件标注降级 traceId={} path={} code={}: {}", traceId,
                                        warning.path(), warning.code(), warning.message());
                                sink.record(new TraceEvent(traceId, "CONDITION_WARNING", "condition_provenance",
                                        null, MAPPER.valueToTree(warning).toString(), null, null, null, null, null, null));
                            });
            DagPlan plan = compiled.dagPlan();
            if (plan.nodes().isEmpty() && reply != null && !reply.isBlank()) {
                return evaluateReplyWithoutPlan(parsed, reply, traceId, sink);
            }
            if (plan.nodes().isEmpty() && !parsed.path("needs_clarification").asBoolean(false)) {
                // P039 型：模型写了澄清话术但忘了置旗，"plan 不包含任何节点"的泛泛提示会把
                // 模型引向修 plan（永远修不好）；直接指出该补的是 needs_clarification=true。
                String clarify = textOrNull(parsed.path("clarify"));
                if (clarify != null && !clarify.isBlank()) {
                    return repairDecision(traceId, sink, parsed.toString(),
                            "输出了 clarify 但 needs_clarification 未置 true：澄清必须同时设置 "
                                    + "needs_clarification=true、plan=null（或无取数无计算的空对象）并保留 clarify；"
                                    + "若不是澄清意图，请把说明文字放入 reply");
                }
            }
            List<String> errors = validator.validate(plan, compiler.allowedTools(), maxToolCalls);
            errors.addAll(new FundingAggregationValidator().check(question, compiled));
            if (errors.isEmpty()) {
                // Changes are derived by the program for audit only, never a repair requirement.
                if (activeDefinition != null) {
                    var actualChanges = inheritanceValidator.auditChanges(activeDefinition, compiled, requestContext);
                    sink.record(new TraceEvent(traceId,"QUERY_DIFF","inheritance_validator",null,
                            MAPPER.createObjectNode().set("actual_changes",MAPPER.valueToTree(actualChanges)).toString(),
                            null,null,null,null,null,null));
                }
                sink.record(TraceEvent.plan(traceId, parsed.toString(), null));
                sink.record(new TraceEvent(traceId, "PLAN_COMPILED", "metric_compiler",
                        MAPPER.createObjectNode().put("catalog_version", catalog.version()).toString(),
                        plan.toJson().toString(), null, null, null, null, null, null));
                // 历史事实绑定（阶段二）：evidence_ref 协议校验 + 服务端实体绑定；
                // 失败走绑定修复（只一次），再失败由 plan() 转澄清，禁止重查冒充
                com.cexpilot.business.cex.metric.ExecutionBindings bindings = null;
                if (evidenceContext != null && requestContext != null) {
                    EvidenceBinder binder = new EvidenceBinder(evidenceContext.store());
                    String protocolError = binder.checkProtocol(compiled.logicalPlan(), evidenceContext.candidates());
                    if (protocolError != null) {
                        pinnedRequirements.capture(compiled);
                        sink.record(TraceEvent.plan(traceId, parsed.toString(), protocolError));
                        return PlanDecision.bindingError(protocolError);
                    }
                    EvidenceBinder.BindResult bind = binder.bind(compiled, evidenceContext.candidates(),
                            evidenceContext.conversationId(), requestContext, traceId, sink);
                    if (bind.error() != null) {
                        pinnedRequirements.capture(compiled);
                        sink.record(TraceEvent.plan(traceId, parsed.toString(), bind.error()));
                        return PlanDecision.bindingError(bind.error());
                    }
                    bindings = bind.bindings();
                    // F05/R5 后置核对：只产出非阻断提示（字面量与历史快照值一致），落审计事件供回放参考；
                    // 比较方向不再程序校验——方向由本轮语义决定，交给 prompt 规则与 Answer 来源标注
                    java.util.List<String> comparisonWarnings = binder.checkComparisonWarnings(compiled, bindings,
                            evidenceContext.candidates());
                    if (!comparisonWarnings.isEmpty()) {
                        var arr = MAPPER.createArrayNode();
                        comparisonWarnings.forEach(arr::add);
                        sink.record(new TraceEvent(traceId, "LITERAL_MATCH_NOTICE", "evidence_binder",
                                null, arr.toString(), null, null, null, null, null, null));
                    }
                }
                // plan 已合法时，超长 reply 直接丢弃，不再为 reply 重试。
                String effectiveReply = isReasoningDump(reply) ? null : reply;
                // FOLLOWUP_PLAN（阶段三观测）：计划校验通过（含绑定生成）后落计划摘要，
                // 只放各节点口径与绑定决策，不放大 payload
                sink.record(new TraceEvent(traceId, "FOLLOWUP_PLAN", "dag_planner",
                        null, followupPlanSummary(parsed, compiled, bindings),
                        null, null, null, null, null, null));
                return PlanDecision.accepted(true, effectiveReply, compiled, bindings);
            }
            error = planValidationError(compiler.logicalErrors(plan, errors));
        } catch (Exception e) {
            error = "plan 解析失败: " + e.getMessage();
        }
        return repairDecision(traceId, sink, parsed.toString(), error);
    }

    /** FOLLOWUP_PLAN 事件的 output_json：各 metric 节点口径 + 绑定决策（FETCH/REUSE_FACT+factId）+ 程序侧任务元数据。 */
    private static String followupPlanSummary(JsonNode parsed, com.cexpilot.business.cex.metric.CompiledPlan compiled,
                                              com.cexpilot.business.cex.metric.ExecutionBindings bindings) {
        ObjectNode out = MAPPER.createObjectNode();
        var metrics = out.putArray("metrics");
        for (com.cexpilot.business.cex.metric.MetricRequest m : compiled.logicalPlan().metrics()) {
            ObjectNode node = metrics.addObject();
            node.put("id", m.id());
            node.put("metric", m.metric());
            node.put("base", m.instrument() == null ? null : m.instrument().base());
            node.put("shape", String.valueOf(m.shape()));
            node.put("reference_mode", m.referenceMode() == null ? "latest" : m.referenceMode());
            if (m.evidenceRef() != null) {
                node.put("evidence_ref", m.evidenceRef());
            }
            var branches = node.putArray("branches");
            Map<String, String> byExchange = compiled.branches().getOrDefault(m.id(), Map.of());
            byExchange.forEach((exchange, physicalId) -> {
                ObjectNode branch = branches.addObject();
                branch.put("exchange", exchange);
                branch.put("node", physicalId);
                com.cexpilot.business.cex.metric.ExecutionBindings.ReuseFact reuse =
                        bindings == null ? null : bindings.reuseFor(physicalId);
                branch.put("decision", reuse == null ? "FETCH" : "REUSE_FACT");
                if (reuse != null) {
                    branch.put("fact_id", reuse.factId());
                }
            });
        }
        var calculations = out.putArray("calculations");
        for (com.cexpilot.business.cex.metric.CalculationRequest c : compiled.logicalPlan().calculations()) {
            calculations.addObject().put("id", c.id()).put("operator", c.operator());
        }
        out.put("task_action", parsed.path("task_action").asText());
        if (parsed.hasNonNull("candidate_ref")) out.set("candidate_ref", parsed.path("candidate_ref"));
        return out.toString();
    }

    private String planValidationError(List<String> errors) {
        return "plan 校验失败: " + String.join("; ", errors)
                + "；仅使用指标目录与算子；引用字段和算子输入结构必须符合对应上游输出契约，不要输出底层 Tool 节点";
    }

    private PlanDecision repairDecision(String traceId, TraceSink sink, String output, String error) {
        sink.record(TraceEvent.plan(traceId, output, error));
        return new PlanDecision(true, null, null, error, false, null, false, null);
    }

    /** 判断 reply 是否是推理 dump：合法话术很短，超长即视为协议误用。 */
    private static boolean isReasoningDump(String reply) {
        return reply != null && reply.length() > REPLY_MAX_LENGTH;
    }

    /** 按 dag.planner-response-format 构造下发给 planner 调用的 response_format；空配置 = 不下发。 */
    private JsonNode plannerResponseFormat() {
        String format = dagConfig.getPlannerResponseFormat();
        if (format == null || format.isBlank()) {
            return null;
        }
        // 容忍运维层带来的引号（.env / docker env-file 可能不剥引号）
        format = format.trim().replaceAll("^\"+|\"+$", "");
        if ("json_object".equals(format)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "json_object");
            return node;
        }
        if ("json_schema".equals(format)) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "json_schema");
            ObjectNode jsonSchema = node.putObject("json_schema");
            jsonSchema.put("name", "dag_planner_output");
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", plannerSchema());
            return node;
        }
        throw new IllegalArgumentException(
                "未知的 dag.planner-response-format: " + format + "（支持 json_object / json_schema / 留空）");
    }

    /**
     * planner 输出信封的 JSON Schema。plan/reply 允许 null：模型判断工具不足以回答时
     * 输出 plan=null；metric/operator 字段使用目录枚举，结构为 metrics/calculations。
     * 业务约束及展开后的 DAG 仍由编译器与 PlanValidator 校验。
     */
    private JsonNode plannerSchema() {
        try (var input = new org.springframework.core.io.ClassPathResource("metrics/plan-schema.json").getInputStream()) {
            ObjectNode schema = (ObjectNode) MAPPER.readTree(input);
            ((ObjectNode) schema.at("/properties/plan/properties/metrics/items/properties/metric"))
                    .set("enum", MAPPER.valueToTree(catalog.names()));
            ObjectNode calculations = (ObjectNode) schema.at("/properties/plan/properties/calculations");
            ObjectNode items = MAPPER.createObjectNode();
            var branches = items.putArray("anyOf");
            for (String name : catalog.operators().stream().sorted().toList()) {
                if (!(registry.get(name) instanceof com.cexpilot.platform.tool.calculation.CalculationTool tool)) continue;
                ObjectNode branch = branches.addObject().put("type", "object").put("additionalProperties", false);
                branch.putArray("required").add("id").add("operator").add("input");
                var properties = branch.putObject("properties");
                properties.putObject("id").put("type", "string");
                properties.putObject("operator").put("type", "string").putArray("enum").add(name);
                properties.set("input", tool.planningInputSchema());
            }
            calculations.set("items", items);
            return schema;
        } catch (Exception e) {
            throw new IllegalStateException("加载指标计划 schema 失败", e);
        }
    }

    private String systemPrompt(String plannerContext) {
        return prompts.render(PROMPT_NAME, Map.of(
                "concepts", catalog.concepts(),
                "metrics", catalog.describeMetrics(),
                "operators", catalog.describeOperators(registry),
                "max_nodes", String.valueOf(Math.min(dagConfig.getMaxNodes(), llmConfig.getMaxToolCalls())),
                "max_depth", String.valueOf(dagConfig.getMaxDepth()),
                "conversation_context", plannerContext == null ? "" : plannerContext));
    }

    /** 后续规划只观察和提出增量；不复用首轮 task_action，也不重新选择会话任务。 */
    public ChatResponse observe(String question,com.cexpilot.business.cex.metric.CompiledPlan current,JsonNode evidence,
            JsonNode status,com.cexpilot.platform.execution.RequestContext request,String traceId,TraceSink sink,List<LlmUsage> usages) {
        return observe(question,current,evidence,status,request,traceId,sink,usages,1);
    }
    public ChatResponse observe(String question,com.cexpilot.business.cex.metric.CompiledPlan current,JsonNode evidence,
            JsonNode status,com.cexpilot.platform.execution.RequestContext request,String traceId,TraceSink sink,List<LlmUsage> usages,int decision) {
        var budget=com.cexpilot.platform.execution.QueryBudget.current();
        if(budget!=null && com.cexpilot.platform.run.RunScope.current()==null)budget.beforeModel(true);
        var input=MAPPER.createObjectNode().put("question",question).put("request_time",request.requestTime().toString())
                .put("timezone",request.userZone().getId());
        input.set("plan",current.logicalPlanJson());input.set("observations",evidence);input.set("query_status",status);
        if(budget!=null)input.set("budget",budget.snapshot());
        var messages=List.of(ChatMessage.system(prompts.render("query_continue",Map.of("catalog",continuationCatalog()))),ChatMessage.user(input.toString()));
        var activeRun=com.cexpilot.platform.run.RunScope.current();
        boolean replayed=activeRun!=null && activeRun.load("query:observe:"+decision)!=null;
        long started=System.nanoTime();boolean received=false;
        var event=MAPPER.createObjectNode().put("stage","query_continue").put("model_role","normal").put("model",llmConfig.getNormal().getModel())
                .put("prompt_version",prompts.version("query_continue"));
        event.set("messages",LlmTraceSerializer.messagesToArray(messages));
        event.set("request_parameters",llm.requestParameters(null));
        try {
            var response=com.cexpilot.platform.run.DurableModelCall.invoke("query:observe:"+decision,true,()->com.cexpilot.platform.execution.QueryBudget.invoke(()->llm.chat(messages,null)));
            if(budget!=null && com.cexpilot.platform.run.RunScope.current()==null)budget.observe(response);
            usages.add(answerUsage(response));received=true;
            if(replayed)return response;
            sink.record(TraceEvent.llmCall(traceId,"query_continue",event.toString(),LlmTraceSerializer.responseToJson(response),
                    (System.nanoTime()-started)/1_000_000,response.promptTokens(),response.completionTokens(),response.ttftMs(),response.cachedTokens(),null));
            return response;
        } catch(RuntimeException error) {
            if(!received)usages.add(answerUsage(null));
            sink.record(TraceEvent.llmCall(traceId,"query_continue",event.toString(),LlmTraceSerializer.failureToJson(error),
                    (System.nanoTime()-started)/1_000_000,null,null,null,null,error.getMessage()));
            throw error;
        }
    }

    public com.cexpilot.business.cex.metric.CompiledPlan compileContinuation(com.cexpilot.business.cex.metric.CompiledPlan prior,JsonNode additions,String question,com.cexpilot.platform.execution.RequestContext request) {
        return new QueryPlanExtension(compiler,validator,dagConfig,llmConfig).append(prior,additions,question,request);
    }
    public String continuationCatalog() {
        return catalog.concepts()+"\n"+catalog.describeMetrics()+"\n"+catalog.describeOperators(registry);
    }

    /** 不含每轮历史的有效规划提示词版本，包含指标目录（含绑定）、算子配置。 */
    public String promptVersion() {
        return PromptStore.fingerprint(systemPrompt("") + catalog.version() + prompts.version("clarification_repair") + prompts.version("query_continue"));
    }

    private void appendRepair(List<ChatMessage> messages, String badOutput, String error) {
        messages.add(ChatMessage.assistant(badOutput, null));
        messages.add(ChatMessage.user("上一次输出不合法，请修正后重新输出完整 JSON，只输出 JSON。错误明细：\n" + error));
    }

    private ChatResponse callLlm(List<ChatMessage> messages, String traceId, TraceSink sink, int attempt,
                                 JsonNode responseFormat, List<LlmUsage> usages) {
        LlmClient client = attempt == 1 ? llm : repairLlm;
        // 单客户端兼容构造不能把同一个真实客户端按模型 2 的单价计费。
        boolean useRepair = attempt > 1 && repairLlm != llm;
        String role = useRepair ? "flagship" : "normal";
        LlmConfig.ModelConfig model = useRepair ? llmConfig.getFlagship() : llmConfig.getNormal();
        var activeRun=com.cexpilot.platform.run.RunScope.current();
        boolean replayed=activeRun!=null && activeRun.load("query:planner:"+attempt)!=null;
        long start = System.currentTimeMillis();
        String input = eventInput(attempt, messages, responseFormat, client, role, model.getModel());
        boolean received = false;
        try {
            ChatResponse response = com.cexpilot.platform.run.DurableModelCall.invoke("query:planner:"+attempt,true,()->com.cexpilot.platform.execution.QueryBudget.invoke(()->client.chat(messages, null, responseFormat)));
            if(com.cexpilot.platform.execution.QueryBudget.current()!=null && com.cexpilot.platform.run.RunScope.current()==null)com.cexpilot.platform.execution.QueryBudget.current().observe(response);
            LlmUsage usage = LlmUsage.of(role, model, response);
            usages.add(usage);
            received = true;
            if(replayed)return response;
            ObjectNode output = LlmTraceSerializer.responseToObject(response);
            output.put("model_role", role).put("model", model.getModel());
            output.set("estimated_cost", MAPPER.valueToTree(usage.estimatedCost()));
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, output.toString(),
                    System.currentTimeMillis() - start,
                    response.promptTokens(), response.completionTokens(),
                    response.ttftMs(), response.cachedTokens(), null));
            return response;
        } catch (Exception e) {
            if (!received) usages.add(LlmUsage.of(role, model, null));
            sink.record(TraceEvent.llmCall(traceId, TRACE_NAME,
                    input, LlmTraceSerializer.failureToJson(e),
                    System.currentTimeMillis() - start, null, null, null, null, e.getMessage()));
            throw e;
        }
    }

    /** Answer 始终使用 normal；与 Planner 共用同一份模型单价配置。 */
    public LlmUsage answerUsage(ChatResponse response) {
        return LlmUsage.of("normal", llmConfig.getNormal(), response);
    }

    /** 最终对账/绑定/历史输入保护均结束后记录结果，不能把提前产生的 PLAN_COMPILED 当作可执行。 */
    private void recordAttempt(String traceId, TraceSink sink, int attempt, String retryReason,
            String callStatus, String validationStatus, String result, String error,
            com.cexpilot.business.cex.metric.CompiledPlan plan) {
        LlmClient client = attempt == 1 ? llm : repairLlm;
        boolean useRepair = attempt > 1 && repairLlm != llm;
        var model = useRepair ? llmConfig.getFlagship() : llmConfig.getNormal();
        ObjectNode input = MAPPER.createObjectNode().put("attempt", attempt)
                .put("model_role", useRepair ? "flagship" : "normal")
                .put("model", client.requestParameters(null).path("model").asText(model.getModel()));
        input.put("retry_reason", retryReason);
        ObjectNode output = MAPPER.createObjectNode().put("call_status", callStatus)
                .put("validation_status", validationStatus).put("result", result);
        output.put("call_error", "FAILED".equals(callStatus) ? error : null);
        output.put("validation_error", "FAILED".equals(validationStatus) ? error : null);
        if (plan != null) {
            output.set("logical_plan", plan.logicalPlanJson());
            output.set("compiled_plan", plan.dagPlan().toJson());
        }
        sink.record(new TraceEvent(traceId, "PLANNER_ATTEMPT", TRACE_NAME, input.toString(), output.toString(),
                null, null, null, null, null, error));
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private String eventInput(int attempt, List<ChatMessage> messages, JsonNode responseFormat,
                              LlmClient client, String role, String model) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("stage", TRACE_NAME);
        node.put("attempt", attempt);
        node.put("message_count", messages.size());
        node.set("messages", LlmTraceSerializer.messagesToArray(messages));
        client.requestParameters(responseFormat).fields().forEachRemaining(field -> node.set(field.getKey(), field.getValue()));
        node.put("model_role", role);
        if (!node.hasNonNull("model")) node.put("model", model);
        // 显式 null 表示本次没有下发结构约束；完整 Schema 保留在 response_format 中。
        node.set("response_format", responseFormat == null
                ? com.fasterxml.jackson.databind.node.NullNode.instance : responseFormat.deepCopy());
        if (responseFormat != null && responseFormat.path("json_schema").has("schema")) {
            node.put("schema_fingerprint", PromptStore.fingerprint(responseFormat.at("/json_schema/schema").toString()));
        }
        return node.toString();
    }

    /** 把 planner 原始输出包成 JSON 对象落 trace（PLAN 事件的 output_json）。 */
    private static String planOutput(String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("content", content);
        return node.toString();
    }
}
