package com.cexpilot.agent;

import com.cexpilot.config.LlmClientConfig;
import com.cexpilot.config.LlmConfig;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.TraceEvent;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;

/** 路由/监控规划的公共调用审计，不涉及工具执行；失败调用也计入 usage。 */
@Component
public class PlanningLlm {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final LlmClient client;
    private final LlmConfig config;
    private final PromptStore prompts;
    public PlanningLlm(@Qualifier(LlmClientConfig.NORMAL_LLM_CLIENT) LlmClient client,LlmConfig config,PromptStore prompts) {
        this.client=client; this.config=config; this.prompts=prompts;
    }
    /**
     * 发起一次规划类 LLM 调用并完整落 trace（请求消息、模型参数、响应、耗时与 token）。
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
        var budget=("router".equals(stage)||"query_continue".equals(stage)||stage.startsWith("agent_"))?com.cexpilot.runtime.QueryBudget.current():null;
        if(budget!=null)budget.beforeModel("query_continue".equals(stage)||"agent_plan".equals(stage));
        var input=JSON.createObjectNode().put("stage",stage).put("attempt",usages.size()+1)
                .put("model",config.getNormal().getModel()).put("model_role","normal").put("prompt_version",prompts.version(prompt));
        input.set("messages",LlmTraceSerializer.messagesToArray(messages));
        input.set("request_parameters",client.requestParameters(null));
        long start=System.nanoTime();
        ChatResponse response;
        try { response=budget==null?client.chat(List.copyOf(messages),null):com.cexpilot.runtime.QueryBudget.invoke(()->client.chat(List.copyOf(messages),null));
            if(budget!=null)budget.observe(response); }
        catch (RuntimeException error) {
            usages.add(LlmUsage.of("normal",config.getNormal(),null));
            sink.record(TraceEvent.llmCall(traceId,stage,input.toString(),LlmTraceSerializer.failureToJson(error),(System.nanoTime()-start)/1_000_000,
                    null,null,null,null,error.getMessage()));
            throw error;
        }
        usages.add(LlmUsage.of("normal",config.getNormal(),response));
        sink.record(TraceEvent.llmCall(traceId,stage,input.toString(),LlmTraceSerializer.responseToJson(response),
                (System.nanoTime()-start)/1_000_000,response.promptTokens(),response.completionTokens(),response.ttftMs(),response.cachedTokens(),null));
        return response;
    }
}
