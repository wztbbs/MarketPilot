package com.cexpilot.monitor;

import com.cexpilot.agent.*;
import com.cexpilot.llm.LlmUsage;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

/** 对话阶段只规划并保存待确认草稿；确认、调度、恢复与通知沿用业务服务。 */
@Component
public class MonitorAgent implements ConversationAgent {
    public static final AgentDefinition DEFINITION = new AgentDefinition("monitor", "monitor", "持续行情监控意图及待确认规则规划；具体支持范围由监控规划器校验");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MonitorPlanner monitor;
    private final PromptStore prompts;
    private final MonitorConversationFlow flow;
    @Autowired
    public MonitorAgent(MonitorPlanner monitor, PromptStore prompts, MonitorConversationFlow flow) {
        this.monitor=monitor; this.prompts=prompts; this.flow=flow;
    }
    @Override public AgentDefinition definition() { return DEFINITION; }
    @Override public String promptVersion() { return prompts.version(MonitorPlanner.PROMPT); }
    @Override public JsonNode enrichRoutingState(String visitor, JsonNode state) {
        return flow!=null && visitor!=null ? flow.enrichState(visitor,state) : state;
    }
    @Override public ExecutionResult execute(AgentRunContext run) {
        var question=run.question(); var context=run.conversationContext(); var traceId=run.traceId();
        var sink=run.sink(); var progress=run.progress(); var visitor=run.visitor();
        var session=run.session(); var request=run.request();
        if(progress!=null) progress.accept("正在解析监控条件，生成后需要你确认才会启用…");
        MonitorPlanner.Outcome outcome;
        try { outcome=monitor.plan(question,context,traceId,sink); }
        catch(MonitorPlanner.Failure failure) {
            throw new ExecutionFailure(failure.getCause(),new TurnCounters(failure.usages.size(),0,0,0,0,0,List.of())
                    .withLlmUsages(failure.usages));
        }
        var result=planningResult("monitor",outcome.status(),outcome.reply(),outcome.candidate(),outcome.usages());
        if(flow!=null && visitor!=null && outcome.candidate()!=null) {
            try {
                var card=flow.saveDraft(visitor,session,question,outcome.candidate(),request.userZone()==null?java.time.ZoneOffset.ofHours(8).getId():request.userZone().getId());
                var payload=((ObjectNode)result.agentPayload()).deepCopy().put("status","PENDING_CONFIRMATION").put("execution_status","NOT_ENABLED");
                payload.set("confirmation",card); payload.set("candidate",card.path("candidate"));
                var text=new StringBuilder("我将监控条件整理为以下规则（全部同时满足）：\n");
                for(var rule:card.path("rules")) text.append("• ").append(rule.path("description").asText()).append('\n');
                text.append("每5分钟检查一次，连续 ").append(card.path("requiredHits").asInt()).append(" 次满足后发邮件；条件恢复后才会再次提醒。\n");
                if(!card.at("/profile/emailBound").asBoolean()) text.append("请填写通知邮箱，并核对后点击确认。当前草稿尚未启用。");
                else text.append("请核对下面的规则和收件邮箱，点击确认后才会启用。");
                if("DRY_RUN".equals(card.path("mailMode").asText())) text.append("\n当前为邮件模拟模式，只记录日志，不会发送真实邮件。");
                result=new ExecutionResult(text.toString(),result.evidence(),0,0,result.promptTokens(),result.completionTokens(),"SUCCEEDED",null,0,
                        result.plannerLlmCalls(),0,0,0,0,List.of()).withLlmUsages(outcome.usages()).withAgent("monitor",payload);
                sink.record(new TraceEvent(traceId,"MONITOR_DRAFT","monitor_flow",null,card.toString(),null,null,null,null,null,null));
            } catch(RuntimeException failure) {
                throw new ExecutionFailure(failure,new TurnCounters(outcome.usages().size(),0,0,0,0,0,List.of())
                        .withLlmUsages(outcome.usages()));
            }
        }
        return result;
    }
    private ExecutionResult planningResult(String agent,String status,String reply,JsonNode candidate,List<LlmUsage> usages) {
        var payload=JSON.createObjectNode().put("status",status).put("execution_status","NOT_CREATED");
        if(candidate!=null) payload.set("candidate",candidate);
        String turn="NEEDS_CLARIFICATION".equals(status)?status:"FAILED".equals(status)?status:"SUCCEEDED";
        return new ExecutionResult(reply,JSON.createArrayNode(),0,0,LlmUsage.knownPromptTokens(usages),LlmUsage.knownCompletionTokens(usages),
                turn,"NEEDS_CLARIFICATION".equals(status)?reply:null,0,usages.size(),0,0,0,0,List.of())
                .withLlmUsages(usages).withAgent(agent,payload);
    }
}
