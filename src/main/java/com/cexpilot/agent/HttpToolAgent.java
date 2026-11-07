package com.cexpilot.agent;

import com.cexpilot.dag.ClarificationField;
import com.cexpilot.evidence.EvidenceStore;
import com.cexpilot.llm.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** 声明式只读 Agent。复用公共预算、工具、Trace 和事实库，不接管 Query / Monitor 状态。 */
public final class HttpToolAgent implements ConversationAgent {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ConfiguredAgentDefinition definition;
    private final ToolRegistry tools;
    private final PromptStore prompts;
    private final PlanningLlm llm;
    private final EvidenceStore evidence;
    public HttpToolAgent(ConfiguredAgentDefinition definition,ToolRegistry tools,PromptStore prompts,PlanningLlm llm,EvidenceStore evidence) {
        this.definition=definition;this.tools=tools;this.prompts=prompts;this.llm=llm;this.evidence=evidence;
        prompts.version(definition.prompt());prompts.version("http_tool_agent");prompts.version("http_tool_answer");
        for(String name:definition.allowedTools())if(!tools.isHttp(name))throw new IllegalArgumentException("声明式 Agent 只允许已注册的只读 HTTP Tool: "+name);
    }
    @Override public AgentDefinition definition(){return definition.agent();}
    @Override public com.cexpilot.config.HarnessRolloutConfig.Group rolloutGroup(){return com.cexpilot.config.HarnessRolloutConfig.Group.HTTP_AGENT;}
    @Override public String promptVersion(){
        var version=new StringBuilder(definition.agent().toString()).append(definition.prompt()).append(definition.maxSteps()).append(prompts.version(definition.prompt())).append(prompts.version("http_tool_agent")).append(prompts.version("http_tool_answer"));
        definition.allowedTools().stream().sorted().forEach(name->version.append(name).append(tools.contractVersion(name)));
        var p=definition.limits();version.append(p.getMaxModelCalls()).append(':').append(p.getMaxToolCalls()).append(':').append(p.getTimeoutMs()).append(':').append(p.getMaxObservedTokens());
        return PromptStore.fingerprint(version.toString());
    }
    @Override public ExecutionResult execute(AgentRunContext run) {
        try(var scope=QueryBudget.open(definition.limits())) {
            var budget=QueryBudget.current();budget.tighten(definition.limits());
            return execute(run,budget);
        }
    }
    private ExecutionResult execute(AgentRunContext run,QueryBudget budget) {
        var usages=new ArrayList<LlmUsage>();var facts=JSON.createArrayNode();var saved=new ArrayList<String>();
        var observations=new LinkedHashMap<String,ToolResult>();var seen=new HashSet<Invocation>();
        int toolCalls=0,httpCalls=0,plannerCalls=0,answerCalls=0;
        boolean partial=false;String stop="STEP_BUDGET",lastError=null,answer=null,clarify=null;
        List<ClarificationField> fields=List.of();List<String> selected=List.of();
        var catalog=JSON.createArrayNode();
        definition.allowedTools().stream().sorted().forEach(name->{var item=catalog.addObject().put("name",name).put("description",tools.spec(name).description());
            item.set("input_schema",tools.spec(name).inputSchema());item.set("output_schema",tools.outputSchema(name));});
        String system=prompts.render("http_tool_agent",Map.of("agent_prompt",prompts.render(definition.prompt(),Map.of()),"tools",catalog.toString()));
        try {
            for(int step=0;step<definition.maxSteps();step++) {
                var input=JSON.createObjectNode().put("question",run.question()).put("recent_dialogue",run.history()==null?"":run.history())
                        .put("request_time",run.request().requestTime().toString()).put("timezone",(run.request().userZone()==null?java.time.ZoneOffset.ofHours(8):run.request().userZone()).getId());
                input.set("observations",facts);input.set("budget",budget.snapshot());if(lastError!=null)input.put("validation_error",lastError);
                int before=usages.size();ChatResponse response;
                try {response=llm.call("agent_plan","http_tool_agent",List.of(ChatMessage.system(system),ChatMessage.user(input.toString())),run.traceId(),run.sink(),usages);}
                finally {plannerCalls+=usages.size()-before;}
                try {
                    if(response.hasToolCalls())throw new IllegalArgumentException("仅支持声明的 JSON 动作");
                    var decision=PlanningJson.parse(response.content());
                    PlanningJson.object(decision,Set.of("action","tool","arguments","evidence_refs","clarify"),"Agent 决策");
                    String action=PlanningJson.text(decision,"action",20);
                    if("finish".equals(action)) {
                        var references=refs(decision.path("evidence_refs"));
                        for(String ref:references)if(!fresh(observations.get(ref)))throw new IllegalArgumentException("引用未知、失败或过期证据");
                        if(references.isEmpty() && observations.values().stream().anyMatch(HttpToolAgent::fresh))throw new IllegalArgumentException("结束必须选择已有证据引用");
                        selected=references;partial|=lastError!=null;stop="MODEL_FINISH";break;
                    }
                    if("clarify".equals(action)) {
                        fields=ClarificationField.parse(decision.path("clarify"));
                        if(fields.isEmpty())throw new IllegalArgumentException("澄清需要有效字段");
                        clarify=ClarificationField.question(fields);stop="CLARIFY";break;
                    }
                    if(!"call".equals(action))throw new IllegalArgumentException("未知 Agent 动作");
                    String name=PlanningJson.text(decision,"tool",64);
                    if(!definition.allowedTools().contains(name))throw new IllegalArgumentException("工具未获此 Agent 授权");
                    JsonNode args=tools.prepareArguments(name,resolve(decision.path("arguments"),observations));
                    if(!seen.add(new Invocation(name,args.deepCopy()))){stop="NO_PROGRESS";partial=true;break;}
                    budget.admitTools(1);toolCalls++;
                    if(run.progress()!=null)run.progress().accept("正在调用数据服务…");
                    var context=new ToolContext(run.traceId(),run.session()==null?null:run.session().conversationId(),run.request().userZone()==null?java.time.ZoneOffset.ofHours(8):run.request().userZone(),run.request().requestTime(),
                            definition.agent().id(),run.visitor(),definition.allowedTools());
                    var collector=new NodeTraceCollector.Session();ToolResult result;
                    NodeTraceCollector.bind(collector);
                    try {result=tools.get(name).execute(args,context);}
                    finally {
                        NodeTraceCollector.clear();
                        for(var draft:collector.freezeAndSnapshot()) {
                            httpCalls++;
                            run.sink().record(TraceEvent.toolCall(run.traceId(),draft.name(),draft.inputJson(),draft.outputJson(),draft.durationMs(),draft.error()));
                        }
                    }
                    String ref="e"+toolCalls;observations.put(ref,result);
                    var fact=(ObjectNode)JSON.readTree(result.toMessageContent());fact.put("ref",ref).put("tool",name);
                    if(result.ok() && run.session()!=null && evidence!=null) {
                        try {evidence.saveToolFact(run.session().conversationId(),run.session().turnId(),run.traceId(),definition.agent().id(),name,
                                result.data(),result.provenance(),Instant.now()).ifPresent(stored->{saved.add(stored.factId());fact.put("fact_id",stored.factId());});}
                        catch(RuntimeException persistence){fact.put("persistence","UNAVAILABLE");partial=true;}
                    }
                    facts.add(fact);partial|=!result.ok();lastError=null;
                    run.sink().record(TraceEvent.toolCall(run.traceId(),name,args.toString(),fact.toString(),null,result.error()));
                } catch(IllegalArgumentException invalid) {
                    lastError=invalid.getMessage();
                    run.sink().record(new TraceEvent(run.traceId(),"AGENT_REJECTED",definition.agent().id(),null,null,null,null,null,null,null,lastError));
                }
            }
        } catch(QueryBudget.Exhausted exhausted){stop=exhausted.reason;partial=true;}
        catch(Exception failure){throw failure(failure,usages,plannerCalls,0,toolCalls,httpCalls,saved,facts);}
        if(!Set.of("MODEL_FINISH","CLARIFY").contains(stop))partial=true;
        if(clarify!=null)answer=clarify;
        else {
            var visible=JSON.createArrayNode();
            for(var entry:facts)if(fresh(observations.get(entry.path("ref").asText())) && (selected.isEmpty() || selected.contains(entry.path("ref").asText())))visible.add(entry);
            if(visible.isEmpty()){answer="本次未取得可用证据，无法给出可靠结果。";partial=true;}
            else {
                var input=JSON.createObjectNode().put("question",run.question()).put("stop_reason",stop).put("partial",partial);
                input.set("facts",visible);
                var allowed=new HashSet<String>();visible.forEach(f->allowed.add(f.path("ref").asText()));
                int before=usages.size();
                try {
                    var response=llm.call("agent_answer","http_tool_answer",List.of(ChatMessage.system(prompts.render("http_tool_answer",Map.of())),ChatMessage.user(input.toString())),
                            run.traceId(),run.sink(),usages);
                    if(response.hasToolCalls())throw new IllegalArgumentException("回答不能调用工具");
                    var output=PlanningJson.parse(response.content());PlanningJson.object(output,Set.of("answer","evidence_refs"),"Agent 回答");
                    var references=refs(output.path("evidence_refs"));
                    if(references.isEmpty() || !allowed.containsAll(references) || references.stream().anyMatch(ref->!fresh(observations.get(ref))))throw new IllegalArgumentException("回答缺少有效证据引用");
                    answer=PlanningJson.text(output,"answer",8000);
                } catch(QueryBudget.Exhausted exhausted){stop=exhausted.reason;partial=true;answer="本次执行达到预算或时间限制，尚未完整完成；已取得的证据保留在执行记录中。";}
                catch(IllegalArgumentException invalid){stop="ANSWER_INVALID";partial=true;answer="本次未能生成可靠回答，已保留执行记录。";}
                catch(Exception failure){throw failure(failure,usages,plannerCalls,usages.size()-before,toolCalls,httpCalls,saved,facts);}
                finally {answerCalls=usages.size()-before;}
            }
        }
        var payload=JSON.createObjectNode().put("status",clarify==null?"SUCCEEDED":"NEEDS_CLARIFICATION")
                .put("agent_id",definition.agent().id()).put("execution_status",partial?"PARTIAL":"FINISHED").put("stop_reason",stop);
        payload.set("budget",budget.snapshot());if(!fields.isEmpty())payload.set("clarify",JSON.valueToTree(fields));
        run.sink().record(new TraceEvent(run.traceId(),"AGENT_FINISHED",definition.agent().id(),null,payload.toString(),null,null,null,null,null,null));
        return new ExecutionResult(answer,facts,toolCalls,plannerCalls,LlmUsage.knownPromptTokens(usages),LlmUsage.knownCompletionTokens(usages),
                clarify==null?null:"NEEDS_CLARIFICATION",clarify,0,plannerCalls,answerCalls,0,0,httpCalls,saved).withLlmUsages(usages).withAgent(definition.agent().route(),payload);
    }
    private record Invocation(String tool,JsonNode args){}
    private static List<String> refs(JsonNode node) {
        if(!node.isArray())throw new IllegalArgumentException("evidence_refs 必须为数组");
        var refs=new ArrayList<String>();for(var ref:node)if(!ref.isTextual() || !ref.asText().matches("e[1-9][0-9]*") || refs.contains(ref.asText()))throw new IllegalArgumentException("证据引用非法");else refs.add(ref.asText());
        return refs;
    }
    private static boolean fresh(ToolResult result) {
        return result!=null && result.ok() && result.provenance()!=null && Instant.parse(result.provenance().path("valid_until").asText()).isAfter(Instant.now());
    }
    private static JsonNode resolve(JsonNode node,Map<String,ToolResult> results) {
        if(node.isObject() && node.has("$ref")) {
            if(node.size()!=1 || !node.path("$ref").isTextual())throw new IllegalArgumentException("非法参数引用");
            var parts=node.path("$ref").asText().split("#",-1);
            if(parts.length!=2 || !parts[1].isEmpty() && !parts[1].startsWith("/"))throw new IllegalArgumentException("参数引用格式为 e1#/字段");
            var source=results.get(parts[0]);if(!fresh(source))throw new IllegalArgumentException("引用未知、失败或过期数据");
            var value=source.data().at(parts[1]);if(value.isMissingNode())throw new IllegalArgumentException("引用字段不存在");return value.deepCopy();
        }
        if(node.isObject()){var object=JSON.createObjectNode();node.fields().forEachRemaining(e->object.set(e.getKey(),resolve(e.getValue(),results)));return object;}
        if(node.isArray()){var array=JSON.createArrayNode();node.forEach(item->array.add(resolve(item,results)));return array;}
        return node.deepCopy();
    }
    private static ExecutionFailure failure(Exception cause,List<LlmUsage> usages,int plans,int answers,int tools,int http,List<String> saved,JsonNode facts) {
        return new ExecutionFailure(cause,new TurnCounters(plans,answers,0,0,http,0,saved).withLlmUsages(usages).withToolCalls(tools),null,facts);
    }
}
