package com.cexpilot.monitor;

import com.cexpilot.agent.*;
import com.cexpilot.config.DagConfig;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.metric.MetricCatalog;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitor 的规划方言与结果组装（{@link PlanHooks} 实现）：envelope 协议校验、候选规则编译校验、
 * 澄清/不支持/失败终态、草稿保存与确认卡片组装。执行循环归底座 PlanEngine。
 */
@Component
public class MonitorPlanHooks implements PlanHooks {
    /** 规划提示词模板名。 */
    public static final String PROMPT = "monitor_planner";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PromptStore prompts;
    private final MetricCatalog catalog;
    private final ToolRegistry registry;
    private final MonitorPlanValidator validator;
    private final DagConfig config;
    private final MonitorConversationFlow flow;

    public MonitorPlanHooks(PromptStore prompts, MetricCatalog catalog, ToolRegistry registry,
                            MonitorPlanValidator validator, DagConfig config, MonitorConversationFlow flow) {
        this.prompts = prompts; this.catalog = catalog; this.registry = registry;
        this.validator = validator; this.config = config; this.flow = flow;
    }

    @Override public String stage() { return PROMPT; }
    @Override public String planningPrompt() { return PROMPT; }
    @Override public int maxAttempts() { return Math.min(config.getPlannerMaxRetries(), 2) + 1; }

    @Override public Map<String, String> planningVars() {
        return Map.of("concepts", catalog.concepts(), "metrics", catalog.describeMetrics(),
                "operators", catalog.describeOperators(registry));
    }

    @Override public ObjectNode planningInput(AgentRunContext run) {
        var input = JSON.createObjectNode().put("question", run.question());
        input.set("context", run.conversationContext());
        return input;
    }

    @Override public void beforePlanning(AgentRunContext run) {
        if (run.progress() != null) run.progress().accept("正在解析监控条件，生成后需要你确认才会启用…");
    }

    @Override public PlanVerdict validate(JsonNode envelope, AgentRunContext run, List<LlmUsage> usages) {
        try {
            PlanningJson.object(envelope, Set.of("plan", "monitor", "needs_clarification", "reply"), "monitor 规划");
            if (envelope.has("needs_clarification") && !envelope.path("needs_clarification").isBoolean())
                throw new IllegalArgumentException("needs_clarification 必须是布尔值");
            if (envelope.path("needs_clarification").asBoolean())
                return PlanVerdict.terminal(terminal(run, "NEEDS_CLARIFICATION", PlanningJson.text(envelope, "reply", 200), usages));
            if (!envelope.hasNonNull("plan"))
                return PlanVerdict.terminal(terminal(run, "UNSUPPORTED", PlanningJson.text(envelope, "reply", 200), usages));
            var accepted = validator.validate(envelope);
            run.sink().record(new TraceEvent(run.traceId(), "MONITOR_PLAN", PROMPT, null, accepted.candidate().toString(), null, null, null, null, null, null));
            return PlanVerdict.compiled(accepted.candidate());
        } catch (IllegalArgumentException invalid) {
            run.sink().record(new TraceEvent(run.traceId(), "MONITOR_PLAN", PROMPT, null, envelope.toString(), null, null, null, null, null, invalid.getMessage()));
            return PlanVerdict.repair(invalid.getMessage());
        }
    }

    @Override public void planRejected(String rawContent, String error, AgentRunContext run) {
        run.sink().record(new TraceEvent(run.traceId(), "MONITOR_PLAN", PROMPT, null, rawContent, null, null, null, null, null, error));
    }

    @Override public String repairMessage(String validationError) {
        return "规划未通过服务端校验：" + validationError + "。修复完整 JSON；无法支持时 plan=null 并在 reply 说明。";
    }

    @Override public ExecutionResult exhausted(PlanCompletion completion) {
        return terminal(completion.run(), "FAILED", "暂时无法可靠生成监控计划，本次没有创建或启用监控任务。", completion.usages());
    }

    @Override public ExecutionResult complete(PlanCompletion completion) {
        var run = completion.run(); var usages = completion.usages(); var candidate = (JsonNode) completion.compiled();
        var result = planningResult("PLANNED", "监控候选计划已生成并通过校验；本次仅规划，尚未创建或启用后台任务。", candidate, usages);
        if (flow != null && run.visitor() != null && candidate != null) {
            try {
                var card = flow.saveDraft(run.visitor(), run.session(), run.question(), candidate,
                        run.request().userZone() == null ? java.time.ZoneOffset.ofHours(8).getId() : run.request().userZone().getId());
                var payload = ((ObjectNode) result.agentPayload()).deepCopy().put("status", "PENDING_CONFIRMATION").put("execution_status", "NOT_ENABLED");
                payload.set("confirmation", card); payload.set("candidate", card.path("candidate"));
                var text = new StringBuilder("我将监控条件整理为以下规则（全部同时满足）：\n");
                for (var rule : card.path("rules")) text.append("• ").append(rule.path("description").asText()).append('\n');
                text.append("每5分钟检查一次，连续 ").append(card.path("requiredHits").asInt()).append(" 次满足后发邮件；条件恢复后才会再次提醒。\n");
                if (!card.at("/profile/emailBound").asBoolean()) text.append("请填写通知邮箱，并核对后点击确认。当前草稿尚未启用。");
                else text.append("请核对下面的规则和收件邮箱，点击确认后才会启用。");
                if ("DRY_RUN".equals(card.path("mailMode").asText())) text.append("\n当前为邮件模拟模式，只记录日志，不会发送真实邮件。");
                result = new ExecutionResult(text.toString(), result.evidence(), 0, 0, result.promptTokens(), result.completionTokens(), "SUCCEEDED", null, 0,
                        result.plannerLlmCalls(), 0, 0, 0, 0, List.of()).withLlmUsages(usages).withAgent("monitor", payload);
                run.sink().record(new TraceEvent(run.traceId(), "MONITOR_DRAFT", "monitor_flow", null, card.toString(), null, null, null, null, null, null));
            } catch (RuntimeException failure) {
                throw new ExecutionFailure(failure, new TurnCounters(usages.size(), 0, 0, 0, 0, 0, List.of()).withLlmUsages(usages));
            }
        }
        return result;
    }

    /** 澄清/不支持/失败终态：落 MONITOR_PLAN 归一化事件并按原口径组装结果。 */
    private ExecutionResult terminal(AgentRunContext run, String status, String reply, List<LlmUsage> usages) {
        var normalized = JSON.createObjectNode().put("status", status).put("reply", reply).putNull("plan").putNull("monitor");
        run.sink().record(new TraceEvent(run.traceId(), "MONITOR_PLAN", PROMPT, null, normalized.toString(), null, null, null, null, null,
                "FAILED".equals(status) ? "监控规划修复次数耗尽" : null));
        return planningResult(status, reply, null, usages);
    }

    private ExecutionResult planningResult(String status, String reply, JsonNode candidate, List<LlmUsage> usages) {
        var payload = JSON.createObjectNode().put("status", status).put("execution_status", "NOT_CREATED");
        if (candidate != null) payload.set("candidate", candidate);
        String turn = "NEEDS_CLARIFICATION".equals(status) ? status : "FAILED".equals(status) ? status : "SUCCEEDED";
        return new ExecutionResult(reply, JSON.createArrayNode(), 0, 0, LlmUsage.knownPromptTokens(usages), LlmUsage.knownCompletionTokens(usages),
                turn, "NEEDS_CLARIFICATION".equals(status) ? reply : null, 0, usages.size(), 0, 0, 0, 0, List.of())
                .withLlmUsages(usages).withAgent("monitor", payload);
    }
}
