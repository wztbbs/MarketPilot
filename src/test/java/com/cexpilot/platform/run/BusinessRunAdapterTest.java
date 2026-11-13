package com.cexpilot.platform.run;

import com.cexpilot.platform.run.fixture.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

/** 快速验证通用契约；真实 MySQL/强杀 JVM 的验证在 BusinessRunMySqlIT。 */
class BusinessRunAdapterTest {
    static final class ClockControl extends Clock {
        Instant now = Instant.parse("2026-11-12T01:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
    }
    @Test void businessCheckpointPrivateCodecsAndCommitSurviveReassembly() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        RunContractFixture.schema(jdbc); var clock = new ClockControl(); String id;
        try (var service = new RunContractService()) {
            try (var first = new RunContractFixture(jdbc, service.uri(), clock, () -> {}, true)) {
                var result = first.start("document-a"); assertEquals("WAITING", result.turnStatus()); id = result.agentPayload().path("runId").asText();
                var run = first.store.require(RUN, id); assertEquals("WAITING_EXPORT", run.data().at("/checkpoint/phase").asText());
                assertEquals(7, run.data().at("/privateInput/version").asInt()); assertFalse(run.data().toString().contains("requestCallback"));
                var call = first.store.calls(id).get(0); assertEquals("CONFIRMATION_REQUIRED", call.status()); assertEquals(0, service.effects.get());
                first.gateway.confirm(call.id(), "alice", call.data().path("fingerprint").asText(), true); first.gateway.process(call.id());
                assertEquals(1, service.effects.get());
            }
            service.done = true; clock.now = clock.now.plusSeconds(3600);
            try (var resumed = new RunContractFixture(jdbc, service.uri(), clock, () -> {}, true)) {
                resumed.gateway.process(resumed.store.calls(id).get(0).id()); resumed.runs.drive(id); resumed.runs.drive(id);
                var run = resumed.store.require(RUN, id);
                assertEquals("SUCCEEDED", run.status()); assertTrue(run.data().path("completionPublished").asBoolean());
                assertEquals("EXPORT_COMPLETE", run.data().at("/checkpoint/phase").asText()); assertEquals("document-a", run.data().at("/privateOutput/documentId").asText());
                assertTrue(run.data().at("/result/output").isNull()); assertEquals(1, service.plans("document-a")); assertEquals(1, service.submissions.get());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM run_contract_business_result WHERE run_id=?", Integer.class, id));
                var output = jdbc.queryForMap("SELECT * FROM run_contract_business_result WHERE run_id=?", id);
                assertEquals("alice", output.get("principal_id")); assertEquals(8, ((Number) output.get("next_version")).intValue());
                assertEquals("2026-11-12T01:00:00Z", output.get("request_time"));
            }
        }
    }
    @Test void inputCodecChangesPreventExecutionAndMultipleAdaptersAreRejected() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        RunContractFixture.schema(jdbc); var clock = new ClockControl();
        try (var service = new RunContractService(); var fixture = new RunContractFixture(jdbc, service.uri(), clock, () -> {}, true)) {
            var initial = fixture.start("document-b"); String id = initial.agentPayload().path("runId").asText();
            var run = fixture.store.require(RUN, id); var data = run.data().put("adapterVersion", "incompatible");
            assertTrue(fixture.store.update(RUN, run, run.status(), clock.instant(), null, data)); fixture.runs.drive(id);
            assertEquals("FAILED", fixture.store.require(RUN, id).status()); assertEquals(0, service.effects.get());
            var another = fixture.new BusinessAdapter(() -> {}) { @Override public String id() { return "another-adapter"; } };
            assertThrows(IllegalStateException.class, () -> new RunAdapterRegistry(List.of(fixture.adapter, another)).find(fixture.agent));
            assertThrows(IllegalArgumentException.class, () -> new RunAdapterRegistry(List.of(fixture.adapter, fixture.adapter)));
        }
    }
    @Test void dispatcherSelectsBusinessAdapterWithoutCastingAgentToDeclaredType() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        RunContractFixture.schema(jdbc);var clock=new ClockControl();
        try(var service=new RunContractService();var fixture=new RunContractFixture(jdbc,service.uri(),clock,()->{},true)) {
            var router=org.mockito.Mockito.mock(com.cexpilot.platform.agent.IntentRouter.class);
            org.mockito.Mockito.when(router.route(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.cexpilot.platform.agent.IntentRouter.Decision("contract",null,List.of()));
            var dispatcher=new com.cexpilot.platform.agent.AgentDispatcher(router,new com.cexpilot.platform.agent.AgentRegistry(List.of(fixture.agent)),new com.cexpilot.platform.context.BasicAgentContextProvider());
            var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("runs",fixture.runs);
            org.springframework.test.util.ReflectionTestUtils.setField(dispatcher,"durableRuns",beans.getBeanProvider(RunCoordinator.class));
            var request=com.cexpilot.platform.agent.AgentExecutionRequest.builder("导出文档","trace-business",e->{})
                    .requestContext(new com.cexpilot.platform.execution.RequestContext(java.time.ZoneOffset.UTC,clock.instant()))
                    .visitor("alice").input(new RunContractFixture.Input("document-dispatch",7,()->{})).build();
            var result=dispatcher.execute(request);assertEquals("WAITING",result.turnStatus());assertEquals("contract",result.agentType());
            assertEquals(1,service.plans("document-dispatch"));assertEquals(0,service.effects.get());
            var run=fixture.store.require(RUN,result.agentPayload().path("runId").asText());
            assertEquals("contract-business",run.data().path("adapterId").asText());
        }
    }
}
