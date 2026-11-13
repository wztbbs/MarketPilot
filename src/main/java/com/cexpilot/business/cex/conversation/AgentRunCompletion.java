package com.cexpilot.business.cex.conversation;

import com.cexpilot.platform.execution.ExecutionResult;
import com.cexpilot.platform.run.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 只收口原轮次与 Trace，不占用会话执行锁，也不覆盖用户后来轮次的活动上下文。 */
@Component
public class AgentRunCompletion implements RunCompletionListener {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    public AgentRunCompletion(JdbcTemplate jdbc,PlatformTransactionManager transactions) {this.jdbc=jdbc;this.tx=new TransactionTemplate(transactions);}
    @Override public boolean completed(DurableStore.Entry run,ExecutionResult result) {
        var session=run.data().path("session");if(!session.isObject())return true;
        String turn=session.path("turnId").asText(),conversation=session.path("conversationId").asText(),trace=run.data().path("traceId").asText();
        return Boolean.TRUE.equals(tx.execute(ignored->{
            var rows=jdbc.queryForList("SELECT turn_status,trace_id FROM conversation_query WHERE turn_id=? AND conversation_id=? FOR UPDATE",turn,conversation);
            if(rows.isEmpty())return true;
            var row=rows.get(0);if("RUNNING".equals(row.get("turn_status")))return false;
            if(!"WAITING".equals(row.get("turn_status")) || !trace.equals(row.get("trace_id")))return true;
            String status=RunStatus.valueOf(run.status()) == RunStatus.FAILED?"FAILED":result.turnStatus()==null?"SUCCEEDED":result.turnStatus();
            jdbc.update("UPDATE conversation_query SET turn_status=?,answer=?,agent_payload_json=CAST(? AS JSON),finished_at=NOW() WHERE turn_id=? AND turn_status='WAITING'",
                    status,result.answer(),result.agentPayload().toString(),turn);
            jdbc.update("UPDATE ask_trace SET status=?,answer=?,llm_steps=?,tool_calls=?,planner_llm_calls=?,answer_llm_calls=?,http_request_count=?,prompt_tokens=COALESCE(prompt_tokens,0)+?,completion_tokens=COALESCE(completion_tokens,0)+?,cost=CASE WHEN cost IS NULL OR ? IS NULL THEN NULL ELSE cost+? END,finished_at=NOW() WHERE trace_id=? AND status='WAITING'",
                    "FAILED".equals(status)?"FAILED":"SUCCESS",result.answer(),result.steps(),result.toolCallCount(),result.plannerLlmCalls(),result.answerLlmCalls(),result.httpRequestCount(),result.promptTokens(),result.completionTokens(),result.llmUsages()==null?null:com.cexpilot.platform.llm.LlmUsage.totalCost(result.llmUsages()),result.llmUsages()==null?null:com.cexpilot.platform.llm.LlmUsage.totalCost(result.llmUsages()),trace);
            return true;
        }));
    }
}
