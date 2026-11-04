package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.http.client.ClientHttpResponse;

/** Per-call counters only: reasoning text and arbitrary response headers are never retained. */
final class LlmCallDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final long start = System.nanoTime();
    private final ObjectNode values = JSON.createObjectNode();
    private long lastChunk = -1, maxGap;
    private int contentChars, reasoningChars, chunks;
    long connectionWaitNanos, connectNanos;
    int leaseAttempts;

    long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
    static ObjectNode pool(PoolStats stats) {
        return JSON.createObjectNode().put("leased", stats.getLeased()).put("pending", stats.getPending())
                .put("available", stats.getAvailable()).put("max", stats.getMax());
    }
    void poolStart(PoolStats stats) { values.set("pool_start", pool(stats)); }
    void headers(ClientHttpResponse response) throws java.io.IOException {
        values.put("response_headers_ms", elapsed()).put("status_code", response.getStatusCode().value());
        for (String header : java.util.List.of("x-request-id", "x-tc-requestid", "request-id", "trace-id")) {
            String id = response.getHeaders().getFirst(header);
            if (id != null) values.withObject("/upstream_request_ids").put(header, id);
        }
    }
    void packet() {
        long now = elapsed();
        if (!values.has("first_chunk_ms")) values.put("first_chunk_ms", now);
        if (lastChunk >= 0) maxGap = Math.max(maxGap, now - lastChunk);
        lastChunk = now;
        chunks++;
    }
    void chunk(JsonNode chunk) {
        if (chunk.hasNonNull("id")) values.put("response_id", chunk.path("id").asText());
        JsonNode choice = chunk.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        if (delta.path("content").isTextual() && !delta.path("content").asText().isEmpty()) {
            mark("content"); contentChars += delta.path("content").asText().length();
        }
        if (delta.path("reasoning_content").isTextual() && !delta.path("reasoning_content").asText().isEmpty()) {
            mark("reasoning"); reasoningChars += delta.path("reasoning_content").asText().length();
        }
        if (choice.hasNonNull("finish_reason")) values.put("finish_reason", choice.path("finish_reason").asText());
        usage(chunk.path("usage"));
    }
    private void mark(String field) {
        long now = elapsed();
        if (!values.has("first_" + field + "_ms")) values.put("first_" + field + "_ms", now);
        values.put("last_" + field + "_ms", now);
    }
    void usage(JsonNode usage) {
        if (!usage.isObject()) return;
        values.set("usage", usage.deepCopy());
        JsonNode reasoning = usage.path("completion_tokens_details").path("reasoning_tokens");
        if (reasoning.isIntegralNumber()) values.set("reasoning_tokens", reasoning);
    }
    void nonStreaming(JsonNode response) {
        JsonNode message = response.path("choices").path(0).path("message");
        contentChars = message.path("content").asText("").length();
        reasoningChars = message.path("reasoning_content").asText("").length();
        if (response.hasNonNull("id")) values.put("response_id", response.path("id").asText());
        JsonNode reason = response.path("choices").path(0).path("finish_reason");
        if (reason.isTextual()) values.put("finish_reason", reason.asText());
        usage(response.path("usage"));
    }
    Long firstChunk() { return values.has("first_chunk_ms") ? values.path("first_chunk_ms").asLong() : null; }
    void doneMarker() { values.put("stream_done", true); }
    ObjectNode finish(PoolStats stats, String status) {
        values.put("total_ms", elapsed()).put("connection_wait_ms", connectionWaitNanos / 1_000_000)
                .put("connect_ms", connectNanos / 1_000_000).put("lease_attempts", leaseAttempts)
                .put("chunk_count", chunks).put("content_chars", contentChars).put("reasoning_chars", reasoningChars)
                .put("max_chunk_gap_ms", maxGap).put("end_status", status);
        if (lastChunk >= 0) {
            // Include the tail while waiting for DONE/EOF, even if no more data arrives.
            values.put("max_chunk_gap_ms", Math.max(maxGap, elapsed() - lastChunk));
            values.put("last_chunk_ms", lastChunk);
        }
        values.set("pool_end", pool(stats));
        return values.deepCopy();
    }
}
