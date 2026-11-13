package com.cexpilot.platform.agent;

import com.cexpilot.platform.llm.PlanningJson;
import com.cexpilot.platform.llm.ModelOutputException;
import com.cexpilot.platform.llm.LlmCallService;

import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.llm.LlmUsage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 意图路由：根据用户问题与精简上下文选择目标 Agent（query/monitor/声明式 Agent/clarify）。
 * 输出必须是合法 JSON 且 route 在已注册范围内；最多一次修复重试，仍失败则回落为固定澄清。
 */
@Component
public class IntentRouter {
    /** 路由提示词模板名。 */
    public static final String PROMPT="intent_router";
    /**
     * 路由决策结果。
     *
     * @param route            目标对外 route（query/monitor/...）或 "clarify"
     * @param question         仅 clarify 时有值：向用户提出的澄清问题
     * @param usages           本次路由的 LLM 用量（含修复重试）
     * @param resolvedQuestion 仅 query 路由可有：指代消解后的独立问题；缺失时回落为原始问题
     */
    public record Decision(String route,String question,List<LlmUsage> usages,String resolvedQuestion) {
        public Decision(String route,String question,List<LlmUsage> usages) { this(route,question,usages,null); }
    }
    /** 路由失败时携带已累计用量的异常包装，供上层计入本轮计数。 */
    public static class Failure extends RuntimeException {
        public final List<LlmUsage> usages;
        Failure(RuntimeException cause,List<LlmUsage> usages) { super(cause); this.usages=List.copyOf(usages); }
    }
    private final LlmCallService llm;
    private final PromptStore prompts;
    private final com.cexpilot.platform.context.RoutingDialect dialect;
    public IntentRouter(LlmCallService llm,PromptStore prompts) { this(llm, prompts, new com.cexpilot.platform.context.RoutingDialect() {}); }
    public IntentRouter(LlmCallService llm, PromptStore prompts, com.cexpilot.platform.context.RoutingDialect dialect) {
        this.llm=llm; this.prompts=prompts; this.dialect=dialect;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public IntentRouter(LlmCallService llm, PromptStore prompts,
            org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.context.RoutingDialect> dialect) {
        this(llm, prompts, dialect.getIfAvailable(() -> new com.cexpilot.platform.context.RoutingDialect() {}));
    }
    /** @return 路由提示词的版本指纹 */
    public String promptVersion() { return prompts.version(PROMPT); }
    /**
     * 执行一次路由：拼装候选路由描述与上下文调用模型，校验输出 JSON；
     * 输出非法时把错误反馈给模型重试一次，仍失败则返回固定的 clarify 兜底决策。
     *
     * @param question 用户原始问题
     * @param context  Router 专用薄视图（由应用提供的上下文投影）
     * @param traceId  本轮 Trace 标识
     * @param sink     Trace 写入器；每次决策（含失败重试与兜底）都会落 ROUTE_DECISION 事件
     * @param agents   本轮可选的候选 Agent 定义（已按灰度过滤）
     * @return 路由决策；模型调用异常包装为 {@link Failure} 上抛
     */
    public Decision route(String question,JsonNode context,String traceId,TraceSink sink,List<AgentDefinition> agents) {
        var allowedRoutes=new HashSet<String>(); allowedRoutes.add("clarify");
        var descriptions=new StringBuilder();
        for(var agent:agents) { allowedRoutes.add(agent.route()); descriptions.append("- ").append(agent.route()).append(": ").append(agent.description()).append('\n'); }

        var messages=new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(prompts.render(PROMPT,Map.of("agent_routes",descriptions.toString()))));
        var input=new ObjectMapper().createObjectNode();
        input.set("context",orderedContext(context)); input.put("question",question);
        messages.add(ChatMessage.user(input.toString()));
        var usages=new ArrayList<LlmUsage>();
        try {
            for(int attempt=0;attempt<2;attempt++) {
                var response=llm.call("router",PROMPT,messages,traceId,sink,usages);
                try {
                    if(response.hasToolCalls()) throw new ModelOutputException("路由不能调用工具");
                    var parsed=PlanningJson.parse(response.content());
                    PlanningJson.object(parsed,Set.of("route","question","resolved_question"),"路由结果");
                    String route=PlanningJson.text(parsed,"route",16);
                    if(!allowedRoutes.contains(route)) throw new ModelOutputException("route 仅支持已注册的 Agent 或 clarify");
                    String clarify="clarify".equals(route)?PlanningJson.text(parsed,"question",200):null;
                    String resolved = null;
                    if (dialect.supportsRewrite(route)) {
                        // Missing rewrite is a compatible passthrough, not a reason for another LLM call.
                        JsonNode rewritten = parsed.path("resolved_question");
                        resolved = rewritten.isTextual() && !rewritten.asText().isBlank() && rewritten.asText().length() <= 20000
                                ? rewritten.asText() : question;
                    } else if (parsed.has("resolved_question")) {
                        throw new ModelOutputException("当前路由未声明问题改写能力，不能输出 resolved_question");
                    }
                    sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router",null,parsed.toString(),null,null,null,null,null,null));
                    return new Decision(route,clarify,List.copyOf(usages),resolved);
                } catch(ModelOutputException invalid) {
                    sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router",null,response.content(),null,null,null,null,null,invalid.getMessage()));
                    messages.add(ChatMessage.assistant(response.content()==null?"":response.content(),null));
                    messages.add(ChatMessage.user("输出无效："+invalid.getMessage()+"。只返回合法的路由 JSON。"));
                }
            }
            String fallback=dialect.fallbackQuestion();
            var fallbackJson=new ObjectMapper().createObjectNode().put("route","clarify").put("question",fallback);
            sink.record(new TraceEvent(traceId,"ROUTE_DECISION","intent_router","{\"validation_fallback\":true}",fallbackJson.toString(),null,null,null,null,null,null));
            return new Decision("clarify",fallback,List.copyOf(usages));
        } catch(RuntimeException error) { throw new Failure(error,usages); }
    }

    /** 仅调整字段顺序：稳定状态在前，历史和精确请求时间在后；所有字段和值保持不变。 */
    private JsonNode orderedContext(JsonNode context) {
        if(context==null || !context.isObject()) return context;
        var ordered=new ObjectMapper().createObjectNode();
        for(String key:List.of("current_agent","timezone"))
            if(context.has(key)) ordered.set(key,context.get(key));
        context.fields().forEachRemaining(field->{
            if(!ordered.has(field.getKey()) && !Set.of("recent_dialogue","request_time").contains(field.getKey()))
                ordered.set(field.getKey(),field.getValue());
        });
        for(String key:List.of("recent_dialogue","request_time"))
            if(context.has(key)) ordered.set(key,context.get(key));
        return ordered;
    }
}
