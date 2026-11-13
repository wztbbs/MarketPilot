package com.cexpilot.platform.run;

import com.cexpilot.platform.run.fixture.RunTestEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.cexpilot.platform.run.fixture.RunContractFixture.JSON;
import static org.junit.jupiter.api.Assertions.*;

/** 仅验证记录格式和保留策略，不创建 MySQL、不启动或强杀 JVM。 */
class RunTestEvidenceTest {
    @TempDir Path directory;
    @Test void recordsOrderedEventsSnapshotsAndOutcomesWithoutOverwritingAnEarlierRun() throws Exception {
        var evidence = new RunTestEvidence(directory);
        evidence.event("case_a", "force_kill_finished", JSON.createObjectNode().put("pid", 123).put("exitCode", 137).put("faultInjection", true));
        var state = JSON.createObjectNode().put("password", "not-for-archive").put("checkpoint", "WAITING_EXPORT");
        var run = new DurableStore.Entry("run", "alice", null, "WAITING", 2, 1, Instant.EPOCH, null, state);
        var callState = JSON.createObjectNode().put("jobId", "job").put("password", "not-for-archive");
        var call = new DurableStore.Entry("call", "alice", "run", "WAITING", 3, 2, null, null, callState);
        evidence.snapshot("case_a", "before_kill", run, List.of(call), List.of(), 0, JSON.createObjectNode().put("externalEffects", 1));
        evidence.result("case_a", "PASS", null); evidence.finish();
        var lines = Files.readAllLines(directory.resolve("events.jsonl"));
        for (int i = 0; i < lines.size(); i++) {
            var event = JSON.readTree(lines.get(i)); assertEquals(i + 1, event.path("sequence").asInt());
            assertDoesNotThrow(() -> Instant.parse(event.path("recordedAtUtc").asText()));
            assertTrue(event.path("elapsedMs").asLong() >= 0);
        }
        assertEquals(123, JSON.readTree(lines.get(1)).at("/details/pid").asInt());
        var snapshotEvent = JSON.readTree(lines.get(2));
        var snapshot = Files.readString(directory.resolve(snapshotEvent.at("/details/file").asText()));
        assertFalse(snapshot.contains("not-for-archive")); assertTrue(snapshot.contains("WAITING_EXPORT"));
        assertEquals("job", JSON.readTree(snapshot).at("/calls/0/data/jobId").asText());
        assertEquals("PASS", JSON.readTree(Files.readString(directory.resolve("summary.json"))).at("/cases/case_a").asText());
        assertThrows(FileAlreadyExistsException.class, () -> new RunTestEvidence(directory));
    }
    @Test void failureOutcomeIsRecordedWithoutCopyingExceptionMessagesIntoMetadata() throws Exception {
        var evidence = new RunTestEvidence(directory);
        evidence.result("case_a", "FAIL", new IllegalStateException("private-credential")); evidence.finish();
        String events = Files.readString(directory.resolve("events.jsonl")); assertFalse(events.contains("private-credential"));
        assertTrue(events.contains("IllegalStateException"));
        assertEquals("FAIL", JSON.readTree(Files.readString(directory.resolve("summary.json"))).at("/cases/case_a").asText());
    }
}
