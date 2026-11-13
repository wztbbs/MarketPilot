package com.cexpilot.platform.engine.dag;

import com.cexpilot.platform.tool.calculation.CalculationTool;
import com.cexpilot.platform.tool.AgentTool;
import com.cexpilot.platform.observability.NodeTraceCollector;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.tool.ToolContext;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.tool.ToolResult;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 有界 DAG 执行引擎：Kahn 拓扑分层，同层节点（依赖已全部满足）提交固定线程池并行执行，
 * 层间屏障等待全部完成后再调度下一层。
 *
 * 并发纪律（沿用 merchant 项目的约定）：工作线程只读 DagContext，只做依赖检查、
 * 指标 Provider 查询或计算工具执行；结果的写入 DagContext 与 trace 统一由主线程
 * 在层间完成，因此层内不存在共享态写竞争。
 *
 * 失败语义：任一节点失败不中断整体——直接依赖失败的节点记 "skipped: 上游节点失败"；
 * 节点超时 future.cancel(true) 记 failure（HTTP 客户端不保证响应中断，仅作预算兜底）。
 */
@Component
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final DagConfig config;
    private final List<NodeExecutionSupport> nodeSupports;
    private final ExecutorService pool;

    public DagExecutor(ToolRegistry registry, DagConfig config) {
        this(registry, config, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DagExecutor(ToolRegistry registry, DagConfig config, List<NodeExecutionSupport> nodeSupports) {
        this.nodeSupports = List.copyOf(nodeSupports);
        this.registry = registry;
        this.config = config;
        this.pool = Executors.newFixedThreadPool(config.getExecutorThreads());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }
    /** 独立后台执行器的生命周期入口，不共享问答执行池。 */
    public void close() { shutdown(); }

    /** 执行结果：节点产出上下文 + 拓扑层数（作为 ExecutionResult.steps）+ 本轮真实 HTTP 请求数。 */
    public record ExecutionOutcome(DagContext context, int layers, int httpRequests) {
    }

    /** 节点执行身份：会话归属 + Agent/访客标识 + 工具白名单；null 表示旧路径（无身份透传，HTTP 工具会被拒绝）。 */
    public record NodeIdentity(String conversationId, String agentId, String visitorId, java.util.Set<String> allowedTools) {
    }

    /** 单节点的工作线程产出：结果 + 解析后的入参（供主线程落 trace）。 */
    private record NodeOutcome(ToolResult result, String resolvedArgsJson, long durationMs) {
    }

    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink) {
        return execute(plan, traceId, sink, null);
    }

    /**
     * @param requestContext 请求时间上下文（用户时区 + 固定 requestTime），随 ToolContext
     *                       传给每个工具节点；null 表示未携带
     */
    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink,
                                    RequestContext requestContext) {
        return execute(plan, traceId, sink, requestContext, null);
    }

    /**
     * @param bindings 服务端生成的执行期绑定（阶段二）；非 null 时 REUSE_FACT 节点不走 Provider，
     *                 直接把历史事实 payload 播种进 DagContext（nodeId=本轮物理节点 id），
     *                 下游 ReferenceResolver/算子/来源校验零改动
     */
    public ExecutionOutcome execute(DagPlan plan, String traceId, TraceSink sink,
                                    RequestContext requestContext,
                                    NodeBindings bindings) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,Map.of(),(node,result)->{});
    }

    /** 监控执行恢复：种子必须由调用方校验来源、新鲜度与依赖；每个完成节点可持久化检查点。 */
    public ExecutionOutcome executeResumable(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            Map<String,ToolResult> seeds,java.util.function.BiConsumer<PlanNode,ToolResult> completed) {
        return executeInternal(plan,traceId,sink,requestContext,null,seeds,completed);
    }
    public ExecutionOutcome executeResumableIdentified(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            Map<String,ToolResult> seeds,java.util.function.BiConsumer<PlanNode,ToolResult> completed,NodeIdentity identity) {
        return executeInternal(plan,traceId,sink,requestContext,null,seeds,completed,null,identity);
    }
    private ExecutionOutcome executeInternal(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            NodeBindings bindings,Map<String,ToolResult> seeds,
            java.util.function.BiConsumer<PlanNode,ToolResult> completed) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,seeds,completed,null);
    }
    public ExecutionOutcome executeIncremental(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            NodeBindings bindings,Map<String,ToolResult> seeds,com.cexpilot.platform.execution.QueryBudget budget) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,seeds,(node,result)->{},budget,null);
    }
    public java.util.Set<String> readOnlyTools() { return registry.readTools(); }
    public ExecutionOutcome executeIncremental(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            NodeBindings bindings,Map<String,ToolResult> seeds,com.cexpilot.platform.execution.QueryBudget budget,NodeIdentity identity) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,seeds,(node,result)->{},budget,identity);
    }
    /** 带节点执行身份的预算执行入口：声明式 Agent 经 PlanEngine 使用，HTTP 工具据此完成鉴权与身份透传。 */
    public ExecutionOutcome executeIdentified(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            com.cexpilot.platform.execution.QueryBudget budget,NodeIdentity identity) {
        return executeInternal(plan,traceId,sink,requestContext,null,Map.of(),(node,result)->{},budget,identity);
    }
    private ExecutionOutcome executeInternal(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            NodeBindings bindings,Map<String,ToolResult> seeds,java.util.function.BiConsumer<PlanNode,ToolResult> completed,
            com.cexpilot.platform.execution.QueryBudget budget) {
        return executeInternal(plan,traceId,sink,requestContext,bindings,seeds,completed,budget,null);
    }
    private ExecutionOutcome executeInternal(DagPlan plan,String traceId,TraceSink sink,RequestContext requestContext,
            NodeBindings bindings,Map<String,ToolResult> seeds,java.util.function.BiConsumer<PlanNode,ToolResult> completed,
            com.cexpilot.platform.execution.QueryBudget budget,NodeIdentity identity) {
        DagContext ctx = new DagContext();
        seeds.forEach(ctx::put);
        // 本轮共享的 HTTP 请求计数器：工作线程进入节点执行前绑定到线程，
        // BinanceClient/OkxClient 每次真实发请求递增（一个节点分页多次请求会记多次）
        java.util.concurrent.atomic.AtomicInteger httpRequests = new java.util.concurrent.atomic.AtomicInteger();
        List<List<PlanNode>> levels = topoLevels(plan);
        for (List<PlanNode> fullLevel : levels) {
            List<PlanNode> level=fullLevel.stream().filter(node->ctx.get(node.id())==null).toList();
            if(budget!=null && budget.remainingMs()==0) {
                for(var node:level) {
                    ctx.put(node.id(),ToolResult.failure("DEADLINE: 未开始执行"));
                    sink.record(new TraceEvent(traceId,"QUERY_NODE_NOT_RUN","query_budget",null,
                            MAPPER.createObjectNode().put("node_id",node.id()).put("reason","DEADLINE").toString(),null,null,null,null,null,"DEADLINE"));
                }
                continue;
            }
            List<Future<NodeOutcome>> futures = new ArrayList<>();
            List<NodeTraceCollector.Session> sessions = new ArrayList<>();
            var durableScope=com.cexpilot.platform.run.RunScope.current();
            for (PlanNode node : level) {
                NodeTraceCollector.Session session = new NodeTraceCollector.Session();
                sessions.add(session);
                futures.add(pool.submit(() -> {
                    if(budget!=null)budget.checkTime();
                    try(var attached=com.cexpilot.platform.run.RunScope.attach(durableScope)) {
                        return runNode(node,ctx,traceId,requestContext,bindings,httpRequests,session,identity);
                    }
                }));
            }
            try {
                for (int i = 0; i < level.size(); i++) {
                    collect(level.get(i), futures.get(i), sessions.get(i), ctx, traceId, sink, bindings,budget);
                    completed.accept(level.get(i),ctx.get(level.get(i).id()));
                }
            } finally {
                for(var future:futures) if(!future.isDone()) future.cancel(true);
                sessions.forEach(NodeTraceCollector.Session::freezeAndSnapshot);
            }
        }
        return new ExecutionOutcome(ctx, levels.size(), httpRequests.get());
    }

    private static String nodeSignature(PlanNode node) {
        return new DagPlan(List.of(node)).toJson().toString();
    }

    /** 工作线程：依赖检查 → 引用解析 → 工具执行；一切异常转为 ToolResult.failure。 */
    private NodeOutcome runNode(PlanNode node, DagContext ctx, String traceId,
                                RequestContext requestContext,
                                NodeBindings bindings,
                                java.util.concurrent.atomic.AtomicInteger httpRequests,
                                NodeTraceCollector.Session session, NodeIdentity identity) {
        var durable=com.cexpilot.platform.run.RunScope.current();
        String key="dag:node:"+node.id();
        String signature=nodeSignature(node);
        var saved=durable==null?null:durable.load(key);
        if(saved!=null) {
            if(!signature.equals(saved.path("signature").asText()))throw new IllegalStateException("恢复节点定义已变化: "+node.id());
            httpRequests.addAndGet(saved.path("httpRequests").asInt());
            return new NodeOutcome(durable.json().convertValue(saved.path("result"),ToolResult.class),saved.path("args").asText(null),saved.path("durationMs").asLong());
        }
        // 指标 Provider 节点没有工具调用记录，独立计入持久化预算；工具节点由网关计数。
        if(durable!=null && node.operation()!=null)durable.admitTool();
        var nodeRequests=new java.util.concurrent.atomic.AtomicInteger();
        com.cexpilot.platform.observability.HttpRequestCounter.bind(nodeRequests);
        NodeTraceCollector.bind(session);
        try {
            var outcome=doRunNode(node, ctx, traceId, requestContext, bindings, identity);
            if(durable!=null) {
                var value=durable.json().createObjectNode().put("signature",signature).put("args",outcome.resolvedArgsJson())
                        .put("durationMs",outcome.durationMs()).put("httpRequests",nodeRequests.get());
                value.set("result",durable.json().valueToTree(outcome.result()));durable.save(key,value);
            }
            return outcome;
        } finally {
            httpRequests.addAndGet(nodeRequests.get());
            com.cexpilot.platform.observability.HttpRequestCounter.clear();
            NodeTraceCollector.clear();
        }
    }

    private NodeOutcome doRunNode(PlanNode node, DagContext ctx, String traceId,
                                  RequestContext requestContext,
                                  NodeBindings bindings, NodeIdentity identity) {
        long start = System.currentTimeMillis();
        for (String dep : node.dependsOn()) {
            ToolResult depResult = ctx.get(dep);
            if (depResult == null || !depResult.ok()) {
                log.warn("节点 {} 执行器 {} 跳过: 上游节点 {} 失败", node.id(), executionName(node), dep);
                return new NodeOutcome(ToolResult.failure("skipped: 上游节点失败"),
                        null, System.currentTimeMillis() - start);
            }
        }
        JsonNode resolvedArgs = null;
        ToolResult result;
        try {
            if (node.operation() != null) {
                result = support(node).execute(node, ctx, requestContext, bindings);
                return new NodeOutcome(result, node.args().toString(), System.currentTimeMillis() - start);
            }
            AgentTool tool = registry.get(node.tool());
            if (tool instanceof CalculationTool calculation) {
                for (var extension : nodeSupports) extension.beforeTool(node, ctx, tool);
                for (ReferenceResolver.Ref ref : ReferenceResolver.findRefs(node.args())) {
                    ToolResult source = ctx.get(ref.nodeId());
                    if (source != null && source.ok()) calculation.validateSource(source.data());
                }
            }
            var activeRun=com.cexpilot.platform.run.RunScope.current();
            var frozen=activeRun==null?null:activeRun.frozenArguments(node.tool(),"dag:"+node.id());
            resolvedArgs = frozen==null?registry.prepareArguments(node.tool(), ReferenceResolver.resolve(node.args(), ctx)):frozen;
            result = registry.invoke(node.tool(), resolvedArgs, identity == null
                    ? new ToolContext(traceId, null,
                            requestContext == null ? null : requestContext.userZone(),
                            requestContext == null ? null : requestContext.requestTime(), "system_readonly", null, registry.readTools())
                    : new ToolContext(traceId, identity.conversationId(),
                            requestContext == null ? null : requestContext.userZone(),
                            requestContext == null ? null : requestContext.requestTime(),
                            identity.agentId(), identity.visitorId(), identity.allowedTools()), "dag:"+node.id());
            if (tool instanceof CalculationTool) {
                for (var extension : nodeSupports) result = extension.afterTool(node, ctx, tool, result);
            }
        } catch (com.cexpilot.platform.run.RunSuspended | com.cexpilot.platform.run.RunLeaseLostException | com.cexpilot.platform.execution.QueryBudget.Exhausted waiting) { throw waiting; }
        catch (RuntimeException e) {
            log.warn("节点执行失败 traceId={} nodeId={} executor={}", traceId, node.id(), executionName(node), e);
            return new NodeOutcome(ToolResult.failure((node.operation() == null ? "工具执行异常: " : "指标查询失败: ") + e.getMessage()),
                    String.valueOf(resolvedArgs == null ? node.args() : resolvedArgs),
                    System.currentTimeMillis() - start);
        }
        if (!result.ok()) {
            // 工具内部已把异常转成 failure（如交易所 451），异常路径的 warn 不会触发，这里必须补一条
            log.warn("节点 {} 工具 {} 执行失败: {}", node.id(), node.tool(), result.error());
        }
        return new NodeOutcome(result,
                resolvedArgs == null ? null : resolvedArgs.toString(),
                System.currentTimeMillis() - start);
    }

    /** 主线程回收：带超时等待 → 写 DagContext → 落 trace；REUSE_FACT 节点落 FACT_REUSED，不伪装成 METRIC_RESULT。 */
    private void collect(PlanNode node, Future<NodeOutcome> future, NodeTraceCollector.Session session,
                         DagContext ctx,
                         String traceId, TraceSink sink,
                         NodeBindings bindings,com.cexpilot.platform.execution.QueryBudget budget) {
        NodeOutcome outcome;
        try {
            outcome = future.get(budget==null?config.getNodeTimeoutMs():Math.min(config.getNodeTimeoutMs(),budget.remainingMs()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            var activeRun=com.cexpilot.platform.run.RunScope.current();
            if(activeRun!=null && node.operation()==null && activeRun.frozenArguments(node.tool(),"dag:"+node.id())!=null)throw new com.cexpilot.platform.run.RunSuspended(com.cexpilot.platform.run.RunScope.invocationId(activeRun.runId(),"dag:"+node.id()));
            log.warn("节点 {} 执行超时（{}ms），按失败处理", node.id(), config.getNodeTimeoutMs());
            outcome = new NodeOutcome(
                    ToolResult.failure("节点执行超时（" + config.getNodeTimeoutMs() + "ms）"),
                    node.args() == null ? null : node.args().toString(), config.getNodeTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("等待节点执行时被中断: " + node.id(), e);
        } catch (ExecutionException e) {
            if(e.getCause() instanceof com.cexpilot.platform.run.RunSuspended waiting) throw waiting;
            // 节点已处理可降级的工具失败；越过节点边界的控制信号和系统故障继续上抛。
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("节点任务异常: " + node.id(), e.getCause());
        }
        ToolResult result = outcome.result();
        // 节点执行期间的子调用（如 MCP tools/call）先于节点自身事件落库，保持时序。
        // 冻结后取快照：节点超时场景下工作线程可能仍存活，迟到写入被丢弃，主线程不并发遍历。
        for (NodeTraceCollector.Draft draft : session.freezeAndSnapshot()) {
            sink.record(TraceEvent.toolCall(traceId, draft.name(), draft.inputJson(),
                    draft.outputJson(), draft.durationMs(), draft.error()));
        }
        if (node.operation() != null) {
            sink.record(support(node).event(node, result, bindings, traceId, outcome.durationMs()));
        } else {
            sink.record(TraceEvent.toolCall(traceId, node.tool(), eventInput(node.id(), outcome.resolvedArgsJson()),
                    result.toMessageContent(), outcome.durationMs(), result.ok() ? null : result.error()));
        }
        ctx.put(node.id(), result);
    }

    private NodeExecutionSupport support(PlanNode node) {
        return nodeSupports.stream().filter(s -> s.supports(node.operation())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有类型化节点执行器: " + node.operation().executionName()));
    }

    private static String executionName(PlanNode node) {
        return node.operation() == null ? node.tool() : node.operation().executionName();
    }

    private static String eventInput(String nodeId, String resolvedArgsJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("node_id", nodeId);
        try {
            node.set("args", resolvedArgsJson == null
                    ? MAPPER.createObjectNode() : MAPPER.readTree(resolvedArgsJson));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            node.put("args", resolvedArgsJson);
        }
        return node.toString();
    }

    /** Kahn 拓扑分层：每轮 indegree=0 的节点为一层，同层互不依赖可并行（plan 已通过校验无环）。 */
    private List<List<PlanNode>> topoLevels(DagPlan plan) {
        Map<String, Integer> indegree = new HashMap<>();
        for (PlanNode node : plan.nodes()) {
            indegree.put(node.id(), node.dependsOn().size());
        }
        List<List<PlanNode>> levels = new ArrayList<>();
        List<PlanNode> remaining = new ArrayList<>(plan.nodes());
        while (!remaining.isEmpty()) {
            List<PlanNode> ready = remaining.stream()
                    .filter(n -> indegree.get(n.id()) == 0)
                    .toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("plan 依赖图存在环（应已在 PlanValidator 拦截）");
            }
            levels.add(ready);
            for (PlanNode node : ready) {
                remaining.remove(node);
                remaining.forEach(r -> {
                    if (r.dependsOn().contains(node.id())) {
                        indegree.merge(r.id(), -1, Integer::sum);
                    }
                });
            }
        }
        return levels;
    }
}
