package com.cexpilot.platform.engine.react;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.evidence.*;
import com.cexpilot.platform.llm.*;
import com.cexpilot.platform.observability.*;
import com.cexpilot.platform.plan.*;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.ToolControl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** ReAct 方案模式：只读观察 → 冻结本阶段方案 → 等待审批 → 执行真实操作 → 下一阶段决策。 */
final class PreparedReactExecution {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final LlmCallService llm;
    private final ToolEvidenceWriter evidence;
    private final PreparedPlanService plans;
    private final PreparedPlanExecutor executor;
    PreparedReactExecution(ToolRegistry tools, PromptStore prompts, LlmCallService llm, ToolEvidenceWriter evidence,
                           PreparedPlanService plans, PreparedPlanExecutor executor) {
        this.tools = tools; this.prompts = prompts; this.llm = llm; this.evidence = evidence; this.plans = plans; this.executor = executor;
    }

    ExecutionResult execute(ConfiguredAgentDefinition definition, AgentRunContext run, QueryBudget budget) {
        var scope = Objects.requireNonNull(RunScope.current(), "ReAct 方案模式需要持久化运行现场");
        var state = new State(); var fields = List.<ClarificationField>of(); String directAnswer = null;
        try {
            for (int stage = 1; stage <= definition.maxSteps(); stage++) {
                var decision = prepareStage(stage, state, definition, run, budget, scope);
                if (decision.action().equals("clarify")) { fields = decision.fields(); directAnswer = ClarificationField.question(fields); state.stop = "CLARIFY"; break; }
                if (decision.action().equals("finish")) { state.selected = decision.references(); break; }
                var entry = plans.prepare("react_stage_" + stage, decision.proposal());
                if (plans.approval(entry) == PlanApprovalStatus.PENDING) throw RunSuspended.approval(entry.id());
                if (plans.approval(entry) != PlanApprovalStatus.APPROVED) {
                    state.failed = true; state.partial = true; state.stop = entry.status();
                    directAnswer = "本阶段方案已拒绝、过期或被替换，未继续执行；已完成的前序操作保留。"; break;
                }
                state.executingPlan = true;
                var outcome = executor.execute(entry.id(), run.sink());
                state.executingPlan = false;
                appendPlanResults(entry, outcome, state, definition, run);
                state.completedPlans.add(entry.id());
                if (outcome.status() != PlanExecutionStatus.SUCCEEDED) { state.failed = true; state.partial = true; state.stop = outcome.status().name(); break; }
                state.stop = "STEP_BUDGET";
            }
        } catch (RunSuspended | RunLeaseLostException control) { throw control; }
        catch (QueryBudget.Exhausted exhausted) { state.stop = exhausted.reason; state.partial = true; state.failed |= state.executingPlan; }
        catch (RuntimeException failure) { throw failure(failure, state); }
        if (!Set.of("MODEL_FINISH", "CLARIFY").contains(state.stop)) { state.partial = true; state.failed = true; }
        if (directAnswer == null) directAnswer = answer(state, definition, run);
        return result(directAnswer, fields, state, definition, run, budget);
    }

    /** 冻结的阶段快照先于方案提交保存，恢复时不重跑只读准备或重新解释旧证据。 */
    private Decision prepareStage(int stage, State state, ConfiguredAgentDefinition definition, AgentRunContext run, QueryBudget budget, RunScope scope) {
        String key = "react:prepared-stage:" + stage;
        var frozen = scope.load(key);
        if (frozen != null) {
            state.restore(JSON.convertValue(frozen.get("state"), SavedState.class));
            return JSON.convertValue(frozen.get("decision"), Decision.class);
        }
        String system = preparePrompt(definition); String lastError = null; int reads = 0; var seen = new HashSet<Invocation>();
        Decision conclusion = new Decision("finish", null, null, null, List.of(), List.of());
        while (state.plannerCalls < definition.maxSteps()) {
            var input = JSON.createObjectNode().put("question", run.question()).put("recent_dialogue", run.history() == null ? "" : run.history())
                    .put("request_time", run.request().requestTime().toString()).put("timezone", run.request().userZone() == null ? "UTC" : run.request().userZone().getId())
                    .put("stage", stage);
            input.set("observations", state.facts); input.set("completed_plans", JSON.valueToTree(state.completedPlans)); input.set("budget", budget.snapshot());
            if (lastError != null) input.put("validation_error", lastError);
            int before = state.usages.size(); ChatResponse response;
            try { response = llm.call("agent_prepare_" + stage, "react_base_prepare", List.of(ChatMessage.system(system), ChatMessage.user(input.toString())), run.traceId(), run.sink(), state.usages); }
            finally { state.plannerCalls += state.usages.size() - before; }
            Decision decision; JsonNode args = null; String position = "react_prepare:" + stage + ":" + (reads + 1);
            try {
                decision = parseDecision(response, definition, state);
                if (decision.action().equals("call")) {
                    var frozenArgs = scope.frozenArguments(decision.tool(), position);
                    args = frozenArgs == null ? readArguments(decision.tool(), decision.arguments(), state) : frozenArgs;
                }
            } catch (ModelOutputException invalid) {
                lastError = invalid.getMessage(); rejected(run, definition, invalid); continue;
            }
            if (!decision.action().equals("call")) { conclusion = decision; state.stop = decision.action().equals("finish") ? "MODEL_FINISH" : decision.action().toUpperCase(Locale.ROOT); break; }
            if (!seen.add(new Invocation(decision.tool(), args.deepCopy()))) { state.stop = "NO_PROGRESS"; state.partial = true; break; }
            reads++; state.toolCalls++;
            executeRead(decision.tool(), args, position, state, definition, run); lastError = null;
        }
        var snapshot = JSON.createObjectNode(); snapshot.set("state", JSON.valueToTree(state.snapshot())); snapshot.set("decision", JSON.valueToTree(conclusion));
        scope.save(key, snapshot);
        return conclusion;
    }

