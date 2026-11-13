package com.cexpilot.platform.llm;

import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.observability.TraceEvent;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;

/** 模型调用服务：预算、用量、Trace 与运行检查点；供路由、规划及回答阶段使用。 */
@Component
public class LlmCallService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final LlmClient client;
    private final LlmConfig config;
    private final PromptStore prompts;
    public LlmCallService(@Qualifier(com.cexpilot.platform.llm.LlmClient.NORMAL_LLM_CLIENT) LlmClient client,LlmConfig config,PromptStore prompts) {
        this.client=client; this.config=config; this.prompts=prompts;
    }
    /**
     * 发起一次 LLM 调用并完整落 trace（请求消息、模型参数、响应、耗时与 token）。
     * router/query_continue/agent_* 阶段会先占用共享预算；失败调用也计入 usage 并记录错误事件。
     *
     * @param stage    调用阶段标识（router/query_continue/agent_plan/agent_answer 等）
     * @param prompt   提示词模板名，用于记录版本指纹
     * @param messages 完整对话消息
     * @param traceId  本轮 Trace 标识
     * @param sink     Trace 写入器
     * @param usages   累计用量列表，本次调用的 usage 会追加进去
     * @return 模型响应；调用异常原样上抛（已先落错误事件）
     */
    public ChatResponse call(String stage,String prompt,List<ChatMessage> messages,String traceId,TraceSink sink,List<LlmUsage> usages) {
        var durable=com.cexpilot.platform.run.RunScope.current();
        String checkpoint=durable==null?null:durable.modelKey(stage);
        if(durable!=null) {
            durable.checkLease();var saved=durable.load(checkpoint);
            if(saved!=null) {
                if(!prompts.version(prompt).equals(saved.path("promptVersion").asText())) throw new IllegalStateException("模型检查点的提示词版本已变化");
                var replay=durable.json().convertValue(saved.get("response"),ChatResponse.class);
                usages.add(LlmUsage.of("normal",config.getNormal(),replay));
                return replay;
            }
        }
        var budget=(durable!=null||"router".equals(stage)||"query_continue".equals(stage)||stage.startsWith("agent_"))?com.cexpilot.platform.execution.QueryBudget.current():null;
        if(budget!=null)budget.beforeModel("query_continue".equals(stage)||"agent_plan".equals(stage)||stage.startsWith("agent_prepare_"));
        if(durable!=null)durable.saveBudget(true);
        var input=JSON.createObjectNode().put("stage",stage).put("attempt",usages.size()+1)
                .put("model",config.getNormal().getModel()).put("model_role","normal").put("prompt_version",prompts.version(prompt));
        input.set("messages",LlmTraceSerializer.messagesToArray(messages));
        input.set("request_parameters",client.requestParameters(null));
        long start=System.nanoTime();
        ChatResponse response;
        try { response=budget==null?client.chat(List.copyOf(messages),null): QueryBudget.invoke(()->client.chat(List.copyOf(messages),null));
            if(budget!=null)budget.observe(response); }
        catch (RuntimeException error) {
            usages.add(LlmUsage.of("normal",config.getNormal(),null));
            sink.recordFailure(TraceEvent.llmCall(traceId,stage,input.toString(),LlmTraceSerializer.failureToJson(error),(System.nanoTime()-start)/1_000_000,
                    null,null,null,null,error.getMessage()), error);
            throw error;
        }
        if(durable!=null) {
            durable.saveBudget();var saved=JSON.createObjectNode().put("promptVersion",prompts.version(prompt));
            saved.set("response",JSON.valueToTree(response));saved.set("messages",LlmTraceSerializer.messagesToArray(messages));durable.save(checkpoint,saved);
        }
        usages.add(LlmUsage.of("normal",config.getNormal(),response));
        sink.record(TraceEvent.llmCall(traceId,stage,input.toString(),LlmTraceSerializer.responseToJson(response),
                (System.nanoTime()-start)/1_000_000,response.promptTokens(),response.completionTokens(),response.ttftMs(),response.cachedTokens(),null));
        return response;
    }
}
