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
    public static QueryBudget current(){return CURRENT.get();}
    public static Scope open(QueryExecutionConfig config){
        var prior=CURRENT.get(); if(prior!=null)return new Scope(false);
        CURRENT.set(new QueryBudget(config));return new Scope(true);
    }
    public static final class Scope implements AutoCloseable {
        private final boolean owner;
        private Scope(boolean owner){this.owner=owner;}
        public void close(){if(owner)CURRENT.remove();}
    }
    public static final class Exhausted extends RuntimeException {
        public final String reason;
        public Exhausted(String reason){super(reason);this.reason=reason;}
    }
    /** Agent YAML 只能收紧请求预算；Router 已用的调用仍计入，不重新起算。 */
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
    public QueryExecutionConfig config(){return config;}
    public long remainingMs(){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    public void checkTime(){if(remainingMs()==0)throw new Exhausted("DEADLINE");}
    public void beforeModel(boolean reserveAnswer){
        checkTime();
        if(models+(reserveAnswer?1:0)>=config.getMaxModelCalls())throw new Exhausted("MODEL_BUDGET");
        if(tokens>=config.getMaxObservedTokens() || unknownTokens)throw new Exhausted("TOKEN_BUDGET");
        models++;
    }
    public void observe(ChatResponse response){
        if(response==null || response.promptTokens()==null || response.completionTokens()==null)unknownTokens=true;
        else tokens+=(long)response.promptTokens()+response.completionTokens();
    }
    public void admitTools(int count){checkTime();if(count<0 || tools+count>config.getMaxToolCalls())throw new Exhausted("TOOL_BUDGET");tools+=count;}
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
