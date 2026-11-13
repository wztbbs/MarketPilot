package com.cexpilot.platform.run;

import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.llm.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 一段活跃运行的持久化现场。LLM 检查点固定已接受的决策；工具检查点由独立调用记录保存。 */
public final class RunScope implements AutoCloseable {
    private static final ThreadLocal<RunScope> CURRENT = new ThreadLocal<>();
    private final RunScope prior;
    private final DurableStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final String runId;
    private final long fence;
    private final Map<String, Integer> modelPositions = new HashMap<>();
    public RunScope(DurableStore store, ObjectMapper json, Clock clock, String runId, long fence) {
        this.store = store; this.json = json; this.clock = clock; this.runId = runId; this.fence = fence;
        prior = CURRENT.get(); CURRENT.set(this);
    }
    public static RunScope current() { return CURRENT.get(); }
    public static String invocationId(String runId, String position) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((runId+":"+position).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,48); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** 已接受动作的参数按原调用恢复，不能在一小时后重新从过期的上游证据推导。 */
    public com.fasterxml.jackson.databind.JsonNode frozenArguments(String tool, String position) {
        var entry=store.find(DurableStore.Kind.CALL,invocationId(runId,position));
        if(entry==null)return null;
        if(!tool.equals(entry.data().path("tool").asText()))throw new IllegalStateException("恢复位置的工具已变化");
        return entry.data().path("arguments").deepCopy();
    }
    public RunCheckpoint progress() {
        checkLease();var value=store.require(DurableStore.Kind.RUN,runId).data().get("checkpoint");
        return value==null || value.isNull()?null:json.convertValue(value,RunCheckpoint.class);
    }
    public synchronized void progress(RunCheckpoint checkpoint) {
        java.util.Objects.requireNonNull(checkpoint);
        for(int attempt=0;attempt<12;attempt++) {
            checkLease();var entry=store.require(DurableStore.Kind.RUN,runId);
            if(entry.fence()!=fence || RunStatus.valueOf(entry.status()) != RunStatus.RUNNING)throw new RunLeaseLostException("运行不再持有执行权");
            var data=entry.data();data.set("checkpoint",json.valueToTree(checkpoint));
            if(budget!=null)data.set("budget",budget.snapshot());
            if(store.update(DurableStore.Kind.RUN,entry,entry.status(),entry.nextAttempt(),entry.leaseUntil(),data))return;
        }
        throw new IllegalStateException("无法保存业务检查点");
    }
    public String runId() { return runId; }
    public long fence() { return fence; }
    public void checkLease() { if (!store.holds(runId, fence, clock.instant())) throw new RunLeaseLostException("运行已被接管"); }
    public String modelKey(String stage) { return "model:" + stage + ":" + modelPositions.merge(stage, 1, Integer::sum); }
    public ObjectNode load(String key) { return store.journal(runId, key); }
    public void save(String key, ObjectNode value) { store.checkpoint(runId, fence, key, value, clock.instant()); }
    private final QueryBudget budget = QueryBudget.current();
    public synchronized void admitTool() { if (budget != null) { budget.admitTools(1); saveBudget(); } }
    public synchronized void saveBudget() { saveBudget(false); }
    public synchronized void saveBudget(boolean modelInFlight) {
        if (budget == null) return;
        for (int i=0;i<12;i++) {
            var entry=store.require(DurableStore.Kind.RUN,runId);
            if(entry.fence()!=fence) throw new RunLeaseLostException("运行已被接管");
            var data=entry.data();var snapshot=budget.snapshot();if(modelInFlight)snapshot.put("token_usage_unknown",true);data.set("budget",snapshot);
            if(store.update(DurableStore.Kind.RUN,entry,entry.status(),entry.nextAttempt(),entry.leaseUntil(),data))return;
        }
        throw new IllegalStateException("无法保存运行预算");
    }
    public ObjectMapper json() { return json; }
    /** DAG 工作线程显式继承同一个现场；不使用 InheritableThreadLocal 污染线程池。 */
    public static AutoCloseable attach(RunScope scope) {
        var previous = CURRENT.get(); if (scope == null) CURRENT.remove(); else CURRENT.set(scope);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    @Override public void close() { if (prior == null) CURRENT.remove(); else CURRENT.set(prior); }
}
