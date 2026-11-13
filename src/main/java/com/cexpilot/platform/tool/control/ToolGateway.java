package com.cexpilot.platform.tool.control;

import com.cexpilot.platform.run.*;
import com.cexpilot.platform.plan.*;
import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

import static com.cexpilot.platform.run.DurableStore.Kind.CALL;

/**
 * 唯一实际调用入口：授权 → 冻结提案 → 确认 → 提交/查单。等待是运行状态，不交给模型修复。
 */
@Component
public class ToolGateway implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolGateway.class);
    private final java.util.concurrent.ThreadPoolExecutor externalCalls = new java.util.concurrent.ThreadPoolExecutor(4, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), r -> {
        var t = new Thread(r, "platform-tool-io");
        t.setDaemon(true);
        return t;
    }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ToolRegistry registry;
    private final DurableStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final ToolAccessChecks checks;
    private final PreparedPlanService plans;

    public ToolGateway(ToolRegistry registry, DurableStore store, ObjectMapper json, Clock clock,
                       List<ToolAccessPolicy> policies, List<ToolResourceGuard> guards) {
        this(registry, store, json, clock, policies, guards, new PreparedPlanService(registry, store, json, clock, policies, guards));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolGateway(ToolRegistry registry, DurableStore store, ObjectMapper json, Clock clock,
                       List<ToolAccessPolicy> policies, List<ToolResourceGuard> guards, PreparedPlanService plans) {
        this.registry = registry;
        this.store = store;
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.clock = clock;
        this.checks = new ToolAccessChecks(this.json, policies, guards);
        this.plans = plans;
    }

    /**
     * 引擎调用入口：校验并登记请求，尝试推进 READY 调用，返回结果或等待信号。
     */
    public ToolResult invoke(String name, JsonNode arguments, ToolContext context, String position) {
        return invoke(name, arguments, context, position, null);
    }

    /** 方案调用只接受方案和调用 ID；工具、参数、身份及调用位置全部由服务端恢复。 */
    public ToolResult invokePrepared(String planId, String callId) {
        var approved = plans.approvedCall(planId, callId);
        return invoke(approved.tool(), approved.arguments(), approved.context(), approved.position(), approved);
    }

    private ToolResult invoke(String name, JsonNode arguments, ToolContext context, String position, PreparedPlanService.ApprovedCall approved) {
        var definition = registry.definition(name);
        var args = normalizeArguments(name, arguments);
        try {
            authorize(definition, args, context);
        } catch (SecurityException denied) {
            log.info("工具调用未获授权 traceId={} agentId={} tool={}", context == null ? null : context.traceId(), context == null ? null : context.agentId(), name);
            return new ToolResult(false, null, "工具调用未获授权", "DENIED", null);
        }

        var scope = RunScope.current();
        if (scope == null) {
            return executeReadWithoutScope(definition, args, context);
        }

        scope.checkLease();
        if (definition.control().kind() == ToolControl.Kind.OPERATION && approved == null
                && plans.mode(store.require(DurableStore.Kind.RUN, scope.runId())) == ToolApprovalMode.PREPARED_PLAN)
            throw new SecurityException("方案审批运行中的操作必须通过获批方案调用");
        if (approved != null) checkResources(definition, args, context);
        String id = RunScope.invocationId(scope.runId(), position);
        var entry = approved == null ? findOrCreateCall(scope, id, definition, args, context, null)
                : plans.registerCall(approved, () -> findOrCreateCall(scope, id, definition, args, context, approved));
        if (!entry.data().path("tool").asText().equals(name) || !entry.data().path("arguments").equals(args))
            throw new IllegalStateException("恢复位置与冻结调用不一致");
        if (ToolCallStatus.valueOf(entry.status()) == ToolCallStatus.READY) {
            process(id);
        }
        return readResultOrSuspend(id);
    }

    /**
     * 参数经过 JSON 往返规范化，保证首次调用与持久化后读取的数值类型一致。
     */
    private JsonNode normalizeArguments(String name, JsonNode arguments) {
        try {
            return json.readTree(registry.prepareArguments(name, arguments).toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("工具参数无法持久化", error);
        }
    }

    private ToolResult executeReadWithoutScope(ToolDefinition definition, JsonNode args, ToolContext context) {
        var tool = registry.get(definition.name());
        if (definition.control().kind() != ToolControl.Kind.READ || tool instanceof AsyncAgentTool)
            return new ToolResult(false, null, "此工具需要可恢复运行入口", "DURABLE_RUN_REQUIRED", null);
        return tool.execute(args, context);
    }

    /**
     * 同一运行位置只登记一次；已有记录直接复用，不重新冻结参数或扣预算。
     */
    private DurableStore.Entry findOrCreateCall(RunScope scope, String id, ToolDefinition definition,
                                                JsonNode args, ToolContext context, PreparedPlanService.ApprovedCall approved) {
        var entry = store.find(CALL, id);
        if (entry != null) {
            if (approved != null && (!approved.planId().equals(entry.data().path("preparedPlanId").asText())
                    || !approved.callId().equals(entry.data().path("preparedCallId").asText()))) throw new SecurityException("冻结调用与方案不一致");
            return entry;
        }
        // 方案调用的业务权限与资源检查由 invoke 在短事务之前执行。
        if (approved == null) checkResources(definition, args, context);
        scope.admitTool();
        String name = definition.name();
        var data = json.createObjectNode().put("tool", name).put("contract", registry.contractVersion(name))
                .put("createdAt", clock.instant().toString()).put("expiresAt", clock.instant().plusSeconds(definition.control().expiresAfterSeconds()).toString())
                .put("summary", summary(definition, args));
        data.set("arguments", args.deepCopy());
        data.set("context", encode(context));
        data.set("resourceVersions", resourceVersions(definition, args));
        if (approved != null) {
            data.put("preparedPlanId", approved.planId()).put("preparedCallId", approved.callId()).put("preparedPlanFingerprint", approved.fingerprint());
            if (approved.expiresAt().isBefore(Instant.parse(data.path("expiresAt").asText()))) data.put("expiresAt", approved.expiresAt().toString());
        }
        data.put("fingerprint", fingerprint(data));
        ToolCallStatus status = approved == null && definition.control().confirmationRequired() ? ToolCallStatus.CONFIRMATION_REQUIRED : ToolCallStatus.READY;
        try {
            return store.create(CALL, id, context.visitorId(), scope.runId(), status.name(), clock.instant(), data);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            return store.require(CALL, id);
        }
    }

    private ToolResult readResultOrSuspend(String id) {
        var entry = store.require(CALL, id);
        if (entry.data().has("result")) return json.convertValue(entry.data().get("result"), ToolResult.class);
        throw new RunSuspended(id);
    }

    public DurableStore.Entry confirm(String id, String owner, String fingerprint, boolean approve) {
        for (int attempt = 0; attempt < 8; attempt++) {
            var entry = owned(id, owner);
            var data = entry.data();
            ToolCallStatus currentStatus = ToolCallStatus.valueOf(entry.status());
            if (store.require(DurableStore.Kind.RUN, entry.runId()).data().has("result") && currentStatus == ToolCallStatus.CONFIRMATION_REQUIRED)
                throw new IllegalArgumentException("所属运行已经结束");
            if (!Objects.equals(fingerprint, data.path("fingerprint").asText()))
                throw new IllegalArgumentException("确认内容指纹不一致");
            if (currentStatus != ToolCallStatus.CONFIRMATION_REQUIRED) {
                if (approve && data.path("decision").asText().equals("APPROVED") || !approve && currentStatus == ToolCallStatus.REJECTED)
                    return entry;
                throw new IllegalArgumentException("该调用已不能确认");
            }
            ToolCallStatus status;
            if (!clock.instant().isBefore(Instant.parse(data.path("expiresAt").asText()))) {
                status = ToolCallStatus.EXPIRED;
                data.set("result", json.valueToTree(ToolResult.failure("确认已过期")));
            } else {
                if (approve) validateFrozen(entry);
                status = approve ? ToolCallStatus.READY : ToolCallStatus.REJECTED;
                data.put("decision", approve ? "APPROVED" : "REJECTED").put("decidedBy", owner).put("decidedAt", clock.instant().toString());
                if (!approve) data.set("result", json.valueToTree(ToolResult.failure("用户已拒绝该操作")));
            }
            if (store.update(CALL, entry, status.name(), status.isTerminal() ? null : clock.instant(), null, data)) {
                store.wake(entry.runId(), clock.instant());
                return store.require(CALL, id);
            }
        }
        throw new IllegalStateException("调用状态正在变化，请重试");
    }

    public DurableStore.Entry owned(String id, String owner) {
        var entry = store.find(CALL, id);
        if (entry == null || owner == null || !owner.equals(entry.owner()))
            throw new NoSuchElementException("调用不存在");
        return entry;
    }

    /**
     * 回调只唤醒；结果由受信任的工具适配器查单，外部不能直接伪造成功结果。
     */
    public void signal(String id, String jobId) {
        var entry = store.require(CALL, id);
        if (!Objects.equals(entry.data().path("jobId").asText(null), jobId) || jobId == null)
            throw new SecurityException("异步任务归属不一致");
        if (ToolCallStatus.valueOf(entry.status()).isTerminal()) return;
        // 不抢占正在提交/轮询的执行者；下一轮到期扫描补偿此竞争。
        if (entry.leaseUntil() == null) store.update(CALL, entry, entry.status(), clock.instant(), null, entry.data());
        store.wake(entry.runId(), clock.instant());
    }

    /**
     * 推进入口：领取已登记调用，处理等待条件，提交／查单并持久化结果。也供后台扫描使用。
     */
    public void process(String id) {
        var now = clock.instant();
        var entry = claimCall(id, now);
        if (entry == null) return;
        var data = entry.data();
        ToolCallStatus previous = ToolCallStatus.valueOf(entry.status());
        boolean externalStarted = false;
        try {
            var prepared = prepareExecution(entry, previous, now);
            if (prepared == null) return;
            var executing = markExecuting(entry, now);
            if (executing == null) return;
            entry = executing;
            externalStarted = true;
            var execution = executeToolCall(prepared, previous, data);
            saveExecutionOutcome(entry, prepared.definition(), execution, data);
        } catch (java.util.concurrent.RejectedExecutionException notSubmitted) {
            retryUnsubmittedCall(entry, previous);
        } catch (CallValidationException invalid) {
            handleInvalidCall(entry, invalid);
        } catch (RuntimeException uncertain) {
            handleExecutionFailure(entry, data, externalStarted, uncertain);
        }
    }

    /** 领取租约与 fencing token；未到期、其他执行者持有或已结束时不再推进。 */
    private DurableStore.Entry claimCall(String id, Instant now) {
        var entry = store.claim(CALL, id, now, now.plusSeconds(60));
        return entry == null || ToolCallStatus.valueOf(entry.status()).isTerminal() ? null : entry;
    }

    /** 校验等待条件和冻结内容，恢复实际调用需要的工具与身份；本轮无需执行时返回 null。 */
    private PreparedCall prepareExecution(DurableStore.Entry entry, ToolCallStatus previous, Instant now) {
        var data = entry.data();
        if (!now.isBefore(Instant.parse(data.path("expiresAt").asText()))) {
            finish(entry, ToolCallStatus.EXPIRED, ToolResult.failure("调用等待已过期"));
            return null;
        }
        if (previous == ToolCallStatus.CONFIRMATION_REQUIRED) {
            store.update(CALL, entry, previous.name(), Instant.parse(data.path("expiresAt").asText()), null, data);
            return null;
        }
        boolean parentEnded = store.require(DurableStore.Kind.RUN, entry.runId()).data().has("result");
        if (parentEnded && Set.of(ToolCallStatus.READY, ToolCallStatus.CONFIRMATION_REQUIRED).contains(previous)) {
            finish(entry, ToolCallStatus.FAILED, ToolResult.failure("所属运行已经结束，未提交此调用"));
            return null;
        }
        try {
            validateFrozen(entry);
        } catch (SecurityException rejected) {
            throw new CallValidationException(rejected);
        }
        var tool = registry.get(data.path("tool").asText());
        var prepared = new PreparedCall(tool, registry.definition(tool.name()), decode(data.path("context"), entry.id()), parentEnded);
        // 状态与记录必须一致；在进入外部调用阶段前拒绝损坏记录，避免错误地首次提交。
        if (previous == ToolCallStatus.WAITING) requirePollingTool(prepared, data);
        if (previous == ToolCallStatus.READY && data.hasNonNull("jobId"))
            throw new IllegalStateException("READY 调用不应已有外部 jobId");
        return prepared;
    }

    /** 外部调用前保存 EXECUTING；竞争失败或已被接管时返回 null，避免继续提交。 */
    private DurableStore.Entry markExecuting(DurableStore.Entry entry, Instant now) {
        if (!store.update(CALL, entry, ToolCallStatus.EXECUTING.name(), now.plusSeconds(60), now.plusSeconds(60), entry.data()))
            return null;
        var executing = store.require(CALL, entry.id());
        return executing.fence() == entry.fence() ? executing : null;
    }

    private record PreparedCall(AgentTool tool, ToolDefinition definition, ToolContext context, boolean parentEnded) {}

    /** 仅由提交前的授权／契约／资源检查产生，业务工具异常不能归入此类。 */
    private static final class CallValidationException extends RuntimeException {
        private CallValidationException(SecurityException cause) { super(cause); }
    }

    /** 工作池拒绝接收，尚未发起外部请求；恢复原状态并安排重试。 */
    private void retryUnsubmittedCall(DurableStore.Entry entry, ToolCallStatus previous) {
        var current = store.require(CALL, entry.id());
        if (current.fence() == entry.fence()) {
            log.debug("工具工作池已满，延后调用 runId={} invocationId={}", entry.runId(), entry.id());
            store.update(CALL, current, previous.name(), clock.instant().plusSeconds(5), null, current.data());
        }
    }

    /** 明确的提交前拒绝保存 DENIED；不把业务工具或存储故障当成授权拒绝。 */
    private void handleInvalidCall(DurableStore.Entry entry, CallValidationException failure) {
        var current = store.require(CALL, entry.id());
        if (current.fence() != entry.fence() || ToolCallStatus.valueOf(current.status()).isTerminal()) return;
        log.info("工具提交前校验未通过 runId={} invocationId={} reason={}", entry.runId(), entry.id(), failure.getCause().getMessage());
        finish(current, ToolCallStatus.FAILED, new ToolResult(false, null, "授权、契约或目标资源已变化", "DENIED", null));
    }

    /** 提交前的系统异常结束调用；进入外部调用阶段后的故障保留 UNKNOWN，安排查单。 */
    private void handleExecutionFailure(DurableStore.Entry entry, ObjectNode data, boolean externalStarted, RuntimeException failure) {
        try {
            var current = store.require(CALL, entry.id());
            if (current.fence() != entry.fence() || ToolCallStatus.valueOf(current.status()).isTerminal()) return;
            current.data().put("httpRequests", data.path("httpRequests").asInt());
            if (externalStarted)
                store.update(CALL, current, ToolCallStatus.UNKNOWN.name(), clock.instant().plusSeconds(60), null, current.data());
            else finish(current, ToolCallStatus.FAILED, new ToolResult(false, null, "工具执行准备失败", "EXECUTION_ERROR", null));
        } catch (RuntimeException persistenceFailure) {
            if (persistenceFailure != failure) failure.addSuppressed(persistenceFailure);
            // 未能提交处理结果，由后台任务／请求入口记录，不能用落库故障覆盖原始执行异常。
            throw failure;
        }
        log.error("工具推进异常 runId={} invocationId={} externalStarted={}", entry.runId(), entry.id(), externalStarted, failure);
    }

    /**
     * 实际调用业务工具：首次提交、按 jobId 轮询，或按 invocationId 查单。
     */
    private ToolExecution executeToolCall(PreparedCall prepared, ToolCallStatus previous, ObjectNode data) {
        return switch (previous) {
            case READY -> submitCall(prepared, data);
            case WAITING -> pollCall(prepared, data);
            case UNKNOWN, EXECUTING -> recoverCall(prepared, data);
            default -> throw new IllegalStateException("当前状态不允许执行工具调用: " + previous);
        };
    }

    /** READY：首次提交，同步工具直接执行，异步工具提交外部任务。 */
    private ToolExecution submitCall(PreparedCall prepared, ObjectNode data) {
        if (prepared.tool() instanceof AsyncAgentTool async) {
            return remote(() -> async.submit(data.get("arguments"), prepared.context()), data);
        }
        return executeSyncTool(prepared, data);
    }

    /** WAITING：只允许异步工具按已保存的 jobId 轮询，不回退到首次提交。 */
    private ToolExecution pollCall(PreparedCall prepared, ObjectNode data) {
        var async = requirePollingTool(prepared, data);
        return remote(() -> async.poll(data.path("jobId").asText(), prepared.context()), data);
    }

    /** UNKNOWN／遗留 EXECUTING：优先查询已有任务；没有任务标识时查单，或受控重试同步调用。 */
    private ToolExecution recoverCall(PreparedCall prepared, ObjectNode data) {
        if (prepared.tool() instanceof AsyncAgentTool async) {
            if (data.hasNonNull("jobId")) return pollCall(prepared, data);
            return remote(() -> async.recover(data.get("arguments"), prepared.context()), data);
        }
        if (!prepared.definition().control().idempotent() || prepared.parentEnded()) {
            return ToolExecution.unknown(60000);
        }
        return executeSyncTool(prepared, data);
    }

    /** 轮询必须对应异步工具和有效的外部任务标识；调用前校验也复用此约束。 */
    private AsyncAgentTool requirePollingTool(PreparedCall prepared, ObjectNode data) {
        if (!(prepared.tool() instanceof AsyncAgentTool async))
            throw new IllegalStateException("同步工具不支持轮询外部任务");
        var jobId = data.path("jobId");
        if (!jobId.isTextual() || jobId.asText().isBlank())
            throw new IllegalStateException("轮询调用缺少有效的外部 jobId");
        return async;
    }

    /** 同步执行共用步骤：提交调用并判断返回结果是否确定。 */
    private ToolExecution executeSyncTool(PreparedCall prepared, ObjectNode data) {
        ToolResult result = remote(() -> prepared.tool().execute(data.get("arguments"), prepared.context()), data);
        if (isUncertainOperationResult(prepared.definition(), result)) {
            return ToolExecution.unknown(60000);
        }
        return ToolExecution.completed(result);
    }

    /** 操作工具的超时、依赖故障或响应损坏，都无法证明外部动作没有发生。 */
    private static boolean isUncertainOperationResult(ToolDefinition definition, ToolResult result) {
        return definition.control().kind() == ToolControl.Kind.OPERATION
                && Set.of("TIMEOUT", "DEPENDENCY_FAILED", "OUTPUT_INVALID").contains(Objects.toString(result.status(), ""));
    }

    /**
     * 保存终态并唤醒 Run，或保存 jobId 和下一次查单时间。
     */
    private void saveExecutionOutcome(DurableStore.Entry entry, ToolDefinition definition,
                                      ToolExecution execution, ObjectNode data) {
        entry.data().put("httpRequests", data.path("httpRequests").asInt());
        if (execution.result() != null) {
            if (execution.result().ok())
                ToolOutputSchema.validateValue(execution.result().data(), definition.outputSchema());
            finish(entry, execution.result().ok() ? ToolCallStatus.SUCCEEDED : ToolCallStatus.FAILED, execution.result());
        } else {
            if (execution.jobId() != null) {
                if (data.hasNonNull("jobId") && !data.path("jobId").asText().equals(execution.jobId()))
                    throw new IllegalStateException("外部任务身份发生变化");
                data.put("jobId", execution.jobId());
            }
            store.update(CALL, entry, (execution.unknown() ? ToolCallStatus.UNKNOWN : ToolCallStatus.WAITING).name(), clock.instant().plusMillis(execution.pollAfterMs()), null, data);
        }
    }


    /**
     * submission/poll 是短调用，长任务必须返回 ACCEPTED；提交超时进入 UNKNOWN。
     */
    private <T> T remote(java.util.concurrent.Callable<T> action, ObjectNode data) {
        var collector = new com.cexpilot.platform.observability.NodeTraceCollector.Session();
        var future = externalCalls.submit(() -> {
            if (collector != null) com.cexpilot.platform.observability.NodeTraceCollector.bind(collector);
            try {
                return action.call();
            } finally {
                com.cexpilot.platform.observability.NodeTraceCollector.clear();
            }
        });
        try {
            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用中断，结果待查", interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException("调用超时，结果待查", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("调用结果待查", failure.getCause());
        } finally {
            var attempts = collector.freezeAndSnapshot();
            data.put("httpRequests", data.path("httpRequests").asInt() + (int) attempts.stream().filter(event -> "http_tool_attempt".equals(event.name())).count());
            for (var event : attempts)
                com.cexpilot.platform.observability.NodeTraceCollector.record(event.name(), event.inputJson(), event.outputJson(), event.durationMs() == null ? 0 : event.durationMs(), event.error());
        }
    }

    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        externalCalls.shutdownNow();
    }

    private void finish(DurableStore.Entry entry, ToolCallStatus status, ToolResult result) {
        var data = entry.data();
        data.set("result", json.valueToTree(result));
        data.put("completedAt", clock.instant().toString());
        if (store.update(CALL, entry, status.name(), null, null, data)) store.wake(entry.runId(), clock.instant());
    }

    private void validateFrozen(DurableStore.Entry entry) {
        var data = entry.data();
        var definition = registry.definition(data.path("tool").asText());
        if (!data.path("contract").asText().equals(registry.contractVersion(definition.name())) || !data.path("fingerprint").asText().equals(fingerprint(data)))
            throw new SecurityException("调用契约已变更");
        if (data.hasNonNull("preparedPlanId")) plans.validateBoundCall(entry);
        else if (definition.control().kind() == ToolControl.Kind.OPERATION
                && plans.mode(store.require(DurableStore.Kind.RUN, entry.runId())) == ToolApprovalMode.PREPARED_PLAN)
            throw new SecurityException("操作调用没有获批方案归属");
        var context = decode(data.get("context"), entry.id());
        authorize(definition, data.get("arguments"), context);
        checkResources(definition, data.get("arguments"), context);
    }

    private void authorize(ToolDefinition definition, JsonNode args, ToolContext context) { checks.authorize(definition, args, context); }
    private void checkResources(ToolDefinition definition, JsonNode args, ToolContext context) { checks.checkResources(definition, args, context); }
    private ObjectNode resourceVersions(ToolDefinition definition, JsonNode args) { return checks.resourceVersions(definition, args); }

    private String summary(ToolDefinition definition, JsonNode args) {
        // 摘要来自可信定义；具体值和资源版本在确认卡中另行完整展示，模型不能替换摘要。
        return definition.control().summary();
    }

    private ObjectNode encode(ToolContext c) {
        var data = json.createObjectNode().put("traceId", c.traceId()).put("conversationId", c.conversationId()).put("agentId", c.agentId())
                .put("visitorId", c.visitorId()).put("timezone", c.timezone() == null ? "UTC" : c.timezone().getId())
                .put("requestTime", c.requestTime() == null ? clock.instant().toString() : c.requestTime().toString());
        data.set("allowedTools", json.valueToTree(new TreeSet<>(c.allowedTools())));
        return data;
    }

    private ToolContext decode(JsonNode c, String callId) {
        var allowed = new HashSet<String>();
        c.path("allowedTools").forEach(n -> allowed.add(n.asText()));
        return new ToolContext(c.path("traceId").asText(), c.path("conversationId").asText(null), ZoneId.of(c.path("timezone").asText()),
                Instant.parse(c.path("requestTime").asText()), c.path("agentId").asText(), c.path("visitorId").asText(null), allowed, callId);
    }

    private String fingerprint(ObjectNode data) {
        var fixed = json.createObjectNode();
        for (String field : List.of("tool", "contract", "arguments", "context", "summary", "resourceVersions", "expiresAt"))
            fixed.set(field, data.get(field));
        for (String field : List.of("preparedPlanId", "preparedCallId", "preparedPlanFingerprint"))
            if (data.has(field)) fixed.set(field, data.get(field));
        return digest(canonical(fixed).toString());
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = json.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(n -> sorted.set(n, canonical(node.get(n))));
            return sorted;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(n -> array.add(canonical(n)));
            return array;
        }
        return node;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