    private Decision parseDecision(ChatResponse response, ConfiguredAgentDefinition definition, State state) {
        if (response.hasToolCalls()) throw new ModelOutputException("准备阶段只支持 JSON 动作");
        var output = PlanningJson.parse(response.content());
        PlanningJson.object(output, Set.of("action", "tool", "arguments", "summary", "calls", "evidence_refs", "clarify"), "准备决策");
        String action = PlanningJson.text(output, "action", 20);
        if (action.equals("call")) {
            String tool = PlanningJson.text(output, "tool", 64);
            if (!definition.allowedTools().contains(tool)) throw new ModelOutputException("工具未获此 Agent 授权");
            if (tools.definition(tool).control().kind() != ToolControl.Kind.READ) throw new ModelOutputException("准备阶段不能执行操作工具，请使用 propose 提交方案");
            return new Decision(action, tool, output.path("arguments"), null, List.of(), List.of());
        }
        if (action.equals("propose")) return new Decision(action, null, null, parseProposal(output, definition, state), List.of(), List.of());
        if (action.equals("finish")) {
            var refs = ReactEngine.refs(output.path("evidence_refs"));
            for (var ref : refs) if (!ReactEngine.fresh(state.observations.get(ref))) throw new ModelOutputException("结束引用无效或过期");
            if (refs.isEmpty() && state.observations.values().stream().anyMatch(ReactEngine::fresh)) throw new ModelOutputException("结束必须选择已有成功证据");
            return new Decision(action, null, null, null, refs, List.of());
        }
        if (action.equals("clarify")) {
            try {
                var fields = ClarificationField.parse(output.path("clarify")); if (fields.isEmpty()) throw new IllegalArgumentException("澄清字段为空");
                return new Decision(action, null, null, null, List.of(), fields);
            } catch (IllegalArgumentException invalid) { throw new ModelOutputException(invalid.getMessage(), invalid); }
        }
        throw new ModelOutputException("未知准备动作");
    }

    private PreparedPlan parseProposal(JsonNode output, ConfiguredAgentDefinition definition, State state) {
        try {
            String summary = PlanningJson.text(output, "summary", 1000); var calls = new ArrayList<PreparedToolCall>();
            if (!output.path("calls").isArray() || output.path("calls").isEmpty()) throw new IllegalArgumentException("方案必须包含调用清单");
            for (var raw : output.path("calls")) {
                PlanningJson.object(raw, Set.of("id", "tool", "arguments", "depends_on"), "方案调用");
                String id = PlanningJson.text(raw, "id", 40), tool = PlanningJson.text(raw, "tool", 64);
                if (!definition.allowedTools().contains(tool)) throw new IllegalArgumentException("方案工具未获此 Agent 授权");
                var dependencies = new ArrayList<String>();
                if (raw.has("depends_on")) {
                    if (!raw.path("depends_on").isArray()) throw new IllegalArgumentException("depends_on 必须为数组");
                    for (var dependency : raw.path("depends_on")) { if (!dependency.isTextual()) throw new IllegalArgumentException("依赖必须为调用 ID"); dependencies.add(dependency.asText()); }
                }
                calls.add(new PreparedToolCall(id, tool, ReactEngine.resolve(raw.path("arguments"), state.observations), dependencies));
            }
            return plans.validateProposal(new PreparedPlan(summary, calls, 86400));
        } catch (IllegalArgumentException invalid) { throw new ModelOutputException(invalid.getMessage(), invalid); }
    }
    private JsonNode readArguments(String tool, JsonNode arguments, State state) {
        try { return tools.prepareArguments(tool, ReactEngine.resolve(arguments, state.observations)); }
        catch (IllegalArgumentException invalid) { throw new ModelOutputException(invalid.getMessage(), invalid); }
    }

