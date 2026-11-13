package com.cexpilot.business.cex.query;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.engine.plan.PlanEngine;
import com.cexpilot.platform.execution.*;
import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import com.cexpilot.platform.run.*;
import com.cexpilot.business.cex.conversation.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;

/** Query 继续自驱动；此适配器提供输入冻结、私有输出和幂等业务提交。 */
@Component
public final class QueryRunAdapter implements AgentRunAdapter {
    public record Input(PlanningContext plannerInput,String resolvedQuestion,ConversationService.TurnHandle turn) implements AgentRunContext.PrivateInput {}
    private final PlanEngine engine;private final ConversationRunCommitter committer;
    private final QueryRunCodec codec;private final QueryExecutionConfig config;
    public QueryRunAdapter(PlanEngine engine,ConversationRunCommitter committer,QueryRunCodec codec,QueryExecutionConfig config){this.engine=engine;this.committer=committer;this.codec=codec;this.config=config;}
    public String id(){return "cex-query";}public String version(){return "query-conversation-v1";}
    public boolean supports(ConversationAgent agent){return agent instanceof MarketAnalysisAgent;}
    public QueryExecutionConfig limits(ConversationAgent agent){return config;}
    public JsonNode captureInput(ConversationAgent agent,AgentRunContext run){
        var input=(MarketAnalysisAgent.QueryInput)run.privateInput();
        var data=codec.write(new Input(input==null?null:input.plannerInput(),input==null?null:input.resolvedQuestion(),null));
        data.set("turn",committer.capture(run));return data;
    }
    public AgentRunContext.PrivateInput restoreInput(ConversationAgent agent,JsonNode saved){return codec.read(saved,Input.class);}
    public JsonNode captureOutput(AgentOutput output){return output==null?null:codec.write((QueryOutput)output);}
    public AgentOutput restoreOutput(JsonNode saved){return saved==null || saved.isNull()?null:codec.read(saved,QueryOutput.class);}
    public RunStep executeSegment(ConversationAgent agent,RunSegment segment){
        var checkpoint=segment.checkpoint();
        if(checkpoint!=null && "QUERY_COMPLETE".equals(checkpoint.phase())) {
            var state=checkpoint.state();return RunStep.completed(codec.read(state.path("result"),ExecutionResult.class).withOutput(restoreOutput(state.get("output"))));
        }
        var input=(Input)segment.context().privateInput();var run=segment.context();
        var latest=new java.util.concurrent.atomic.AtomicReference<com.cexpilot.business.cex.metric.CompiledPlan>();
        var restored=new AgentRunContext(run.question(),run.history(),run.traceId(),run.sink(),run.delta(),run.request(),run.progress(),run.session(),run.routingState(),run.conversationContext(),run.visitor(),
                new MarketAnalysisAgent.QueryInput(input.plannerInput(),latest::set,input.resolvedQuestion()));
        ExecutionResult result;
        try {result=engine.execute(agent,restored);}
        catch(ExecutionFailure failure) {
            var scope=failure.output instanceof QueryOutput query?query.taskScope():null;
            throw new ExecutionFailure(failure,failure.counters,new QueryOutput(scope,latest.get()),failure.evidence);
        }
        result=result.withOutput(new QueryOutput(QueryOutput.scope(result),latest.get()));
        var saved=codec.write(result.withOutput(null));var state=segment.scope().json().createObjectNode();state.set("result",saved);state.set("output",captureOutput(result.output()));
        segment.checkpoint(new RunCheckpoint("QUERY_COMPLETE",state));return RunStep.completed(result);
    }
    public boolean commit(RunCompletion completion){
        return committer.commit(completion,((Input)completion.context().privateInput()).turn(),QueryOutput.plan(completion.result()),QueryOutput.scope(completion.result()));
    }
}
