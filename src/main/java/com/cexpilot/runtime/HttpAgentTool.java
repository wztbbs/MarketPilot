package com.cexpilot.runtime;

import com.cexpilot.config.HttpToolServices;
import com.cexpilot.prompt.PromptStore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 固定服务/路径、只读、禁止重定向的 HTTP 执行器；不记录凭证、URL或依赖错误正文。 */
public final class HttpAgentTool implements AgentTool {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> STATUSES=Set.of("OK","EMPTY","MISSING_DATA","DENIED","DEPENDENCY_FAILED","TIMEOUT","EXECUTION_ERROR");
    private static final HttpClient READ_ONLY_CLIENT=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final ToolDefinition definition;
    private final HttpToolServices.Service service;
    private final HttpClient client;
    private final Clock clock;
    public HttpAgentTool(ToolDefinition definition,HttpToolServices services) {
        this(definition,services,READ_ONLY_CLIENT,Clock.systemUTC());
    }
    public HttpAgentTool(ToolDefinition definition,HttpToolServices services,HttpClient client,Clock clock) {
        this.definition=definition;this.service=services.require(definition.http().serviceId());this.client=client;this.clock=clock;
        if(client.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("HTTP Tool 禁止重定向");
        service.endpoint(definition.http().path());
        strictInput(definition.inputSchema());strictOutput(definition.outputSchema());
        if("GET".equals(definition.http().method()))for(var rule:definition.inputSchema().path("properties"))
            if(Set.of("object","array").contains(rule.path("type").asText()))throw new IllegalArgumentException("GET Tool 仅支持标量参数");
    }
    @Override public String name(){return definition.name();}
    /** @return 工具描述+输入/输出 schema+HTTP 配置的指纹，契约任何变化都会改变该值 */
    public String contractVersion(){return PromptStore.fingerprint(definition.description()+":"+definition.inputSchema()+":"+definition.outputSchema()+":"+definition.http());}
    /**
     * 执行一次只读 HTTP 工具调用：校验身份/授权与入参，按定义发起 GET/POST，
     * 校验响应信封（status/data/evidence）、防凭证回显，并按超时与最大尝试次数重试。
     * 每次尝试都会向 NodeTraceCollector 记录一条 http_tool_attempt 事件。
     *
     * @param args    模型给出的参数（此处再做一次 schema 校验并应用默认值）
     * @param context 调用上下文；agentId/visitorId/conversationId/traceId 任一非法即 DENIED
     * @return 成功（OK/EMPTY）时带 data 与 provenance 的结果；失败时仅有 status，data/provenance 为 null
     */
    @Override public ToolResult execute(JsonNode args,ToolContext context) {
        if(context==null || context.agentId()==null || !context.allowedTools().contains(name())
                || context.requestTime()==null || context.timezone()==null || !identity(context.visitorId()) || !identity(context.conversationId()) || !identity(context.traceId()))return fail("DENIED");
        var errors=ToolArguments.validate(args,definition.inputSchema(),false);
        if(!errors.isEmpty())return fail("INPUT_INVALID");
        args=ToolArguments.withDefaults(args,definition.inputSchema());
        var http=definition.http();
        byte[] requestBody;
        try {requestBody=JSON.writeValueAsBytes(args);}catch(IOException invalid){return fail("INPUT_INVALID");}
        if(requestBody.length>http.maxResponseBytes())return fail("INPUT_INVALID");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(http.timeoutMs());
        var budget=QueryBudget.current();
        if(budget!=null)deadline=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget.remainingMs()));
        for(int attempt=1;attempt<=http.maxAttempts();attempt++) {
            long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            if(remaining<=0)return fail("TIMEOUT");
            long start=System.nanoTime();int httpStatus=0;String status="DEPENDENCY_FAILED";
            try {
                URI uri=service.endpoint(http.path());
                if("GET".equals(http.method()) && !args.isEmpty()) {
                    var parts=new ArrayList<String>();args.fields().forEachRemaining(e->parts.add(encode(e.getKey())+"="+encode(e.getValue().asText())));
                    uri=URI.create(uri+"?"+String.join("&",parts));
                }
                var request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remaining)).header("Accept","application/json")
                        .header("User-Agent","CEXPilot-HttpTool/1.0").header("X-Cexpilot-Trace",context.traceId())
                        .header("X-Cexpilot-Agent",context.agentId()).header("X-Cexpilot-Visitor",context.visitorId())
                        .header("X-Cexpilot-Conversation",context.conversationId()).header("X-Cexpilot-Request-Time",context.requestTime().toString())
                        .header("X-Cexpilot-Timezone",context.timezone().getId());
                if(!service.getToken().isBlank())request.header("Authorization","Bearer "+service.getToken());
                if("POST".equals(http.method())) {
                    request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
                } else request.GET();
                var pending=client.sendAsync(request.build(),info->new LimitedBody(http.maxResponseBytes()));
                HttpResponse<byte[]> response;
                try {response=pending.get(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),TimeUnit.MILLISECONDS);}
                catch(TimeoutException timeout){pending.cancel(true);throw new HttpTimeoutException("Tool deadline");}
                catch(InterruptedException interrupted){pending.cancel(true);throw interrupted;}
                catch(ExecutionException failed){if(failed.getCause() instanceof IOException io)throw io;throw new IOException("Tool dependency failed",failed.getCause());}
                httpStatus=response.statusCode();
                if(httpStatus==401 || httpStatus==403){status="DENIED";return fail(status);}
                if(httpStatus<200 || httpStatus>=300) {
                    status="DEPENDENCY_FAILED";
                    if(attempt<http.maxAttempts() && Set.of(429,502,503,504).contains(httpStatus))continue;
                    return fail(status);
                }
                var contentType=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if(!contentType.contains("application/json") && !contentType.contains("+json")){status="OUTPUT_INVALID";return fail(status);}
                String body=new String(response.body(),StandardCharsets.UTF_8);
                if(!service.getToken().isEmpty() && body.contains(service.getToken())){status="OUTPUT_INVALID";return fail(status);}
                var envelope=JSON.readTree(body);
                if(envelope==null || !envelope.isObject() || !envelope.path("status").isTextual() || !STATUSES.contains(envelope.path("status").asText())) {
                    status="OUTPUT_INVALID";return fail(status);
                }
                if(containsSecret(envelope,service.getToken())){status="OUTPUT_INVALID";return fail(status);}
                status=envelope.path("status").asText();
                if(!Set.of("OK","EMPTY").contains(status))return fail(status);
                ToolOutputSchema.validateValue(envelope.get("data"),definition.outputSchema());
                var provenance=provenance(envelope.path("evidence"),context,args);
                return ToolResult.http(status,envelope.get("data"),provenance);
            } catch(HttpTimeoutException timeout) {status="TIMEOUT";if(attempt==http.maxAttempts())return fail(status);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();status="TIMEOUT";return fail(status);}
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid){status="OUTPUT_INVALID";return fail(status);}
            catch(IOException dependency) {
                status=hasCause(dependency,TooLarge.class)?"OUTPUT_INVALID":"DEPENDENCY_FAILED";
                if("OUTPUT_INVALID".equals(status) || attempt==http.maxAttempts())return fail(status);
            } catch(StaleEvidence stale){status="STALE_EVIDENCE";return fail(status);}
            catch(RuntimeException invalid){status="OUTPUT_INVALID";return fail(status);}
            finally {
                var trace=JSON.createObjectNode().put("tool",name()).put("service_id",http.serviceId()).put("attempt",attempt)
                        .put("http_status",httpStatus).put("status",status);
                NodeTraceCollector.record("http_tool_attempt",null,trace.toString(),(System.nanoTime()-start)/1_000_000,
                        Set.of("OK","EMPTY").contains(status)?null:status);
            }
        }
        return fail("DEPENDENCY_FAILED");
    }
    /**
     * 校验并组装证据元数据：source/observed_at/valid_until/unit/definition_version 必须齐全，
     * observed_at 不得在未来，valid_until 按 maxAgeMs 封顶且必须仍未过期。
     *
     * @param input   响应信封中的 evidence 节点
     * @param context 调用上下文（agentId 写入 provenance）
     * @param args    实际请求参数（写入 provenance.parameters）
     * @return 组装后的 provenance 节点；过期抛 StaleEvidence，其他非法抛 IllegalArgumentException
     */
    private ObjectNode provenance(JsonNode input,ToolContext context,JsonNode args) {
        for(String key:List.of("source","observed_at","valid_until","unit","definition_version"))
            if(!input.path(key).isTextual() || input.path(key).asText().isBlank() || input.path(key).asText().length()>256)throw new IllegalArgumentException("缺少证据元数据");
        Instant observed=Instant.parse(input.path("observed_at").asText()),valid=Instant.parse(input.path("valid_until").asText());
        Instant now=clock.instant();
        if(observed.isAfter(now.plusSeconds(5)))throw new IllegalArgumentException("证据时间在未来");
        valid=valid.isBefore(observed.plusMillis(definition.http().maxAgeMs()))?valid:observed.plusMillis(definition.http().maxAgeMs());
        if(!valid.isAfter(now))throw new StaleEvidence();
        var result=JSON.createObjectNode().put("source",input.path("source").asText()).put("observed_at",observed.toString())
                .put("valid_until",valid.toString()).put("unit",input.path("unit").asText()).put("definition_version",input.path("definition_version").asText())
                .put("service_id",definition.http().serviceId()).put("contract_version",contractVersion()).put("agent_id",context.agentId());
        result.set("parameters",args.deepCopy());return result;
    }
    /** 深度扫描响应 JSON（含对象键），检测是否回显了服务凭证；secret 为空串直接返回 false。 */
    private static boolean containsSecret(JsonNode root,String secret) {
        if(secret.isEmpty())return false;
        var pending=new ArrayDeque<JsonNode>();pending.add(root);
        while(!pending.isEmpty()) {
            var node=pending.removeFirst();
            if(node.isTextual() && node.asText().contains(secret))return true;
            if(node.isObject()) {
                var keys=node.fieldNames();while(keys.hasNext())if(keys.next().contains(secret))return true;
            }
            if(node.isContainerNode())node.forEach(pending::addLast);
        }
        return false;
    }
    /** 构造仅有 status 的失败结果（data/provenance 为 null）。 */
    private static ToolResult fail(String status){return ToolResult.http(status,null,null);}
    /** @return 身份标识是否合法：非空、仅含字母数字及 _ . : -、长度 1~128 */
    private static boolean identity(String value){return value!=null && value.matches("[A-Za-z0-9_.:-]{1,128}");}
    /** URL 查询参数值编码。 */
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    /** @return 异常链中是否存在指定类型的 cause */
    private static boolean hasCause(Throwable error,Class<?> type){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(type.isInstance(cause))return true;return false;}
    /** 证据已过期的内部信号异常。 */
    private static class StaleEvidence extends RuntimeException {}
    /** 响应体超过 maxResponseBytes 的内部信号异常。 */
    private static class TooLarge extends IOException {}
    /** 限制响应体大小的 BodySubscriber：超过 limit 即取消订阅并以 TooLarge 失败。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> future=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return future;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>limit){subscription.cancel();future.completeExceptionally(new TooLarge());return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){future.completeExceptionally(error);}
        public void onComplete(){future.complete(bytes.toByteArray());}
    }
    /**
     * 校验输入 schema 的安全约束：object 必须封闭（显式 additionalProperties=false）并声明 properties，
     * 且任何层级不得声明身份/连接类字段（userId、token、url、secret 等）。
     */
    private static void strictInput(JsonNode schema) {
        if("object".equals(schema.path("type").asText())) {
            if(!schema.path("properties").isObject() || !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean())
                throw new IllegalArgumentException("HTTP input object 必须封闭且声明 properties");
            schema.path("properties").fields().forEachRemaining(e->{
                String key=e.getKey().replace("_","").replace("-","").toLowerCase(Locale.ROOT);
                if(Set.of("userid","visitorid","tenantid","accountid","authorization","headers","url","baseurl","token","apikey","secret","password").contains(key))
                    throw new IllegalArgumentException("HTTP input 不能声明身份或连接字段");
                strictInput(e.getValue());
            });
        } else if("array".equals(schema.path("type").asText()))strictInput(schema.path("items"));
    }
    /** 校验输出 schema 的每个节点都显式声明 type（不允许动态类型），递归检查嵌套结构。 */
    private static void strictOutput(JsonNode schema) {
        if(!schema.has("type"))throw new IllegalArgumentException("HTTP output 不允许动态类型");
        if(schema.path("properties").isObject())schema.path("properties").forEach(HttpAgentTool::strictOutput);
        if(schema.path("items").isObject())strictOutput(schema.path("items"));
        if(schema.path("prefixItems").isArray())schema.path("prefixItems").forEach(HttpAgentTool::strictOutput);
    }
}
