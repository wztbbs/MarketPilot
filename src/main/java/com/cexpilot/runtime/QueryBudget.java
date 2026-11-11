package com.cexpilot.runtime;

import com.cexpilot.config.QueryExecutionConfig;
import com.cexpilot.llm.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 同一请求的 Router、Planner（含修复）、后续规划及 Answer 共用；不传给后台 Monitor。 */
public final class QueryBudget {
    private static final ThreadLocal<QueryBudget> CURRENT=new ThreadLocal<>();
    private static final ExecutorService CALLS=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"query-model-call");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private QueryExecutionConfig config;
    private long deadline;
    private final long started=System.nanoTime();
    private int models,tools;
    private long tokens;
    private boolean unknownTokens;
    private QueryBudget(QueryExecutionConfig config){config.validate();this.config=config;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(config.getTimeoutMs());}
    /** @return 当前线程已打开的预算；未打开时为 null（如单测、离线路径） */
    public static QueryBudget current(){return CURRENT.get();}
    /**
     * 为当前线程打开预算作用域；若已有外层预算则返回非 owner 的空作用域（嵌套调用沿用外层预算）。
     *
     * @param config 预算上限配置
     * @return 作用域句柄；close 时仅 owner 会清除线程绑定
     */
    public static Scope open(QueryExecutionConfig config){
        var priorQueryBudget=CURRENT.get(); if(priorQueryBudget!=null)return new Scope(false);
        CURRENT.set(new QueryBudget(config));return new Scope(true);
    }
    /** 预算作用域：owner 负责在线程结束时清除 ThreadLocal 绑定。 */
    public static final class Scope implements AutoCloseable {
        private final boolean owner;
        private Scope(boolean owner){this.owner=owner;}
        public void close(){if(owner)CURRENT.remove();}
    }
    /** 预算耗尽信号；reason 为 DEADLINE / MODEL_BUDGET / TOOL_BUDGET / TOKEN_BUDGET。 */
    public static final class Exhausted extends RuntimeException {
        public final String reason;
        public Exhausted(String reason){super(reason);this.reason=reason;}
    }
    /**
     * 把当前预算的上限收紧为与 limits 逐项比较的较小值（只紧不松，原地生效）。
     * 已消耗的模型/工具/token 计数保留，deadline 取原值与新超时计算的较早者，
     * 且始终从预算创建时刻起算——收紧既不能重置已用额度，也不能延长剩余时间。
     */
    public void tighten(QueryExecutionConfig limits) {
        limits.validate();var capped=new QueryExecutionConfig();
        capped.setMaxModelCalls(Math.min(config.getMaxModelCalls(),limits.getMaxModelCalls()));
        capped.setMaxToolCalls(Math.min(config.getMaxToolCalls(),limits.getMaxToolCalls()));
        capped.setMaxObservedTokens(Math.min(config.getMaxObservedTokens(),limits.getMaxObservedTokens()));
        capped.setTimeoutMs(Math.min(config.getTimeoutMs(),limits.getTimeoutMs()));
        capped.setAdaptiveEnabled(config.isAdaptiveEnabled() && limits.isAdaptiveEnabled());
        capped.setMaxContinuations(Math.min(config.getMaxContinuations(),limits.getMaxContinuations()));
        config=capped;deadline=Math.min(deadline,started+TimeUnit.MILLISECONDS.toNanos(capped.getTimeoutMs()));
    }
    /** @return 当前生效的预算配置（可能已被 tighten 收紧） */
    public QueryExecutionConfig config(){return config;}
    /** @return 距截止时间的剩余毫秒数，已过期为 0 */
    public long remainingMs(){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    /** 时间预算检查；已过期抛 {@link Exhausted}（DEADLINE）。 */
    public void checkTime(){if(remainingMs()==0)throw new Exhausted("DEADLINE");}
    /**
     * 占用一次模型调用额度：先检查时间，再检查调用次数与 token 上限。
     *
     * @param reserveAnswer 为 true 时预留一次回答调用额度（规划类调用使用），即 models+1 即视为占满
     */
    public void beforeModel(boolean reserveAnswer){
        checkTime();
        if(models+(reserveAnswer?1:0)>=config.getMaxModelCalls())throw new Exhausted("MODEL_BUDGET");
        if(tokens>=config.getMaxObservedTokens() || unknownTokens)throw new Exhausted("TOKEN_BUDGET");
        models++;
    }
    /**
     * 记录一次模型响应的 token 消耗；响应或 token 字段缺失时标记 unknownTokens，后续调用将被 TOKEN_BUDGET 拦截。
     */
    public void observe(ChatResponse response){
        if(response==null || response.promptTokens()==null || response.completionTokens()==null)unknownTokens=true;
        else tokens+=(long)response.promptTokens()+response.completionTokens();
    }
    /**
     * 占用工具调用额度。
     *
     * @param count 本次占用的次数，负数或超出上限抛 {@link Exhausted}（TOOL_BUDGET）
     */
    public void admitTools(int count){checkTime();if(count<0 || tools+count>config.getMaxToolCalls())throw new Exhausted("TOOL_BUDGET");tools+=count;}
    /** @return 当前预算消耗快照（已用/剩余的模型调用、工具节点、token 与毫秒数），供注入提示词与载荷 */
    public ObjectNode snapshot(){return new ObjectMapper().createObjectNode().put("model_calls",models).put("tool_nodes",tools)
            .put("observed_tokens",tokens).put("token_usage_unknown",unknownTokens).put("remaining_ms",remainingMs())
            .put("remaining_model_calls",Math.max(0,config.getMaxModelCalls()-models)).put("remaining_tool_nodes",Math.max(0,config.getMaxToolCalls()-tools));}
    /** 只包装实际模型 I/O；超时后阻止后续调度，远端已接收请求不保证能撤销。 */
    public static ChatResponse invoke(Supplier<ChatResponse> call){
        var budget=current();if(budget==null)return call.get();budget.checkTime();
        Future<ChatResponse> future=CALLS.submit(call::get);
        try{return future.get(budget.remainingMs(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){future.cancel(true);throw new Exhausted("DEADLINE");}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("模型调用被中断",e);}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException cause)throw cause;throw new IllegalStateException(e.getCause());}
    }
}
