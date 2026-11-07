package com.cexpilot.runtime;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.cexpilot.runtime.HttpToolFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpAgentToolTest {
    HttpServer server;
    final AtomicInteger hits=new AtomicInteger();
    final ExecutorService workers=Executors.newCachedThreadPool();
    @BeforeEach void start() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);server.start();}
    @AfterEach void close(){server.stop(0);workers.shutdownNow();}
    int port(){return server.getAddress().getPort();}
    void reply(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    }
    HttpAgentTool tool(String yaml){return new HttpAgentTool(definition(yaml),services(port()));}
    @Test void validatesContractAndTransmitsOnlyTrustedIdentityAndConfiguredAuthentication() {
        var received=new AtomicReference<String>();
        server.createContext("/lookup",e->{hits.incrementAndGet();received.set(e.getRequestHeaders().getFirst("Authorization")+":"+e.getRequestHeaders().getFirst("X-Cexpilot-Visitor")+":"+e.getRequestHeaders().getFirst("X-Cexpilot-Trace"));
            assertNotNull(e.getRequestHeaders().getFirst("X-Cexpilot-Request-Time"));
            assertEquals("Z",e.getRequestHeaders().getFirst("X-Cexpilot-Timezone"));
            assertEquals("{\"symbol\":\"BTC\"}",new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));reply(e,200,response("BTC"));});
        var result=tool(TOOL).execute(json("{\"symbol\":\"BTC\"}"),context());
        assertTrue(result.ok());assertEquals("OK",result.status());assertEquals(123,result.data().path("value").asInt());
        assertEquals("Bearer "+SECRET+":visitor:trace",received.get());assertFalse(result.toMessageContent().contains(SECRET));
        assertEquals("mock-v1",result.provenance().path("definition_version").asText());assertEquals("reference",result.provenance().path("agent_id").asText());
    }
    @Test void missingAuthorizationOrModelIdentityFieldsNeverReachService() {
        server.createContext("/lookup",e->{hits.incrementAndGet();reply(e,200,response("BTC"));});
        var tool=tool(TOOL);
        assertEquals("DENIED",tool.execute(json("{\"symbol\":\"BTC\"}"),new ToolContext("t","c")).status());
        assertEquals("INPUT_INVALID",tool.execute(json("{\"symbol\":\"BTC\",\"visitorId\":\"someone-else\"}"),context()).status());
        assertEquals(0,hits.get());
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_DATA","DENIED","DEPENDENCY_FAILED","TIMEOUT","EXECUTION_ERROR"})
    void businessFailuresKeepDistinctStatusesAndDiscardUntrustedErrorBody(String status) {
        server.createContext("/lookup",e->reply(e,200,"{\"status\":\""+status+"\",\"error\":\"private detail\"}"));
        var result=tool(TOOL).execute(json("{\"symbol\":\"BTC\"}"),context());
        assertFalse(result.ok());assertEquals(status,result.status());assertFalse(result.toMessageContent().contains("private detail"));
    }
    @Test void emptyIsSuccessfulEvidenceRatherThanDependencyFailure() {
        String yaml=TOOL.replace("required: [symbol, value]","required: [symbol]");
        var empty=json(response("BTC"));((com.fasterxml.jackson.databind.node.ObjectNode)empty).put("status","EMPTY");((com.fasterxml.jackson.databind.node.ObjectNode)empty.path("data")).remove("value");
        server.createContext("/lookup",e->reply(e,200,empty.toString()));
        var result=tool(yaml).execute(json("{\"symbol\":\"BTC\"}"),context());assertTrue(result.ok());assertEquals("EMPTY",result.status());
    }
    @ParameterizedTest @ValueSource(strings={"wrong_type","missing","extra","not_json","secret","secret_escaped","stale","future"})
    void invalidOrExpiredResponsesNeverBecomeFacts(String kind) {
        var node=json(response("BTC"));String body;
        switch(kind) {
            case "wrong_type" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("data")).put("value","123");
            case "missing" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("data")).remove("value");
            case "extra" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("data")).put("unknown",1);
            case "secret", "secret_escaped" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("evidence")).put("source",SECRET);
            case "stale" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("evidence")).put("valid_until",Instant.now().minusSeconds(1).toString());
            case "future" -> ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("evidence")).put("observed_at",Instant.now().plusSeconds(60).toString());
        }
        body=kind.equals("not_json")?"not json":kind.equals("secret_escaped")?node.toString().replace(SECRET,Character.toString(92)+"u006c"+SECRET.substring(1)):node.toString();server.createContext("/lookup",e->reply(e,200,body));
        var result=tool(TOOL.replace("max_attempts: 1","max_attempts: 2")).execute(json("{\"symbol\":\"BTC\"}"),context());
        assertFalse(result.ok());assertEquals(kind.equals("stale")?"STALE_EVIDENCE":"OUTPUT_INVALID",result.status());
        assertFalse(result.toMessageContent().contains(SECRET));
    }
    @Test void redirectsAreNotFollowedAndReadonlyRetriesAreBoundedAndAudited() {
        server.createContext("/lookup",e->{int n=hits.incrementAndGet();if(n==1)reply(e,503,"dependency failed");else reply(e,200,response("BTC"));});
        var trace=new NodeTraceCollector.Session();NodeTraceCollector.bind(trace);
        try {var result=tool(TOOL.replace("max_attempts: 1","max_attempts: 2")).execute(json("{\"symbol\":\"BTC\"}"),context());assertTrue(result.ok());}
        finally{NodeTraceCollector.clear();}
        assertEquals(2,hits.get());assertEquals(2,trace.freezeAndSnapshot().size());
        server.removeContext("/lookup");hits.set(0);
        server.createContext("/lookup",e->{hits.incrementAndGet();e.getResponseHeaders().set("Location","http://127.0.0.1:"+port()+"/target");reply(e,302,"{}");});
        server.createContext("/target",e->{hits.incrementAndGet();reply(e,200,response("BTC"));});
        assertEquals("DEPENDENCY_FAILED",tool(TOOL).execute(json("{\"symbol\":\"BTC\"}"),context()).status());assertEquals(1,hits.get());
    }
    @Test void responseSizeAndTotalToolTimeoutAreBounded() {
        server.createContext("/lookup",e->reply(e,200,"x".repeat(1000)));
        assertEquals("OUTPUT_INVALID",tool(TOOL+"max_response_bytes: 128\n").execute(json("{\"symbol\":\"BTC\"}"),context()).status());
        server.removeContext("/lookup");server.createContext("/lookup",e->{try{Thread.sleep(500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}reply(e,200,response("BTC"));});
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertEquals("TIMEOUT",tool(TOOL.replace("timeout_ms: 2000","timeout_ms: 100")).execute(json("{\"symbol\":\"BTC\"}"),context()).status()));
    }
    @ParameterizedTest @ValueSource(strings={"write","url","path","identity","nested_identity","unbound"})
    void unsafeOrUnboundConfigurationFailsBeforeServingRequests(String kind) {
        String yaml=switch(kind) {
            case "write" -> TOOL.replace("side_effect: false","side_effect: true");
            case "url" -> TOOL+"base_url: http://example.invalid\n";
            case "path" -> TOOL.replace("path: /lookup","path: //other/lookup");
            case "identity" -> TOOL.replace("symbol:","userId:").replace("[symbol]","[userId]");
            case "nested_identity" -> TOOL.replace("symbol: {type: string, enum: [BTC, ETH]}","symbol: {type: object, additionalProperties: false, properties: {tenant_id: {type: string}}}");
            default -> TOOL.replace("service_id: reference","service_id: missing");
        };
        assertThrows(RuntimeException.class,()->tool(yaml));
    }
    @Test void nestedInputDefaultsAreAppliedAndRequiredFieldsAreCheckedRecursively() {
        String yaml=TOOL.replace("required: [symbol]","required: [symbol, options]")
                .replace("symbol: {type: string, enum: [BTC, ETH]}","symbol: {type: string, enum: [BTC, ETH]}\n    options: {type: object, additionalProperties: false, required: [depth], properties: {depth: {type: integer, default: 2, minimum: 1, maximum: 5}}}");
        var received=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/lookup",e->{hits.incrementAndGet();received.set(JSON.readTree(e.getRequestBody()));reply(e,200,response("BTC"));});
        var registry=new ToolRegistry(List.of(),List.of(definition(yaml)),services(port()));
        var args=registry.prepareArguments("reference.lookup",json("{\"symbol\":\"BTC\",\"options\":{}}"));
        assertEquals(2,args.at("/options/depth").asInt());assertTrue(registry.get("reference.lookup").execute(args,context()).ok());
        assertEquals(2,received.get().at("/options/depth").asInt());
        assertThrows(IllegalArgumentException.class,()->registry.prepareArguments("reference.lookup",json("{\"symbol\":\"BTC\",\"options\":{\"depth\":0}}")));
        assertThrows(IllegalArgumentException.class,()->registry.prepareArguments("reference.lookup",json("{\"symbol\":\"BTC\",\"options\":{\"extra\":1}}")));
        assertEquals(1,hits.get());
    }
    @Test void getParametersStayOnFixedPathAndAuthenticationFailureIsNotRetried() {
        var query=new AtomicReference<String>();
        server.createContext("/lookup",e->{hits.incrementAndGet();query.set(e.getRequestURI().getRawQuery());assertEquals("GET",e.getRequestMethod());reply(e,403,SECRET);});
        var result=tool(TOOL.replace("method: POST","method: GET").replace("max_attempts: 1","max_attempts: 2")).execute(json("{\"symbol\":\"BTC\"}"),context());
        assertEquals("symbol=BTC",query.get());assertEquals(1,hits.get());assertEquals("DENIED",result.status());assertFalse(result.toMessageContent().contains(SECRET));
    }
    @Test void typedArrayContractsRejectWrongItemsAtRuntime() {
        var schema=json("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"items\"],\"properties\":{\"items\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}}}");
        ToolOutputSchema.validateSchema(schema);
        assertDoesNotThrow(()->ToolOutputSchema.validateValue(json("{\"items\":[1,2]}"),schema));
        assertThrows(IllegalArgumentException.class,()->ToolOutputSchema.validateValue(json("{\"items\":[\"1\"]}"),schema));
    }

}
