package com.cexpilot.agent;

import com.cexpilot.conversation.PlanningContext;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 兼容现有会话的上下文投影；共享条件摘要，保留各业务状态的命名空间。 */
@Component
public class AgentContextAssembler {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 组装执行 Agent 阅读的完整对话上下文投影：当前 Agent、近期对话、监控上下文、
     * 当前查询任务摘要和请求时间基准。仅在路由确定后调用，供被选中的 Agent 使用。
     *
     * @param history 服务端渲染的历史问答文本；超过 2200 字符时仅保留尾部；可为 null
     * @param input   Query 结构化规划输入；取当前任务与活动 definition 的目标摘要，可为 null
     * @param state   服务端持久化的路由/监控状态（active_agent、monitor_context 等）；可为 null
     * @param request 请求时间上下文；userZone 缺省时回落到 UTC+8，requestTime 必须非空
     * @return 投影后的上下文 JSON；含 current_agent/recent_dialogue/monitor_context/query_task/timezone/request_time
     */
    public ObjectNode context(String history,PlanningContext input,JsonNode state,RequestContext request) {
        var result=JSON.createObjectNode();
        result.put("current_agent",state==null?"query":state.path("active_agent").asText("query"));
        result.put("recent_dialogue",clip(history,2200));
        result.set("monitor_context",state==null?JSON.createObjectNode():state.path("monitor_context"));
        var task=result.putObject("query_task");
        if(input!=null && input.currentVersion()!=null) {
            task.put("question",input.currentVersion().originQuestion()).put("status",input.currentVersion().status());
        }
        if(input!=null && input.activeDefinition()!=null) {
            var targets=task.putArray("targets");
            input.activeDefinition().targets().stream().limit(8).forEach(t->{
                var target=targets.addObject().put("metric",t.metric()).put("exchange",t.exchange());
                target.set("instrument",t.instrument()); target.set("time",t.timeExpression());
            });
        }
        if (input != null) task.put("query_context",com.cexpilot.conversation.ConversationContextService.rewriteContext(input));
        result.put("timezone",request.userZone()==null?java.time.ZoneOffset.ofHours(8).getId():request.userZone().getId()).put("request_time",request.requestTime().toString());
        return result;
    }
    /**
     * Router 专用薄视图：通用字段（current_agent/timezone/request_time/recent_dialogue）
     * 加上 routingState 各命名空间的原样透传。底座不解析任何 Agent 私有结构的内部字段，
     * 新增 Agent 的命名空间自动随透传进入路由视野。
     *
     * @param history 服务端渲染的历史问答文本；超过 2200 字符时仅保留尾部；可为 null
     * @param state   enrich 刷新后的路由状态；各命名空间（除 active_agent 映射为 current_agent 外）原样透传，可为 null
     * @param request 请求时间上下文；userZone 缺省时回落到 UTC+8，requestTime 必须非空
     * @return 路由决策所需的视图 JSON
     */
    public ObjectNode routerView(String history,JsonNode state,RequestContext request) {
        var result=JSON.createObjectNode();
        result.put("current_agent",state==null?"query":state.path("active_agent").asText("query"));
        if(state!=null && state.isObject()) state.fields().forEachRemaining(field->{
            if(!"active_agent".equals(field.getKey())) result.set(field.getKey(),field.getValue());
        });
        result.put("timezone",request.userZone()==null?java.time.ZoneOffset.ofHours(8).getId():request.userZone().getId())
                .put("request_time",request.requestTime().toString());
        result.put("recent_dialogue",clip(history,2200));
        return result;
    }
    /** 截断到末尾 max 字符；null 归一为空串。 */
    private static String clip(String text,int max) { return text==null?"":text.length()<=max?text:text.substring(text.length()-max); }
}