    private void executeRead(String tool, JsonNode arguments, String position, State state, ConfiguredAgentDefinition definition, AgentRunContext run) {
        if (run.progress() != null) run.progress().accept("正在准备方案所需信息…");
        var context = new ToolContext(run.traceId(), run.session() == null ? null : run.session().conversationId(), run.request().userZone(), run.request().requestTime(),
                definition.agent().id(), run.visitor(), definition.allowedTools());
        var collector = new NodeTraceCollector.Session(); NodeTraceCollector.bind(collector); ToolResult result;
        try { result = tools.invoke(tool, arguments, context, position); }
        finally {
            NodeTraceCollector.clear();
            for (var event : collector.freezeAndSnapshot()) { state.httpCalls++; run.sink().record(TraceEvent.toolCall(run.traceId(), event.name(), event.inputJson(), event.outputJson(), event.durationMs(), event.error())); }
        }
        addFact(tool, result, position, state, definition, run, null, null);
        run.sink().record(TraceEvent.toolCall(run.traceId(), tool, arguments.toString(), result.toMessageContent(), null, result.error()));
    }
    private void appendPlanResults(DurableStore.Entry plan, PlanExecutionResult outcome, State state, ConfiguredAgentDefinition definition, AgentRunContext run) {
        for (var call : plans.plan(plan).orderedCalls()) {
            var result = outcome.results().get(call.id()); if (result == null) continue;
            state.toolCalls++; if (tools.isHttp(call.tool())) state.httpCalls++;
            addFact(call.tool(), result, "react_plan:" + plan.id() + ":" + call.id(), state, definition, run, plan.id(), call.id());
        }
    }
    private void addFact(String tool, ToolResult result, String position, State state, ConfiguredAgentDefinition definition, AgentRunContext run, String planId, String callId) {
        String ref = "e" + state.toolCalls; state.observations.put(ref, result);
        var fact = ReactEngine.toolFact(result); fact.put("ref", ref).put("tool", tool);
        if (planId != null) fact.put("preparedPlanId", planId).put("preparedCallId", callId);
        if (result.ok() && evidence != null && result.provenance() != null && run.session() != null) {
            var stored = ToolEvidenceCheckpoint.saveOptional(evidence, run, definition.agent().id(), tool, position, result);
            state.partial |= stored.unavailable(); if (stored.unavailable()) fact.put("persistence", "UNAVAILABLE");
            stored.factId().ifPresent(id -> { state.saved.add(id); fact.put("fact_id", id); });
        }
        state.facts.add(fact); state.partial |= !result.ok();
    }

