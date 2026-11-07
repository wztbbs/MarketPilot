package com.cexpilot.agent;

import com.cexpilot.conversation.PlannerInput;
import com.cexpilot.runtime.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.util.List;

/** 兼容现有会话的上下文投影；共享条件摘要，保留各业务状态的命名空间。 */
@Component
public class AgentContextAssembler {
    private static final ObjectMapper JSON = new ObjectMapper();
    public ObjectNode context(String history,PlannerInput input,JsonNode state,RequestContext request) {
        var result=JSON.createObjectNode();
        result.put("current_agent",state==null?"query":state.path("active_agent").asText("query"));
        result.put("recent_dialogue",clip(history,2200));
        result.set("monitor_context",state==null?JSON.createObjectNode():state.path("monitor_context"));
        var task=result.putObject("query_task");
        if(input!=null && input.currentTask()!=null) {
            task.put("question",input.currentTask().originQuestion()).put("status",input.currentTask().status());
        }
        if(input!=null && input.activeFrame()!=null) {
            var targets=task.putArray("targets");
            input.activeFrame().targets().stream().limit(8).forEach(t->{
                var target=targets.addObject().put("metric",t.metric()).put("exchange",t.exchange());
                target.set("instrument",t.instrument()); target.set("time",t.timeExpression());
            });
        }
        if (input != null) task.put("query_context",com.cexpilot.conversation.ConversationContextService.rewriteContext(input));
        result.put("timezone",request.userZone()==null?java.time.ZoneOffset.ofHours(8).getId():request.userZone().getId()).put("request_time",request.requestTime().toString());
        return result;
    }
    public ObjectNode routerContext(ObjectNode full) {
        var result=full.deepCopy();
        // Router 不加载完整 Plan、目录或历史事实，保留近期步骤和候选条件的简短摘要。
        var monitor=JSON.createObjectNode();
        JsonNode prior=full.path("monitor_context");
        for(String key:List.of("last_question","last_reply","status")) if(prior.has(key)) monitor.set(key,prior.get(key));
        if(prior.has("candidate")) {
            monitor.set("conditions",prior.at("/candidate/monitor/conditions"));
            var targets = monitor.putArray("targets");
            for (var metric : prior.at("/candidate/plan/metrics")) {
                var target = targets.addObject();
                target.set("instrument",metric.path("instrument"));
                target.set("exchanges",metric.path("exchanges"));
            }
        }
        if(prior.has("confirmation")) monitor.put("draft_status",prior.at("/confirmation/status").asText());
        result.set("monitor_context",monitor); return result;
    }
    private static String clip(String text,int max) { return text==null?"":text.length()<=max?text:text.substring(text.length()-max); }
}
