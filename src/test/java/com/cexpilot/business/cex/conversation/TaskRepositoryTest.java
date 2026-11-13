package com.cexpilot.business.cex.conversation;

import com.cexpilot.business.cex.configuration.ConversationConfig;
import com.cexpilot.business.cex.metric.CompiledPlan;
import com.cexpilot.business.cex.metric.EffectiveTarget;
import com.cexpilot.business.cex.metric.ConditionOrigin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 新任务/版本表的真实 SQL 与事务验证，完全本地。 */
class TaskRepositoryTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-11-10T01:00:00Z");
    JdbcTemplate jdbc;
    ConversationRepository repository;
    ConversationService service;
    TransactionTemplate tx;
    long queryNo;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:tasks_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = spy(new ConversationRepository(jdbc));
        service = new ConversationService(repository, new ConversationConfig());
        jdbc.update("INSERT INTO conversation (conversation_id) VALUES ('c')");
        jdbc.update("INSERT INTO conversation_state (conversation_id) VALUES ('c')");
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    private CompiledPlan plan(String base) {
        var instrument = json.createObjectNode().put("market_type", "perpetual").put("base", base).put("quote", "USDT");
        var target = new EffectiveTarget("m1", "metric_0", "binance", ConditionOrigin.PLANNED,
                "price.last", "snapshot", instrument, ConditionOrigin.DEFAULT,
                null, null, null, null, null, null, null, null, "USDT", "ticker", "test");
        return new CompiledPlan(null, json.createObjectNode().put("test_base", base), null, Map.of(), List.of(target));
    }
    private ConversationService.TurnHandle begin(String question) {
        String turn = "turn-" + (++queryNo);
        jdbc.update("UPDATE conversation_state SET running_turn_id=?, fencing_token=?, last_query_no=? WHERE conversation_id='c'",
                turn, queryNo, queryNo);
        repository.insertRunningTurn("c", queryNo, turn, null, question, now);
        return new ConversationService.TurnHandle(queryNo, turn, queryNo);
    }
    private TaskVersion commit(String question, String status, CompiledPlan plan, TaskScope scope) {
        var turn = begin(question);
        var evidence = json.createArrayNode();
        evidence.addObject().put("node_id", "metric_0").put("ok", true).put("fact_id", "fact-" + queryNo)
                .putObject("data").put("candle_interval", "5m").putArray("samples").add(123);
        return tx.execute(ignored -> service.finishTaskTurn("c", turn, question, status, "answer", "trace", plan,
                evidence, scope, now, ZoneOffset.UTC, "response").taskVersion());
    }
    @Test void storesOneCompleteVersionAndSeparatesRequirementsFromExecution() {
        var first = commit("BTC价格", "SUCCEEDED", plan("BTC"), TaskScope.current(null));
        assertEquals(first, repository.findActiveTaskVersion("c"));
        assertEquals(first, repository.findLatestTaskVersion("c", first.taskId()));
        assertNull(repository.findLatestTaskVersion("other", first.taskId()));
        assertEquals(1L, repository.findTask("c", first.taskId()).latestSuccessfulVersion());
        assertEquals(first.taskId(), jdbc.queryForObject("SELECT task_id FROM conversation_query", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_task_version", Integer.class));
        assertFalse(json.valueToTree(first.queryDefinition()).has("focus"));
        assertFalse(json.valueToTree(first.queryDefinition().targets().get(0)).has("executionMetadata"));
        assertEquals("5m", first.executionSummary().metadata("metric_0").path("candle_interval").asText());
        assertFalse(first.executionSummary().metadata("metric_0").has("samples"));
    }
    @Test void resumeAppendsAfterLatestAndFailedResumeDoesNotRegressSuccessfulPointer() {
        var first = commit("BTC价格", "SUCCEEDED", plan("BTC"), TaskScope.current(null));
        String original = repository.taskVersion("c", first.taskId(), 1).toJson();
        var second = commit("换ETH", "SUCCEEDED", plan("ETH"), TaskScope.current(first));
        var failed = commit("回到BTC重试", "FAILED", plan("BTC"), new TaskScope(TaskScope.Action.RESUME, first));
        assertEquals(3, failed.version());
        assertEquals(1, failed.baseVersion());
        assertEquals(original, repository.taskVersion("c", first.taskId(), 1).toJson());
        assertEquals(second, repository.latestSuccessfulTaskVersion("c", first.taskId()));
        assertEquals(List.of(3L, 2L), repository.recentTaskVersions("c", first.taskId(), 2).stream().map(TaskVersion::version).toList());
        assertEquals(failed, repository.firstTaskLatestVersion("c"));
        assertEquals(first, repository.firstTaskVersion("c", first.taskId()));
    }
    @Test void clarificationCanExistBeforeAnyExecutableDefinition() {
        var pending = commit("查价格", "NEEDS_CLARIFICATION", null, TaskScope.current(null));
        assertNull(repository.findTask("c", pending.taskId()).latestSuccessfulVersion());
        assertNull(repository.firstExecutableTaskVersion("c", pending.taskId()));
        var success = commit("BTC", "SUCCEEDED", plan("BTC"), TaskScope.current(pending));
        assertEquals(success, repository.firstExecutableTaskVersion("c", pending.taskId()));
        assertEquals(success, repository.latestSuccessfulTaskVersion("c", pending.taskId()));
        var input = new ConversationContextService(repository).buildPlanningContext("c");
        assertEquals(2, input.candidates().size());
        assertEquals(2, input.candidates().stream().map(TaskVersionCandidate::candidateRef).distinct().count());
    }
    @Test void versionPrimaryKeyPreventsOverwritingHistory() {
        commit("BTC价格", "SUCCEEDED", plan("BTC"), TaskScope.current(null));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO conversation_task_version SELECT * FROM conversation_task_version
                """));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_task_version", Integer.class));
    }
    @Test void pointerFailureRollsBackVersionHeaderTurnAndLease() {
        var turn = begin("BTC价格");
        doThrow(new IllegalStateException("pointer failed")).when(repository).activateTask(anyString(), any());
        assertThrows(IllegalStateException.class, () -> tx.execute(ignored -> service.finishTaskTurn("c", turn,
                "BTC价格", "SUCCEEDED", "answer", "trace", plan("BTC"), null, TaskScope.current(null), now, ZoneOffset.UTC, null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_task", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM conversation_task_version", Integer.class));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT turn_status FROM conversation_query", String.class));
        assertEquals(turn.turnId(), jdbc.queryForObject("SELECT running_turn_id FROM conversation_state", String.class));
    }
    @Test void planningContextRejectsAmbiguousReferencesAndForeignVersions() {
        var current = commit("BTC价格", "SUCCEEDED", plan("BTC"), TaskScope.current(null));
        var candidate = new TaskVersionCandidate("candidate_1", current);
        assertThrows(IllegalArgumentException.class, () -> new PlanningContext("", current, List.of(), 0,
                List.of(candidate, candidate)));
        var other = ContextFixtures.version("other", "other-conversation", 1, "foreign-turn", "其他查询",
                now.toString(), "UTC", List.of(), null, "SUCCEEDED");
        assertThrows(IllegalArgumentException.class, () -> new PlanningContext("", current, List.of(), 0,
                List.of(new TaskVersionCandidate("candidate_2", other))));
    }

}
