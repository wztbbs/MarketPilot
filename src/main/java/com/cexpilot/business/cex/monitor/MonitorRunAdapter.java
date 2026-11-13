package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.plan.*;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.run.*;
import com.cexpilot.business.cex.conversation.*;
import com.cexpilot.platform.llm.LlmUsage;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.List;

/** 对话规划的恢复入口；后台规则周期和通知生命周期继续归监控业务。 */
@Component
public class MonitorRunAdapter implements AgentRunAdapter {
    public record Input(ConversationService.TurnHandle turn) implements AgentRunContext.PrivateInput {}
    private final PlanEngine engine;private final ConversationRunCommitter committer;
    private final ObjectMapper json;private final QueryExecutionConfig config;
    public MonitorRunAdapter(PlanEngine engine,ConversationRunCommitter committer,ObjectMapper json,QueryExecutionConfig config){this.engine=engine;this.committer=committer;this.json=json;this.config=config;}
    public String id(){return "cex-monitor";}public String version(){return "monitor-conversation-v1";}
    public boolean supports(ConversationAgent agent){return agent instanceof MonitorAgent;}
    public QueryExecutionConfig limits(ConversationAgent agent){return config;}
    public JsonNode captureInput(ConversationAgent agent,AgentRunContext run){var data=json.createObjectNode();data.set("turn",committer.capture(run));return data;}
    public AgentRunContext.PrivateInput restoreInput(ConversationAgent agent,JsonNode saved){return new Input(saved.path("turn").isNull()?null:json.convertValue(saved.path("turn"),ConversationService.TurnHandle.class));}
    public RunStep executeSegment(ConversationAgent agent,RunSegment segment){
        var checkpoint=segment.checkpoint();ExecutionResult result;
        if(checkpoint!=null && "MONITOR_COMPLETE".equals(checkpoint.phase()))result=json.convertValue(checkpoint.state().get("result"),ExecutionResult.class);
        else if(checkpoint!=null && "MONITOR_ACCEPTED".equals(checkpoint.phase())){
            var usages=json.convertValue(checkpoint.state().get("usages"),new com.fasterxml.jackson.core.type.TypeReference<List<LlmUsage>>(){});
            result=((MonitorAgent)agent).planHooks().complete(new PlanHooks.PlanCompletion(segment.context(),checkpoint.state().get("candidate"),usages,usages.size()));
        }else result=engine.execute(agent,segment.context());
        var saved=json.createObjectNode();saved.set("result",json.valueToTree(result));segment.checkpoint(new RunCheckpoint("MONITOR_COMPLETE",saved));
        return RunStep.completed(result);
    }
    public boolean commit(RunCompletion completion){return committer.commit(completion,((Input)completion.context().privateInput()).turn(),null,null);}
}
