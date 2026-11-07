package com.cexpilot.runtime;

import com.cexpilot.config.QueryExecutionConfig;
import com.cexpilot.llm.ChatResponse;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

class QueryBudgetTest {
    @Test void tighteningCannotReenableAdaptiveOrExpandContinuationAllowance() {
        var config=new QueryExecutionConfig();config.setMaxContinuations(1);
        try(var scope=QueryBudget.open(config)) {
            var budget=QueryBudget.current();budget.beforeModel(false);
            var disabled=new QueryExecutionConfig();disabled.setAdaptiveEnabled(false);
            budget.tighten(disabled);
            budget.tighten(new QueryExecutionConfig());
            assertFalse(budget.config().isAdaptiveEnabled());assertEquals(1,budget.config().getMaxContinuations());
            assertEquals(1,budget.snapshot().path("model_calls").asInt());
            assertTrue(config.isAdaptiveEnabled());
        }
    }
    @Test void nestedScopesShareCountersReserveAnswerAndReleaseAtRequestEnd() {
        var config=new QueryExecutionConfig();config.setMaxModelCalls(3);
        try(var root=QueryBudget.open(config)) {
            var budget=QueryBudget.current();budget.beforeModel(false);
            try(var child=QueryBudget.open(new QueryExecutionConfig())) {
                assertSame(budget,QueryBudget.current());budget.beforeModel(true);
                assertEquals("MODEL_BUDGET",assertThrows(QueryBudget.Exhausted.class,()->budget.beforeModel(true)).reason);
            }
            assertSame(budget,QueryBudget.current());budget.beforeModel(false);
            assertEquals(3,budget.snapshot().path("model_calls").asInt());
            assertThrows(QueryBudget.Exhausted.class,()->budget.beforeModel(false));
        }
        assertNull(QueryBudget.current());
    }
    @Test void unknownTokensDoNotBecomeFreeCalls() {
        try(var root=QueryBudget.open(new QueryExecutionConfig())) {
            var budget=QueryBudget.current();budget.beforeModel(false);
            budget.observe(new ChatResponse("ok",List.of(),null,null));
            assertEquals("TOKEN_BUDGET",assertThrows(QueryBudget.Exhausted.class,()->budget.beforeModel(false)).reason);
            assertTrue(budget.snapshot().path("token_usage_unknown").asBoolean());
        }
    }
    @Test void toolAdmissionIsCumulativeAndDeniedAdmissionDoesNotSpendBudget() {
        var config=new QueryExecutionConfig();config.setMaxToolCalls(3);
        try(var root=QueryBudget.open(config)) {
            var budget=QueryBudget.current();budget.admitTools(2);
            assertEquals("TOOL_BUDGET",assertThrows(QueryBudget.Exhausted.class,()->budget.admitTools(2)).reason);
            budget.admitTools(1);assertEquals(3,budget.snapshot().path("tool_nodes").asInt());
        }
    }
    @Test void modelIoDeadlineReturnsWithoutWaitingForDependency() {
        var config=new QueryExecutionConfig();config.setTimeoutMs(100);
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
            try(var root=QueryBudget.open(config)) {
                var budget=QueryBudget.current();budget.beforeModel(false);
                var failure=assertThrows(QueryBudget.Exhausted.class,()->QueryBudget.invoke(()->{
                    try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    return new ChatResponse("late",List.of(),1,1);
                }));
                assertEquals("DEADLINE",failure.reason);
            }
            assertNull(QueryBudget.current());
        });
    }
}
