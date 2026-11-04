package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import com.cexpilot.exception.LlmException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmDiagnosticsTest {
    HttpServer server;
    OpenAiCompatibleClient client;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        var config = new LlmConfig.ModelConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("local-test-secret"); config.setModel("local-test");
        client = new OpenAiCompatibleClient(config, 0, null, true);
        server.start();
    }
    @AfterEach void close() throws Exception {
        server.stop(0); ((ExecutorService) server.getExecutor()).shutdownNow();
        var field = OpenAiCompatibleClient.class.getDeclaredField("connectionManager"); field.setAccessible(true);
        ((ObservedConnectionManager) field.get(client)).close();
    }
    static void packet(java.io.OutputStream out, String value) throws java.io.IOException {
        out.write(("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    @Test void measuresReasoningSeparatelyAndPersistsUsageWithoutReasoningText() throws Exception {
        server.createContext("/v1/chat/completions", x -> {
            x.getRequestBody().readAllBytes();
            x.getResponseHeaders().add("Content-Type", "text/event-stream");
            x.getResponseHeaders().add("X-Request-Id", "upstream-123");
            x.sendResponseHeaders(200, 0);
            try (var out = x.getResponseBody()) {
                packet(out, "{\"id\":\"chat-123\",\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");
                packet(out, "{\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_REASONING\"}}]}");
                try { Thread.sleep(70); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                packet(out, "{\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9,\"completion_tokens_details\":{\"reasoning_tokens\":8}}}");
                packet(out, "[DONE]");
            }
        });
        var response = client.chat(List.of(ChatMessage.user("test")), null);
        var d = response.diagnostics();
        assertEquals("OK", response.content()); assertEquals(9, response.completionTokens());
        assertEquals(17, d.path("reasoning_chars").asInt()); assertEquals(8, d.path("reasoning_tokens").asInt());
        assertTrue(d.path("first_content_ms").asLong() >= d.path("first_reasoning_ms").asLong());
        assertTrue(d.path("max_chunk_gap_ms").asLong() >= 40);
        assertEquals("upstream-123", d.at("/upstream_request_ids/x-request-id").asText());
        assertEquals("stop", d.path("finish_reason").asText()); assertTrue(d.path("stream_done").asBoolean());
        assertEquals(1, d.path("lease_attempts").asInt()); assertEquals(0, d.at("/pool_end/leased").asInt());
        String json = LlmTraceSerializer.responseToJson(response);
        assertTrue(json.contains("diagnostics")); assertFalse(json.contains("PRIVATE_REASONING")); assertFalse(json.contains("local-test-secret"));
    }
    @Test void failuresKeepPartialStreamAndRequestId() throws Exception {
        server.createContext("/v1/chat/completions", x -> {
            x.getRequestBody().readAllBytes();x.getResponseHeaders().add("X-Request-Id", "failed-123");x.sendResponseHeaders(200, 0);
            try (var out=x.getResponseBody()) {
                packet(out,"{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}");
                packet(out,"not-json");
            }
        });
        var error=assertThrows(LlmException.class,()->client.chat(List.of(ChatMessage.user("test")),null));
        var d=error.diagnostics();assertEquals("ERROR",d.path("end_status").asText());
        assertEquals(7,d.path("content_chars").asInt());assertEquals("failed-123",d.at("/upstream_request_ids/x-request-id").asText());
        assertTrue(LlmTraceSerializer.failureToJson(error).contains("failed-123"));
    }
    @Test void measuresActualConnectionPoolWaitUnderContention() throws Exception {
        var field=OpenAiCompatibleClient.class.getDeclaredField("connectionManager");field.setAccessible(true);
        var manager=(ObservedConnectionManager)field.get(client);manager.setMaxTotal(1);manager.setDefaultMaxPerRoute(1);
        var firstReceived=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);var count=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",x->{
            x.getRequestBody().readAllBytes();x.sendResponseHeaders(200,0);
            try(var out=x.getResponseBody()) {
                if(count.incrementAndGet()==1) {
                    firstReceived.countDown();
                    try { releaseFirst.await(5,TimeUnit.SECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
                packet(out,"{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}");packet(out,"[DONE]");
            }
        });
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->client.chat(List.of(ChatMessage.user("first")),null));
            assertTrue(firstReceived.await(5,TimeUnit.SECONDS));
            var second=workers.submit(()->client.chat(List.of(ChatMessage.user("second")),null));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(manager.getTotalStats().getPending()==0 && System.nanoTime()<deadline) Thread.sleep(5);
            assertEquals(1,manager.getTotalStats().getPending());Thread.sleep(70);releaseFirst.countDown();
            assertEquals("OK",first.get(5,TimeUnit.SECONDS).content());
            var response=second.get(5,TimeUnit.SECONDS);
            assertTrue(response.diagnostics().path("connection_wait_ms").asLong()>=50);
            assertEquals(1,response.diagnostics().path("lease_attempts").asInt());
        } finally { releaseFirst.countDown(); workers.shutdownNow(); }
    }
    @Test void httpFailuresPreserveStatusAndVendorRequestId() throws Exception {
        server.createContext("/v1/chat/completions", x -> {
            x.getRequestBody().readAllBytes();x.getResponseHeaders().add("X-TC-RequestId", "rate-limit-123");
            x.sendResponseHeaders(429,0);
            try(var out=x.getResponseBody()){out.write("rate limited".getBytes(StandardCharsets.UTF_8));}
        });
        var error=assertThrows(LlmException.class,()->client.chat(List.of(ChatMessage.user("test")),null));
        assertEquals(429,error.diagnostics().path("status_code").asInt());
        assertEquals("rate-limit-123",error.diagnostics().at("/upstream_request_ids/x-tc-requestid").asText());
        assertFalse(error.diagnostics().has("first_content_ms"));
    }
    @Test void nonStreamingToolCallsAlsoPreserveDiagnostics() throws Exception {
        server.createContext("/v1/chat/completions",x->{
            x.getRequestBody().readAllBytes();x.getResponseHeaders().add("Content-Type","application/json");x.sendResponseHeaders(200,0);
            try(var out=x.getResponseBody()){out.write("{\"id\":\"nonstream\",\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8));}
        });
        var tool=new ToolSpec("test","test",new ObjectMapper().createObjectNode());
        var response=client.chat(List.of(ChatMessage.user("test")),List.of(tool));
        assertEquals("nonstream",response.diagnostics().path("response_id").asText());assertNull(response.ttftMs());
        assertEquals(2,response.diagnostics().at("/usage/completion_tokens").asInt());
    }
}
