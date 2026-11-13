package com.cexpilot.platform.engine.plan;

import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.agent.ConfiguredAgentDefinition;
import com.cexpilot.platform.plan.*;
import com.cexpilot.platform.llm.PlanningJson;
import com.cexpilot.platform.llm.ModelOutputException;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.LlmCallService;

import com.cexpilot.platform.tool.calculation.CalculationTool;
import com.cexpilot.platform.engine.dag.DagExecutor;
import com.cexpilot.platform.engine.dag.DagPlan;
import com.cexpilot.platform.engine.dag.PlanNode;
import com.cexpilot.platform.engine.dag.ReferenceResolver;
import com.cexpilot.platform.evidence.ToolEvidenceWriter;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolArguments;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.tool.ToolResult;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.execution.ExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.time.Instant;
import java.util.*;

/**
 * 声明式 Agent（engine: plan）的引擎内置回调：LLM 输出工具/计算节点计划，
 * 校验结构与引用后交 DagExecutor 拓扑分层执行，再基于成功节点生成回答。
 */
final class DeclarativePlanHooks implements PlanHooks {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    /** 规划提示词/回答提示词模板名。 */
    static final String PLAN_PROMPT = "plan_agent", ANSWER_PROMPT = "plan_agent_answer";
    private final ConfiguredAgentDefinition definition;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final LlmCallService llm;
    private final ToolEvidenceWriter evidence;
    private final DagExecutor dagExecutor;
    private final PreparedPlanService preparedPlans;
    private final PreparedPlanExecutor preparedExecutor;

    DeclarativePlanHooks(ConfiguredAgentDefinition definition, ToolRegistry tools, PromptStore prompts,
                         LlmCallService llm, ToolEvidenceWriter evidence, DagExecutor dagExecutor, PreparedPlanService preparedPlans, PreparedPlanExecutor preparedExecutor) {
        this.definition = definition; this.tools = tools; this.prompts = prompts;
        this.llm = llm; this.evidence = evidence; this.dagExecutor = dagExecutor;
        this.preparedPlans = preparedPlans; this.preparedExecutor = preparedExecutor;
    }

    @Override public String stage() { return "agent_plan"; }
    @Override public String planningPrompt() { return definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN ? "plan_base_prepare" : PLAN_PROMPT; }
    @Override public int maxAttempts() { return 2; }

    @Override public Map<String, String> planningVars() {
        var catalog = JSON.createArrayNode();
        definition.allowedTools().stream().sorted().forEach(name -> { var item = catalog.addObject().put("name", name).put("description", tools.spec(name).description());
            item.set("control",JSON.valueToTree(tools.definition(name).control()));item.set("input_schema", tools.spec(name).inputSchema()); item.set("output_schema", tools.outputSchema(name)); });
        return Map.of("agent_prompt", prompts.render(definition.prompt(), Map.of()), "tools", catalog.toString());
    }

    @Override public ObjectNode planningInput(AgentRunContext run) {
        return JSON.createObjectNode().put("question", run.question()).put("recent_dialogue", run.history() == null ? "" : run.history())
                .put("request_time", run.request().requestTime().toString())
                .put("timezone", (run.request().userZone() == null ? java.time.ZoneOffset.ofHours(8) : run.request().userZone()).getId());
    }

    @Override public PlanVerdict validate(JsonNode planOutput, AgentRunContext run, List<LlmUsage> usages) {
        try {
            var plan = compile(planOutput, definition);
            if (definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN) preparedPlans.validateProposal(proposal(plan, run));
            var compiled = plan.toJson();
            return PlanVerdict.compiled(compiled);
        } catch (IllegalArgumentException invalid) {
            return PlanVerdict.repair(invalid.getMessage());
        }
    }

    @Override public String repairMessage(String validationError) {
        return "计划无效：" + validationError + "。只返回合法的计划 JSON。";
    }