    private String answer(State state, ConfiguredAgentDefinition definition, AgentRunContext run) {
        var visible = JSON.createArrayNode(); var allowed = new HashSet<String>();
        for (var fact : state.facts) {
            String ref = fact.path("ref").asText();
            if (ReactEngine.fresh(state.observations.get(ref)) && (state.selected.isEmpty() || state.selected.contains(ref))) { visible.add(fact); allowed.add(ref); }
        }
        if (visible.isEmpty()) { state.partial = true; return "本次未取得可用执行结果，无法给出可靠回答。"; }
        var input = JSON.createObjectNode().put("question", run.question()).put("stop_reason", state.stop).put("partial", state.partial); input.set("facts", visible);
        int before = state.usages.size();
        try {
            var response = llm.call("agent_answer", "react_base_answer", List.of(ChatMessage.system(prompts.render("react_base_answer", Map.of("agent_prompt", prompts.render(definition.prompt(), Map.of())))), ChatMessage.user(input.toString())), run.traceId(), run.sink(), state.usages);
            return ReactEngine.parseAnswer(response, allowed, state.observations);
        } catch (RunSuspended | RunLeaseLostException control) { throw control; }
        catch (ModelOutputException invalid) { state.partial = true; state.stop = "ANSWER_INVALID"; rejected(run, definition, invalid); return "本次未能生成可靠回答，执行记录已保留。"; }
        catch (QueryBudget.Exhausted exhausted) { state.partial = true; state.stop = exhausted.reason; return "本次达到预算或时间限制，已执行结果保留在记录中。"; }
        catch (RuntimeException failure) { state.answerCalls = state.usages.size() - before; throw failure(failure, state); }
        finally { state.answerCalls = state.usages.size() - before; }
    }
    private ExecutionResult result(String answer, List<ClarificationField> fields, State state, ConfiguredAgentDefinition definition, AgentRunContext run, QueryBudget budget) {
        String status = !fields.isEmpty() ? "NEEDS_CLARIFICATION" : state.failed ? "FAILED" : "SUCCEEDED";
        var payload = JSON.createObjectNode().put("status", status).put("agent_id", definition.agent().id()).put("execution_status", state.partial ? "PARTIAL" : "FINISHED").put("stop_reason", state.stop);
        payload.set("budget", budget.snapshot()); payload.set("preparedPlanIds", JSON.valueToTree(state.completedPlans));
        if (!fields.isEmpty()) payload.set("clarify", JSON.valueToTree(fields));
        run.sink().record(new TraceEvent(run.traceId(), "AGENT_FINISHED", definition.agent().id(), null, payload.toString(), null, null, null, null, null, null));
        return new ExecutionResult(answer, state.facts, state.toolCalls, state.plannerCalls, LlmUsage.knownPromptTokens(state.usages), LlmUsage.knownCompletionTokens(state.usages),
                status.equals("SUCCEEDED") ? null : status, fields.isEmpty() ? null : answer, 0, state.plannerCalls, state.answerCalls, 0, 0, state.httpCalls, state.saved)
                .withLlmUsages(state.usages).withAgent(definition.agent().route(), payload);
    }
    private String preparePrompt(ConfiguredAgentDefinition definition) {
        var catalog = JSON.createArrayNode();
        definition.allowedTools().stream().sorted().forEach(tool -> {
            var item = catalog.addObject().put("name", tool).put("description", tools.spec(tool).description()); item.set("control", JSON.valueToTree(tools.definition(tool).control()));
            item.set("input_schema", tools.spec(tool).inputSchema()); item.set("output_schema", tools.outputSchema(tool));
        });
        return prompts.render("react_base_prepare", Map.of("agent_prompt", prompts.render(definition.prompt(), Map.of()), "tools", catalog.toString()));
    }
    private void rejected(AgentRunContext run, ConfiguredAgentDefinition definition, ModelOutputException invalid) {
        run.sink().record(new TraceEvent(run.traceId(), "AGENT_REJECTED", definition.agent().id(), null, null, null, null, null, null, null, invalid.getMessage()));
    }
    private ExecutionFailure failure(RuntimeException cause, State state) {
        return new ExecutionFailure(cause, new TurnCounters(state.plannerCalls, state.answerCalls, 0, 0, state.httpCalls, 0, state.saved).withLlmUsages(state.usages).withToolCalls(state.toolCalls), null, state.facts);
    }
    private record Decision(String action, String tool, JsonNode arguments, PreparedPlan proposal, List<String> references, List<ClarificationField> fields) {}
    private record Invocation(String tool, JsonNode arguments) {}
    private record SavedState(List<LlmUsage> usages, ArrayNode facts, Map<String, ToolResult> observations, List<String> saved, List<String> completedPlans,
                              List<String> selected, int toolCalls, int httpCalls, int plannerCalls, boolean partial, String stop) {}
    private static final class State {
        List<LlmUsage> usages = new ArrayList<>(); ArrayNode facts = JSON.createArrayNode(); Map<String, ToolResult> observations = new LinkedHashMap<>();
        List<String> saved = new ArrayList<>(), completedPlans = new ArrayList<>(), selected = List.of();
        int toolCalls, httpCalls, plannerCalls, answerCalls; boolean partial, failed, executingPlan; String stop = "STEP_BUDGET";
        SavedState snapshot() { return new SavedState(usages, facts, observations, saved, completedPlans, selected, toolCalls, httpCalls, plannerCalls, partial, stop); }
        void restore(SavedState value) {
            usages = new ArrayList<>(value.usages()); facts = value.facts().deepCopy(); observations = new LinkedHashMap<>(value.observations()); saved = new ArrayList<>(value.saved());
            completedPlans = new ArrayList<>(value.completedPlans()); selected = value.selected(); toolCalls = value.toolCalls(); httpCalls = value.httpCalls(); plannerCalls = value.plannerCalls(); partial = value.partial(); stop = value.stop();
        }
    }
}
