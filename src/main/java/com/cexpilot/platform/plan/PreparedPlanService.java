package com.cexpilot.platform.plan;

import com.cexpilot.platform.engine.dag.*;
import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.*;
import com.cexpilot.platform.tool.calculation.CalculationTool;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

/** 方案冻结、审批与调用归属校验；不执行工具，不向模型授予新的权限。 */
@Service
public class PreparedPlanService {
    private static final String PROTOCOL = "prepared-plan-v1";
    private final ToolRegistry tools;
    private final DurableStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final ToolAccessChecks checks;

    public PreparedPlanService(ToolRegistry tools, DurableStore store, ObjectMapper json, Clock clock,
                               List<ToolAccessPolicy> policies, List<ToolResourceGuard> guards) {
        this.tools = tools; this.store = store; this.clock = clock;
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.checks = new ToolAccessChecks(this.json, policies, guards);
    }

    /** 同一 slot 只冻结一次；内容变更必须废弃尚未执行的旧方案，并使用新 slot。 */
    public DurableStore.Entry prepare(String slot, PreparedPlan proposal) {
        if (slot == null || !slot.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("非法方案位置");
        var scope = currentScope(); var run = activeRun(scope); var plan = normalizePlan(proposal);
        String id = RunScope.invocationId(scope.runId(), "prepared-plan:" + slot);
        // 恢复已冻结方案不重新准备业务数据；确认和每次实际执行仍检查当前权限与资源。
        if (store.find(PLAN, id) == null) validateTools(plan, context(run)); // 业务服务调用保持在短事务之外。
        var frozen = freeze(run, plan).put("planId", id); var data = json.createObjectNode();
        data.set("frozen", frozen); data.put("fingerprint", ContentFingerprint.of(frozen));
        data.put("executionStatus", PlanExecutionStatus.NOT_STARTED.name());
        boolean confirmation = plan.calls().stream().anyMatch(call -> tools.definition(call.tool()).control().confirmationRequired());
        var status = confirmation ? PlanApprovalStatus.PENDING : PlanApprovalStatus.APPROVED;
        if (!confirmation) data.putObject("decision").put("mode", "TOOL_POLICY").put("approved", true).put("at", clock.instant().toString());
        return store.transaction(() -> {
            var lockedRun = lockActiveRun(scope);
            var existing = store.find(PLAN, id);
            if (existing != null) {
                validateFrozen(existing, lockedRun);
                if (!ContentFingerprint.of(existing.data().path("frozen").path("plan")).equals(ContentFingerprint.of(json.valueToTree(plan))))
                    throw new IllegalStateException("已冻结方案不能修改，请创建新方案重新审批");
                return existing;
            }
            if (hasUnfinishedPlans(run.id())) throw new IllegalStateException("上一阶段方案尚未结束");
            return store.create(PLAN, id, run.owner(), run.id(), status.name(), expiresAt(data), data);
        });
    }

    /** 确认仅记录决定和唤醒运行；绝不在审批 HTTP 线程执行工具。 */
    public DurableStore.Entry confirm(String id, String owner, String fingerprint, boolean approve) {
        var before = owned(id, owner); var run = store.require(RUN, before.runId());
        validateFrozen(before, run);
        if (approve && approval(before) == PlanApprovalStatus.PENDING && clock.instant().isBefore(expiresAt(before.data())))
            validateTools(plan(before), context(run));
        return store.transaction(() -> {
            var lockedRun = store.lock(RUN, before.runId()); var entry = store.lock(PLAN, id);
            requireOwner(entry, owner); validateFrozen(entry, lockedRun);
            if (!Objects.equals(fingerprint, entry.data().path("fingerprint").asText())) throw new IllegalArgumentException("方案确认内容指纹不一致");
            var status = approval(entry);
            if (status != PlanApprovalStatus.PENDING) {
                if (approve && status == PlanApprovalStatus.APPROVED || !approve && status == PlanApprovalStatus.REJECTED) return entry;
                throw new IllegalArgumentException("方案已不能确认");
            }
            if (lockedRun.data().has("result")) throw new IllegalArgumentException("所属运行已经结束");
            var data = entry.data();
            var next = !clock.instant().isBefore(expiresAt(data)) ? PlanApprovalStatus.EXPIRED
                    : approve ? PlanApprovalStatus.APPROVED : PlanApprovalStatus.REJECTED;
            data.putObject("decision").put("mode", "USER").put("approved", next == PlanApprovalStatus.APPROVED)
                    .put("by", owner).put("at", clock.instant().toString());
            save(entry, next, next == PlanApprovalStatus.APPROVED ? expiresAt(data) : null, data);
            store.wake(entry.runId(), clock.instant());
            return store.require(PLAN, id);
        });
    }

    /** 只有尚未登记任何调用的方案可以废弃；已开始的方案通过后续阶段处理，不能覆盖历史。 */
    public void supersede(String id) {
        var scope = currentScope();
        store.transaction(() -> {
            var run = lockActiveRun(scope); var entry = store.lock(PLAN, id); requireRun(entry, run); validateFrozen(entry, run);
            if (approval(entry) != PlanApprovalStatus.PENDING && approval(entry) != PlanApprovalStatus.APPROVED)
                throw new IllegalStateException("方案已不能废弃");
            if (execution(entry) != PlanExecutionStatus.NOT_STARTED || store.calls(run.id()).stream().anyMatch(call -> id.equals(call.data().path("preparedPlanId").asText())))
                throw new IllegalStateException("已开始执行的方案不能替换");
            save(entry, PlanApprovalStatus.SUPERSEDED, null, entry.data()); store.wake(run.id(), clock.instant()); return null;
        });
    }

    /** 到期扫描补偿漏消息；不把已经执行成功的历史审批改成过期。 */
    public void advance(String id) {
        var before = store.find(PLAN, id); if (before == null) return;
        store.transaction(() -> {
            store.lock(RUN, before.runId()); var entry = store.lock(PLAN, id);
            boolean waiting = approval(entry) == PlanApprovalStatus.PENDING
                    || approval(entry) == PlanApprovalStatus.APPROVED && execution(entry) == PlanExecutionStatus.NOT_STARTED;
            if (waiting && !clock.instant().isBefore(expiresAt(entry.data()))) {
                save(entry, PlanApprovalStatus.EXPIRED, null, entry.data()); store.wake(entry.runId(), clock.instant());
            }
            return null;
        });
    }

    public DurableStore.Entry owned(String id, String owner) {
        var entry = store.find(PLAN, id); requireOwner(entry, owner); return entry;
    }
    public DurableStore.Entry forRun(String id, String runId, String owner) {
        var entry = owned(id, owner);
        if (!runId.equals(entry.runId())) throw new NoSuchElementException("方案不存在");
        validateFrozen(entry, store.require(RUN, runId)); return entry;
    }
    public boolean awaitingApproval(String id, String runId, String owner) {
        advance(id); return approval(forRun(id, runId, owner)) == PlanApprovalStatus.PENDING;
    }
    public boolean hasUnfinishedPlans(String runId) {
        return store.plans(runId).stream().anyMatch(entry -> approval(entry) == PlanApprovalStatus.PENDING
                || approval(entry) == PlanApprovalStatus.APPROVED && !execution(entry).isTerminal());
    }
    public PreparedPlan plan(DurableStore.Entry entry) { return json.convertValue(entry.data().path("frozen").path("plan"), PreparedPlan.class); }
    public PlanApprovalStatus approval(DurableStore.Entry entry) { return PlanApprovalStatus.valueOf(entry.status()); }
    public PlanExecutionStatus execution(DurableStore.Entry entry) { return PlanExecutionStatus.valueOf(entry.data().path("executionStatus").asText()); }

    /** 只由服务端从获批方案解析参数与身份，调用方不能传入替换参数。 */
    public ApprovedCall approvedCall(String planId, String callId) {
        var scope = currentScope(); var run = activeRun(scope); var entry = forRun(planId, run.id(), run.owner());
        requireApproved(entry); var call = call(entry, callId); var args = resolvedArguments(entry, call);
        return new ApprovedCall(planId, callId, call.tool(), args, context(run), position(planId, callId), entry.data().path("fingerprint").asText(), expiresAt(entry.data()));
    }
    public record ApprovedCall(String planId, String callId, String tool, JsonNode arguments, ToolContext context,
                               String position, String fingerprint, Instant expiresAt) {}

    /** 只包围登记记录的数据库操作，锁定审批方案以串行化首次调用登记与方案废弃。 */
    public DurableStore.Entry registerCall(ApprovedCall approved, Supplier<DurableStore.Entry> registration) {
        var scope = currentScope();
        return store.transaction(() -> {
            var run = lockActiveRun(scope); var entry = store.lock(PLAN, approved.planId()); requireRun(entry, run); validateFrozen(entry, run); requireApproved(entry);
            var call = call(entry, approved.callId());
            if (!call.tool().equals(approved.tool()) || !resolvedArguments(entry, call).equals(approved.arguments())
                    || !entry.data().path("fingerprint").asText().equals(approved.fingerprint()) || !context(run).equals(approved.context()))
                throw new SecurityException("实际调用与已批准方案不一致");
            String invocation = invocationId(entry.id(), call.id(), entry.runId());
            if (execution(entry).isTerminal() && store.find(CALL, invocation) == null) throw new SecurityException("方案执行已结束");
            return registration.get();
        });
    }

    /** 后台提交／查单也重新核对方案，不能仅信任调用记录中的“已批准”标记。 */
    public void validateBoundCall(DurableStore.Entry invocation) {
        String id = invocation.data().path("preparedPlanId").asText();
        var run = store.require(RUN, invocation.runId()); var entry = forRun(id, run.id(), invocation.owner()); requireApproved(entry);
        var call = call(entry, invocation.data().path("preparedCallId").asText());
        if (!invocation.id().equals(invocationId(id, call.id(), run.id())) || !call.tool().equals(invocation.data().path("tool").asText())
                || !resolvedArguments(entry, call).equals(invocation.data().path("arguments"))
                || !entry.data().path("fingerprint").asText().equals(invocation.data().path("preparedPlanFingerprint").asText()))
            throw new SecurityException("工具调用不属于已批准方案");
        var encoded = invocation.data().path("context"); var context = context(run);
        if (!context.agentId().equals(encoded.path("agentId").asText()) || !context.visitorId().equals(encoded.path("visitorId").asText())
                || !Objects.equals(context.conversationId(), encoded.path("conversationId").asText(null))
                || !context.traceId().equals(encoded.path("traceId").asText())) throw new SecurityException("方案调用身份不一致");
    }

    public void markExecution(String id, PlanExecutionStatus status) {
        markExecution(id, status, null);
    }
    public void markExecution(String id, PlanExecutionStatus status, Map<String, ToolResult> results) {
        var scope = currentScope();
        store.transaction(() -> {
            var run = lockActiveRun(scope); var entry = store.lock(PLAN, id); requireRun(entry, run); validateFrozen(entry, run);
            if (execution(entry).isTerminal()) return null;
            if (status == PlanExecutionStatus.RUNNING) requireApproved(entry);
            entry.data().put("executionStatus", status.name());
            if (results != null) entry.data().set("executionResults", json.valueToTree(results));
            save(entry, approval(entry), null, entry.data()); return null;
        });
    }

    public ObjectNode view(DurableStore.Entry entry) {
        var frozen = entry.data().path("frozen");
        var view = json.createObjectNode().put("planId", entry.id()).put("approvalStatus", entry.status())
                .put("executionStatus", execution(entry).name()).put("fingerprint", entry.data().path("fingerprint").asText())
                .put("summary", frozen.path("plan").path("summary").asText()).put("expiresAt", frozen.path("expiresAt").asText())
                .put("failurePolicy", frozen.path("plan").path("failurePolicy").asText());
        if (entry.data().has("decision")) view.set("decision", entry.data().get("decision"));
        var calls = view.putArray("calls");
        for (var call : plan(entry).orderedCalls()) {
            var item = calls.addObject().put("id", call.id()).put("tool", call.tool()).put("summary", frozen.path("metadata").path(call.id()).path("summary").asText());
            item.set("arguments", call.arguments()); item.set("dependsOn", json.valueToTree(call.dependsOn()));
            item.set("resourceVersions", frozen.path("metadata").path(call.id()).path("resourceVersions"));
            var invocation = store.find(CALL, invocationId(entry.id(), call.id(), entry.runId()));
            item.put("status", invocation == null ? "NOT_STARTED" : invocation.status());
            if (invocation != null && invocation.data().has("result")) { item.set("result", invocation.data().get("result")); item.set("resolvedArguments", invocation.data().get("arguments")); }
            else if (entry.data().path("executionResults").has(call.id())) {
                var result = entry.data().path("executionResults").get(call.id()); item.set("result", result);
                item.put("status", result.path("ok").asBoolean() ? "SUCCEEDED" : "FAILED");
            }
        }
        return view;
    }

    public static String position(String planId, String callId) { return "plan:" + planId + ":" + callId; }
    public static String invocationId(String planId, String callId, String runId) { return RunScope.invocationId(runId, position(planId, callId)); }
    public ToolApprovalMode mode(DurableStore.Entry run) { return ToolApprovalMode.valueOf(run.data().path("toolApprovalMode").asText(ToolApprovalMode.PER_CALL.name())); }

    /** 纯契约校验端口，可用于引擎向模型反馈结构错误；不查询权限服务、不写数据库。 */
    public PreparedPlan validateProposal(PreparedPlan plan) { return normalizePlan(plan); }

    private PreparedPlan normalizePlan(PreparedPlan plan) {
        var byId = new HashMap<String, PreparedToolCall>(); plan.calls().forEach(call -> byId.put(call.id(), call));
        var calls = new ArrayList<PreparedToolCall>();
        for (var call : plan.calls()) {
            for (var reference : ReferenceResolver.findRefs(call.arguments())) {
                var source = byId.get(reference.nodeId());
                String error = ToolOutputSchema.referenceError(tools.outputSchema(source.tool()), reference.path());
                if (error != null) throw new IllegalArgumentException("方案参数引用不符合上游输出契约: " + error);
            }
            var definition = tools.definition(call.tool());
            var args = ToolArguments.withDefaults(call.arguments(), definition.inputSchema());
            var errors = ToolArguments.validate(args, definition.inputSchema(), true);
            if (!errors.isEmpty()) throw new IllegalArgumentException(call.tool() + " 参数非法: " + errors);
            if (tools.get(call.tool()) instanceof CalculationTool calculation) calculation.validateArguments(args, true);
            calls.add(new PreparedToolCall(call.id(), call.tool(), normalize(args), call.dependsOn()));
        }
        return new PreparedPlan(plan.summary(), calls, plan.expiresAfterSeconds(), plan.failurePolicy());
    }
    private JsonNode normalize(JsonNode value) {
        try { return json.readTree(value.toString()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("方案参数无法持久化", failure); }
    }
    private ObjectNode freeze(DurableStore.Entry run, PreparedPlan plan) {
        var now = clock.instant(); long seconds = plan.expiresAfterSeconds();
        for (var call : plan.calls()) seconds = Math.min(seconds, tools.definition(call.tool()).control().expiresAfterSeconds());
        var expires = now.plusSeconds(seconds); var runExpiry = Instant.parse(run.data().path("expiresAt").asText());
        if (expires.isAfter(runExpiry)) expires = runExpiry;
        var frozen = json.createObjectNode().put("protocol", PROTOCOL).put("runId", run.id()).put("owner", run.owner())
                .put("createdAt", now.toString()).put("expiresAt", expires.toString());
        frozen.set("identity", identity(run)); frozen.set("plan", json.valueToTree(plan));
        var contracts = frozen.putObject("contracts"); var metadata = frozen.putObject("metadata");
        for (var call : plan.calls()) {
            var definition = tools.definition(call.tool()); contracts.put(call.id(), tools.contractVersion(call.tool()));
            var item = metadata.putObject(call.id()).put("summary", definition.control().summary()).put("kind", definition.control().kind().name())
                    .put("confirmationRequired", definition.control().confirmationRequired());
            item.set("resourceVersions", checks.resourceVersions(definition, call.arguments()));
        }
        return frozen;
    }
    private void validateTools(PreparedPlan plan, ToolContext context) {
        for (var call : plan.calls()) {
            var definition = tools.definition(call.tool()); checks.checkBinding(definition, context);
            if (ReferenceResolver.findRefs(call.arguments()).isEmpty()) {
                var args = tools.prepareArguments(call.tool(), call.arguments()); checks.authorize(definition, args, context); checks.checkResources(definition, args, context);
            }
        }
    }
    private void validateFrozen(DurableStore.Entry entry, DurableStore.Entry run) {
        requireRun(entry, run); var frozen = entry.data().path("frozen");
        if (mode(run) != ToolApprovalMode.PREPARED_PLAN || !PROTOCOL.equals(frozen.path("protocol").asText())
                || !entry.id().equals(frozen.path("planId").asText()) || !run.id().equals(frozen.path("runId").asText()) || !run.owner().equals(frozen.path("owner").asText())
                || !identity(run).equals(frozen.path("identity")) || !ContentFingerprint.of(frozen).equals(entry.data().path("fingerprint").asText()))
            throw new SecurityException("审批方案身份或内容指纹已变化");
        for (var call : plan(entry).calls())
            if (!tools.contractVersion(call.tool()).equals(frozen.path("contracts").path(call.id()).asText())) throw new SecurityException("审批方案工具契约已变化");
    }
    private void requireApproved(DurableStore.Entry entry) {
        if (approval(entry) != PlanApprovalStatus.APPROVED) throw new SecurityException("方案尚未批准或已失效");
        if (!clock.instant().isBefore(expiresAt(entry.data()))) throw new SecurityException("方案执行授权已过期");
    }
    private JsonNode resolvedArguments(DurableStore.Entry entry, PreparedToolCall call) {
        var context = new DagContext();
        for (String dependency : call.dependsOn()) {
            var invocation = store.find(CALL, invocationId(entry.id(), dependency, entry.runId()));
            if (invocation == null || !invocation.data().has("result")) throw new IllegalStateException("方案依赖尚未完成: " + dependency);
            var result = json.convertValue(invocation.data().get("result"), ToolResult.class);
            if (!result.ok()) throw new IllegalStateException("方案依赖已经失败: " + dependency);
            context.put(dependency, result);
        }
        return normalize(tools.prepareArguments(call.tool(), ReferenceResolver.resolve(call.arguments(), context)));
    }
    private PreparedToolCall call(DurableStore.Entry entry, String callId) {
        return plan(entry).calls().stream().filter(call -> call.id().equals(callId)).findFirst().orElseThrow(() -> new SecurityException("调用不属于获批方案"));
    }
    private ObjectNode identity(DurableStore.Entry run) {
        var identity = json.createObjectNode();
        for (String field : List.of("agentId", "agentVersion", "adapterId", "adapterVersion", "toolApprovalMode", "route", "traceId", "session", "requestTime", "timezone", "allowedTools"))
            identity.set(field, run.data().path(field));
        return identity;
    }
    private ToolContext context(DurableStore.Entry run) {
        var data = run.data(); var allowed = new HashSet<String>(); data.path("allowedTools").forEach(tool -> allowed.add(tool.asText()));
        return new ToolContext(data.path("traceId").asText(), data.path("session").path("conversationId").asText(null),
                ZoneId.of(data.path("timezone").asText()), Instant.parse(data.path("requestTime").asText()), data.path("agentId").asText(), run.owner(), allowed);
    }
    private RunScope currentScope() {
        var scope = RunScope.current(); if (scope == null) throw new IllegalStateException("方案操作需要持久化运行现场"); scope.checkLease(); return scope;
    }
    private DurableStore.Entry activeRun(RunScope scope) {
        var run = store.require(RUN, scope.runId()); requireActiveRun(run, scope); return run;
    }
    private DurableStore.Entry lockActiveRun(RunScope scope) {
        var run = store.lock(RUN, scope.runId()); requireActiveRun(run, scope); return run;
    }
    private void requireActiveRun(DurableStore.Entry run, RunScope scope) {
        if (run.fence() != scope.fence() || run.leaseUntil() == null || !run.leaseUntil().isAfter(clock.instant())) throw new RunLeaseLostException("方案运行已被接管");
        if (run.data().has("result") || mode(run) != ToolApprovalMode.PREPARED_PLAN) throw new SecurityException("当前运行不允许方案执行");
    }
    private void requireOwner(DurableStore.Entry entry, String owner) {
        if (entry == null || owner == null || !owner.equals(entry.owner())) throw new NoSuchElementException("方案不存在");
    }
    private void requireRun(DurableStore.Entry entry, DurableStore.Entry run) {
        if (!run.id().equals(entry.runId()) || !run.owner().equals(entry.owner())) throw new SecurityException("方案不属于当前运行");
    }
    private Instant expiresAt(ObjectNode data) { return Instant.parse(data.path("frozen").path("expiresAt").asText()); }
    private void save(DurableStore.Entry entry, PlanApprovalStatus status, Instant next, ObjectNode data) {
        if (!store.update(PLAN, entry, status.name(), next, null, data)) throw new IllegalStateException("方案状态已变化，请重试");
    }
}
