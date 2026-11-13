package com.cexpilot.platform.run.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.cexpilot.platform.run.fixture.RunContractFixture.JSON;

/** 独立于业务 Worker JVM 的本地外部任务服务；可模拟已受理但响应丢失。 */
public final class RunContractService implements AutoCloseable {
    public record Job(String id, String invocationId, JsonNode arguments, String agent, String principal) {}
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "run-contract-service"); t.setDaemon(true); return t; });
    private final ConcurrentMap<String, Job> byKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> plans = new ConcurrentHashMap<>();
    public final AtomicInteger submissions = new AtomicInteger(), effects = new AtomicInteger(), recoveries = new AtomicInteger();
    public final CountDownLatch accepted = new CountDownLatch(1), releaseSubmissionResponse = new CountDownLatch(1);
    public volatile boolean delaySubmissionResponse, done;
    public volatile String lastInvocationId;
    public RunContractService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
        server.createContext("/plan/", request -> {
            var doc = request.getRequestURI().getPath().substring("/plan/".length()); plans.computeIfAbsent(doc, k -> new AtomicInteger()).incrementAndGet();
            reply(request, JSON.createObjectNode().put("operation", "export").put("documentId", doc));
        });
        server.createContext("/jobs", request -> {
            if ("POST".equals(request.getRequestMethod())) {
                submissions.incrementAndGet(); var key = request.getRequestHeaders().getFirst("Idempotency-Key");
                if (key == null || !key.matches("[a-f0-9]{48}")) { request.sendResponseHeaders(400, -1); request.close(); return; }
                var args = JSON.readTree(request.getRequestBody());
                var job = byKey.computeIfAbsent(key, k -> {
                    effects.incrementAndGet(); return new Job(UUID.randomUUID().toString(), k, args.deepCopy(), request.getRequestHeaders().getFirst("X-Agent"), request.getRequestHeaders().getFirst("X-Principal"));
                });
                lastInvocationId = key; accepted.countDown();
                if (delaySubmissionResponse) try { releaseSubmissionResponse.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { reply(request, JSON.createObjectNode().put("jobId", job.id())); }
                catch (IOException lostResponse) { request.close(); }
            } else {
                String jobId = request.getRequestURI().getPath().substring("/jobs/".length());
                var job = byKey.values().stream().filter(j -> j.id().equals(jobId)).findFirst().orElseThrow();
                var result = JSON.createObjectNode().put("done", done);
                if (done) result.putObject("data").put("receipt", "receipt-" + job.arguments().path("documentId").asText());
                reply(request, result);
            }
        });
        server.createContext("/by-key/", request -> {
            recoveries.incrementAndGet(); var job = byKey.get(request.getRequestURI().getPath().substring("/by-key/".length()));
            var response = JSON.createObjectNode(); if (job != null) response.put("jobId", job.id()); reply(request, response);
        });
        server.start();
    }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public int plans(String doc) { return plans.getOrDefault(doc, new AtomicInteger()).get(); }
    public Job job() { return byKey.get(lastInvocationId); }
    private static void reply(HttpExchange request, JsonNode data) throws IOException {
        byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json"); request.sendResponseHeaders(200, body.length);
        try (var output = request.getResponseBody()) { output.write(body); }
    }
    public void close() { releaseSubmissionResponse.countDown(); server.stop(0); workers.shutdownNow(); }
}
