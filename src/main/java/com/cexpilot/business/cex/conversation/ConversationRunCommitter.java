package com.cexpilot.business.cex.conversation;

import com.cexpilot.business.cex.metric.CompiledPlan;
import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.run.RunCompletion;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 运行恢复后的会话提交。运行结果不是任务版本；以来源轮次状态作为业务提交幂等边界。 */
@Component
public class ConversationRunCommitter {
    private final JdbcTemplate jdbc; private final ConversationService conversations;
    private final TransactionTemplate tx; private final Clock clock; private final ObjectMapper json;
    public ConversationRunCommitter(JdbcTemplate jdbc,ConversationService conversations,PlatformTransactionManager transactions,Clock clock,ObjectMapper json) {
        this.jdbc=jdbc;this.conversations=conversations;this.tx=new TransactionTemplate(transactions);this.clock=clock;this.json=json;
    }
    public JsonNode capture(AgentRunContext context) {
        if(context.session()==null)return json.nullNode();
        var rows=jdbc.queryForList("SELECT q.query_no,s.fencing_token,s.running_turn_id,c.visitor_id FROM conversation_query q JOIN conversation_state s ON s.conversation_id=q.conversation_id JOIN conversation c ON c.conversation_id=q.conversation_id WHERE q.turn_id=? AND q.conversation_id=?",
                context.session().turnId(),context.session().conversationId());
        if(rows.size()!=1 || !Objects.equals(context.visitor(),rows.get(0).get("visitor_id")) || !Objects.equals(context.session().turnId(),rows.get(0).get("running_turn_id")))
            throw new SecurityException("运行输入不属于当前访客或执行轮次");
        var row=rows.get(0);return json.valueToTree(new ConversationService.TurnHandle(((Number)row.get("query_no")).longValue(),context.session().turnId(),((Number)row.get("fencing_token")).longValue()));
    }
    public boolean commit(RunCompletion completion,ConversationService.TurnHandle original,CompiledPlan plan,TaskScope scope) {
        var run=completion.context();if(run.session()==null)return true;
        if(original==null || !original.turnId().equals(run.session().turnId()))throw new IllegalArgumentException("运行缺少原轮次身份");
        return Boolean.TRUE.equals(tx.execute(ignored->{
            String conversation=run.session().conversationId(),turn=run.session().turnId();
            var states=jdbc.queryForList("SELECT last_query_no,running_turn_id,fencing_token,lease_until FROM conversation_state WHERE conversation_id=? FOR UPDATE",conversation);
            var rows=jdbc.queryForList("SELECT turn_status FROM conversation_query WHERE conversation_id=? AND turn_id=? FOR UPDATE",conversation,turn);
            if(rows.isEmpty())return true;String status=(String)rows.get(0).get("turn_status");
            if(!Set.of("RUNNING","WAITING").contains(status))return true;
            ConversationService.TurnHandle handle=original;
            if(!states.isEmpty()) {
                var state=states.get(0);String active=(String)state.get("running_turn_id");
                var lease=jdbc.queryForObject("SELECT lease_until FROM conversation_state WHERE conversation_id=?",(r,n)->r.getObject(1,LocalDateTime.class),conversation);
                // 原 HTTP 请求仍可能提交；不与它争抢正在使用的轮次。
                if("RUNNING".equals(status) && turn.equals(active) && lease!=null && lease.toInstant(ZoneOffset.UTC).isAfter(clock.instant()))return false;
                if(((Number)state.get("last_query_no")).longValue()==original.queryNo() && (active==null || turn.equals(active))) {
                    long token=((Number)state.get("fencing_token")).longValue()+1;
                    jdbc.update("UPDATE conversation_state SET running_turn_id=?,fencing_token=?,lease_until=? WHERE conversation_id=?",turn,token,LocalDateTime.ofInstant(clock.instant().plusSeconds(60),ZoneOffset.UTC),conversation);
                    handle=new ConversationService.TurnHandle(original.queryNo(),turn,token);
                }
            }
            var result=completion.result();String finalStatus=result.turnStatus()==null?"SUCCEEDED":result.turnStatus();
            var context=scope==null?run.request():scope.executionContext(run.request());
            conversations.finishRoutedTurn(conversation,handle,run.question(),finalStatus,result.answer(),run.traceId(),plan,result.evidence(),scope,
                    context.requestTime(),context.userZone(),result.clarify()!=null?result.clarify():"FAILED".equals(finalStatus)?result.answer():null,result.agentType(),result.agentPayload());
            // 恢复时用已落 Trace 的真实模型调用汇总 Router 和执行用量，避免丢失/重复累加 Router。
            var usage=jdbc.queryForMap("SELECT COALESCE(SUM(prompt_tokens),0) p,COALESCE(SUM(completion_tokens),0) c,COALESCE(SUM(CASE WHEN name='router' THEN 1 ELSE 0 END),0) routers FROM trace_event WHERE trace_id=? AND event_type='LLM_CALL'",run.traceId());
            jdbc.update("UPDATE ask_trace SET status=?,answer=?,agent_type=?,llm_steps=?,tool_calls=?,planner_llm_calls=?,answer_llm_calls=?,metric_fetch_count=?,calculation_count=?,http_request_count=?,reused_facts=?,router_llm_calls=?,prompt_tokens=?,completion_tokens=?,cost=NULL,finished_at=NOW() WHERE trace_id=? AND status IN ('RUNNING','WAITING')",
                    "FAILED".equals(finalStatus)?"FAILED":"SUCCESS",result.answer(),result.agentType(),result.steps(),result.toolCallCount(),result.plannerLlmCalls(),result.answerLlmCalls(),result.metricFetchCount(),result.calculationCount(),result.httpRequestCount(),result.reusedFactCount(),usage.get("routers"),usage.get("p"),usage.get("c"),run.traceId());
            return true;
        }));
    }
}
