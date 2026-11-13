package com.cexpilot.platform.run.fixture;

import com.cexpilot.platform.run.DurableStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.cexpilot.platform.run.fixture.RunContractFixture.JSON;

/** 高成本故障验证的过程证据。仅记录白名单测试状态，不读取凭证或全部系统属性。 */
public final class RunTestEvidence {
    private final Path directory;
    private final long started = System.nanoTime();
    private int sequence;
    private final Map<String, String> results = new LinkedHashMap<>();
    public RunTestEvidence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath(); Files.createDirectories(this.directory.resolve("processes"));
        Files.createFile(this.directory.resolve("events.jsonl"));
        event("suite", "started", JSON.createObjectNode().put("javaVersion", System.getProperty("java.version"))
                .put("os", System.getProperty("os.name")).put("architecture", System.getProperty("os.arch")));
    }
    public Path directory() { return directory; }
    public synchronized void event(String scenario, String event, ObjectNode details) {
        var entry = JSON.createObjectNode().put("sequence", ++sequence).put("recordedAtUtc", Instant.now().toString())
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000).put("scenario", scenario).put("event", event);
        entry.set("details", details);
        try { Files.writeString(directory.resolve("events.jsonl"), entry + "\n", StandardOpenOption.APPEND); }
        catch (IOException failure) { throw new IllegalStateException("无法保存故障测试过程记录", failure); }
    }
    public synchronized void result(String scenario, String outcome, Throwable failure) {
        results.put(scenario, outcome); var details = JSON.createObjectNode().put("outcome", outcome);
        if (failure != null) details.put("failureType", failure.getClass().getName());
        event(scenario, "test_finished", details); summary();
    }
    public synchronized void snapshot(String scenario, String stage, DurableStore.Entry run,
                                      List<DurableStore.Entry> calls, List<Map<String, Object>> businessRows,
                                      int commitAttempts, ObjectNode serviceCounters) {
        var snapshot = JSON.createObjectNode(); snapshot.set("run", safeEntry(run, true));
        var records = snapshot.putArray("calls"); calls.forEach(call -> records.add(safeEntry(call, false)));
        snapshot.set("businessRows", JSON.valueToTree(businessRows)); snapshot.put("commitAttempts", commitAttempts);
        snapshot.set("service", serviceCounters);
        String name = String.format(Locale.ROOT, "%03d-%s-%s.json", sequence + 1, scenario, stage);
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("非法证据文件名");
        try { Files.writeString(directory.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + "\n", StandardOpenOption.CREATE_NEW); }
        catch (IOException failure) { throw new IllegalStateException("无法保存数据库测试快照", failure); }
        event(scenario, "state_snapshot", JSON.createObjectNode().put("stage", stage).put("file", name).put("runId", run.id()));
    }
    public static ObjectNode safeEntry(DurableStore.Entry entry, boolean run) {
        var result = JSON.createObjectNode().put("id", entry.id()).put("runId", entry.runId()).put("owner", entry.owner())
                .put("status", entry.status()).put("revision", entry.revision()).put("fence", entry.fence())
                .put("nextAttempt", entry.nextAttempt() == null ? null : entry.nextAttempt().toString())
                .put("leaseUntil", entry.leaseUntil() == null ? null : entry.leaseUntil().toString());
        var fields = run ? List.of("protocol", "adapterId", "adapterVersion", "agentId", "agentVersion", "route", "requestTime",
                "checkpoint", "privateInput", "privateOutput", "budget", "result", "waitingInvocationId", "businessCommitted", "completionPublished")
                : List.of("tool", "contract", "arguments", "resourceVersions", "fingerprint", "jobId", "decision", "result", "expiresAt");
        var data = result.putObject("data");
        for (String field : fields) if (entry.data().has(field)) data.set(field, entry.data().get(field).deepCopy());
        return result;
    }
    public synchronized void finish() { event("suite", "finished", JSON.createObjectNode()); summary(); }
    private void summary() {
        var summary = JSON.createObjectNode().put("format", "run-contract-evidence-v1").put("recordedAtUtc", Instant.now().toString())
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        summary.set("cases", JSON.valueToTree(results));
        try { Files.writeString(directory.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n"); }
        catch (IOException failure) { throw new IllegalStateException("无法保存故障测试汇总", failure); }
    }
}