    @Override public ExecutionResult complete(PlanCompletion completion) {
        var run = completion.run(); var usages = new ArrayList<>(completion.usages());
        int answerCalls = 0, httpCalls = 0; boolean partial = false, failed = false; String stop = "MODEL_FINISH", answer;
        var facts = JSON.createArrayNode(); var saved = new ArrayList<String>();
        var plan = DagPlan.fromJson((JsonNode) completion.compiled());
        var budget = QueryBudget.current();
        try {
            if (run.progress() != null) run.progress().accept("正在按计划获取数据…");
            var identity = new DagExecutor.NodeIdentity(run.session() == null ? null : run.session().conversationId(),
                    definition.agent().id(), run.visitor(), definition.allowedTools());
            java.util.function.Function<String, ToolResult> results;
            if (definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN) {
                var frozen = preparedPlans.prepare("declarative_plan", proposal(plan, run));
                if (preparedPlans.approval(frozen) == PlanApprovalStatus.PENDING) throw com.cexpilot.platform.run.RunSuspended.approval(frozen.id());
                if (preparedPlans.approval(frozen) != PlanApprovalStatus.APPROVED)
                    return result(run, "方案已拒绝、过期或被替换，未继续执行。", facts, usages, completion.plannerCalls(), 0, 0, 0, saved, frozen.status(), true, true);
                var outcome = preparedExecutor.execute(frozen.id(), run.sink());
                results = outcome.results()::get;
                failed = outcome.status() != PlanExecutionStatus.SUCCEEDED; partial |= failed;
                if (failed) stop = outcome.status().name();
            } else {
                var outcome = dagExecutor.executeIdentified(plan, run.traceId(), run.sink(), run.request(), budget, identity);
                results = outcome.context()::get;
            }
            var okNodeIds = new HashSet<String>();
            for (PlanNode node : plan.nodes()) {
                ToolResult result = results.apply(node.id());
                if (result == null) continue;
                if (tools.isHttp(node.tool())) httpCalls++;
                var fact = (ObjectNode) JSON.readTree(result.toMessageContent()); fact.put("ref", node.id()).put("tool", node.tool());
                if (result.ok()) {
                    okNodeIds.add(node.id());
                    if (run.session() != null && evidence != null && tools.isHttp(node.tool())) {
                        var stored = com.cexpilot.platform.evidence.ToolEvidenceCheckpoint.saveOptional(evidence, run, definition.agent().id(), node.tool(), node.id(), result);
                        partial |= stored.unavailable();
                        if (stored.unavailable()) fact.put("persistence", "UNAVAILABLE");
                        stored.factId().ifPresent(id -> { saved.add(id); fact.put("fact_id", id); });
                    }
                } else partial = true;
                facts.add(fact);
            }
            if (okNodeIds.isEmpty()) { answer = "本次未取得可用证据，无法给出可靠结果。"; partial = true; }
            else {
                var input = JSON.createObjectNode().put("question", run.question()).put("stop_reason", stop).put("partial", partial);
                input.set("facts", facts);
                int before = usages.size();
                try {
                    var response = llm.call("agent_answer", ANSWER_PROMPT, List.of(ChatMessage.system(prompts.render(ANSWER_PROMPT, Map.of())), ChatMessage.user(input.toString())),
                            run.traceId(), run.sink(), usages);
                    answer = parseAnswer(response, okNodeIds);
                } catch (ModelOutputException invalid) {
                    run.sink().record(new TraceEvent(run.traceId(), "AGENT_REJECTED", definition.agent().id(), null, null, null, null, null, null, null, invalid.getMessage()));
                    stop = "ANSWER_INVALID"; partial = true; answer = "本次未能生成可靠回答，已保留执行记录。";
                }
                finally { answerCalls = usages.size() - before; }
            }
        } catch (QueryBudget.Exhausted exhausted) { stop = exhausted.reason; partial = true; answer = "本次执行达到预算或时间限制，尚未完整完成；已取得的证据保留在执行记录中。"; }
        catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException waiting) { throw waiting; }
        catch (com.cexpilot.platform.execution.ExecutionFailure failure) { throw failure; }
        catch (RuntimeException | JsonProcessingException failure) {
            throw new com.cexpilot.platform.execution.ExecutionFailure(failure,
                    new com.cexpilot.platform.execution.TurnCounters(completion.plannerCalls(), answerCalls, 0, 0, httpCalls, 0, saved)
                            .withLlmUsages(usages).withToolCalls(facts.size()), null, facts);
        }
        return result(run, answer, facts, usages, completion.plannerCalls(), answerCalls, facts.size(), httpCalls, saved, stop, partial, failed);
    }

    private PreparedPlan proposal(DagPlan plan, AgentRunContext run) {
        String summary = run.question().length() > 1000 ? run.question().substring(0, 1000) : run.question();
        return new PreparedPlan(summary, plan.nodes().stream().map(node -> new PreparedToolCall(node.id(), node.tool(), node.args(), node.dependsOn())).toList(), 86400);
    }

    private static String parseAnswer(ChatResponse response, Set<String> okNodeIds) {
        if (response.hasToolCalls()) throw new ModelOutputException("回答不能调用工具");
        var output = PlanningJson.parse(response.content());
        PlanningJson.object(output, Set.of("answer", "evidence_refs"), "Agent 回答");
        var references = answerRefs(output.path("evidence_refs"), okNodeIds);
        if (references.isEmpty()) throw new ModelOutputException("回答缺少有效证据引用");
        return PlanningJson.text(output, "answer", 8000);
    }

    @Override public ExecutionResult exhausted(PlanCompletion completion) {
        return degraded(completion, "PLAN_INVALID", "本次未能生成有效的执行计划，已保留执行记录。");
    }

    @Override public ExecutionResult budgetExhausted(PlanCompletion completion, QueryBudget.Exhausted exhausted) {
        return degraded(completion, exhausted.reason, "本次执行达到预算或时间限制，尚未完整完成；已取得的证据保留在执行记录中。");
    }

    private ExecutionResult degraded(PlanCompletion completion, String stop, String answer) {
        return result(completion.run(), answer, JSON.createArrayNode(), completion.usages(),
                completion.plannerCalls(), 0, 0, 0, List.of(), stop, true, definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN);
    }

    private ExecutionResult result(AgentRunContext run, String answer, ArrayNode facts, List<LlmUsage> usages,
                                   int plannerCalls, int answerCalls, int toolCalls, int httpCalls, List<String> saved, String stop, boolean partial) {
        return result(run,answer,facts,usages,plannerCalls,answerCalls,toolCalls,httpCalls,saved,stop,partial,false);
    }
    private ExecutionResult result(AgentRunContext run, String answer, ArrayNode facts, List<LlmUsage> usages,
                                   int plannerCalls, int answerCalls, int toolCalls, int httpCalls, List<String> saved, String stop, boolean partial, boolean failed) {
        var payload = JSON.createObjectNode().put("status", failed ? "FAILED" : "SUCCEEDED")
                .put("agent_id", definition.agent().id()).put("execution_status", partial ? "PARTIAL" : "FINISHED").put("stop_reason", stop);
        if (QueryBudget.current() != null) payload.set("budget", QueryBudget.current().snapshot());
        run.sink().record(new TraceEvent(run.traceId(), "AGENT_FINISHED", definition.agent().id(), null, payload.toString(), null, null, null, null, null, null));
        return new ExecutionResult(answer, facts, toolCalls, plannerCalls, LlmUsage.knownPromptTokens(usages), LlmUsage.knownCompletionTokens(usages),
                failed ? "FAILED" : null, null, 0, plannerCalls, answerCalls, 0, 0, httpCalls, saved).withLlmUsages(usages).withAgent(definition.agent().route(), payload);
    }

    /**
     * 把模型输出的计划 JSON 编译为 DagPlan：结构校验、工具白名单、引用语法与目标检查、
     * 规划期参数校验；节点依赖由引用推导，环检测后返回。
     */
    private DagPlan compile(JsonNode json, ConfiguredAgentDefinition definition) {
        PlanningJson.object(json, Set.of("nodes"), "执行计划");
        JsonNode nodesNode = json.path("nodes");
        if (!nodesNode.isArray() || nodesNode.isEmpty() || nodesNode.size() > 16) throw new IllegalArgumentException("nodes 必须为 1~16 个节点的数组");
        var ids = new LinkedHashSet<String>(); var nodes = new ArrayList<PlanNode>();
        for (JsonNode raw : nodesNode) {
            String id = raw.path("id").isTextual() ? raw.path("id").asText() : null;
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,39}") || !ids.add(id)) throw new IllegalArgumentException("节点 id 缺失、非法或重复");
            String tool = raw.path("tool").isTextual() ? raw.path("tool").asText() : null;
            if (tool == null || !definition.allowedTools().contains(tool)) throw new IllegalArgumentException("工具未获此 Agent 授权: " + tool);
            var spec = tools.spec(tool);
            if (spec == null) throw new IllegalArgumentException("工具未注册: " + tool);
            JsonNode args = raw.path("args").isObject() ? raw.path("args") : JSON.createObjectNode();
            var syntaxErrors = ReferenceResolver.syntaxErrors(args);
            if (!syntaxErrors.isEmpty()) throw new IllegalArgumentException(String.join("; ", syntaxErrors));
            var refs = ReferenceResolver.findRefs(args);
            var dependsOn = new ArrayList<String>();
            if (definition.toolApprovalMode() == ToolApprovalMode.PREPARED_PLAN) {
                PlanningJson.object(raw, Set.of("id", "tool", "args", "depends_on"), "方案节点");
                if (raw.has("depends_on")) {
                    if (!raw.path("depends_on").isArray()) throw new IllegalArgumentException("depends_on 必须为数组");
                    for (var dependency : raw.path("depends_on")) {
                        if (!dependency.isTextual() || dependsOn.contains(dependency.asText())) throw new IllegalArgumentException("依赖名称无效或重复");
                        dependsOn.add(dependency.asText());
                    }
                }
            }
            for (var ref : refs) {
                if (ref.nodeId().equals(id)) throw new IllegalArgumentException("节点不能引用自身: " + id);
                if (dependsOn.contains(ref.nodeId())) continue;
                dependsOn.add(ref.nodeId());
            }
            var executor = tools.get(tool);
            if (executor instanceof CalculationTool calculation) calculation.validateArguments(args, true);
            else {
                var errors = ToolArguments.validate(args, spec.inputSchema(), true);
                if (!errors.isEmpty()) throw new IllegalArgumentException(tool + " 参数非法: " + String.join("; ", errors));
            }
            nodes.add(new PlanNode(id, tool, args, dependsOn));
        }
        for (PlanNode node : nodes)
            for (String dep : node.dependsOn())
                if (!ids.contains(dep)) throw new IllegalArgumentException("引用目标不存在: " + dep);
        acyclic(nodes);
        return new DagPlan(nodes);
    }

    /** Kahn 分层做环检测；有环抛 IllegalArgumentException。 */
    private static void acyclic(List<PlanNode> nodes) {
        var indegree = new HashMap<String, Integer>();
        nodes.forEach(n -> indegree.put(n.id(), n.dependsOn().size()));
        var remaining = new ArrayList<>(nodes);
        while (!remaining.isEmpty()) {
            var ready = remaining.stream().filter(n -> indegree.get(n.id()) == 0).toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("计划存在循环依赖");
            for (var node : ready) {
                remaining.remove(node);
                remaining.forEach(r -> { if (r.dependsOn().contains(node.id())) indegree.merge(r.id(), -1, Integer::sum); });
            }
        }
    }

    /** 校验回答的 evidence_refs：必须全部是实际成功的节点 id。 */
    private static List<String> answerRefs(JsonNode node, Set<String> okNodeIds) {
        if (!node.isArray()) throw new ModelOutputException("evidence_refs 必须为数组");
        var refs = new ArrayList<String>();
        for (var ref : node) {
            if (!ref.isTextual() || !okNodeIds.contains(ref.asText()) || refs.contains(ref.asText())) throw new ModelOutputException("证据引用非法: " + ref.asText());
            refs.add(ref.asText());
        }
        return refs;
    }
}
