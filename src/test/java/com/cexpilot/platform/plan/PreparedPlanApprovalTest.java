package com.cexpilot.platform.plan;

import com.cexpilot.business.cex.api.AgentRunController;
import com.cexpilot.platform.run.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreparedPlanApprovalTest {
    @Test void apiEnforcesOwnerRunFingerprintAndIdempotentDecisionWithoutExecution() throws Exception {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId); String fingerprint = plan.data().path("fingerprint").asText();
            var mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(f.runs, f.gateway, f.plans)).build();
            var alice = new Cookie("cexpilot_uid", "alice"); var bob = new Cookie("cexpilot_uid", "bob");
            String endpoint = "/api/agent-runs/" + runId + "/plans/" + plan.id() + "/decision";
            mvc.perform(get("/api/agent-runs/" + runId).cookie(bob)).andExpect(status().isNotFound());
            mvc.perform(get("/api/agent-runs/" + runId).cookie(alice)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.plans[0].approvalStatus").value("PENDING")).andExpect(jsonPath("$.plans[0].calls.length()").value(2));
            mvc.perform(post(endpoint).cookie(bob).header("X-Agent-Action", "1").contentType("application/json").content("{\"fingerprint\":\"" + fingerprint + "\",\"approve\":true}")).andExpect(status().isNotFound());
            mvc.perform(post(endpoint).cookie(alice).header("X-Agent-Action", "1").contentType("application/json").content("{\"fingerprint\":\"forged\",\"approve\":true}")).andExpect(status().isConflict());
            mvc.perform(post(endpoint).cookie(alice).contentType("application/json").content("{\"fingerprint\":\"" + fingerprint + "\",\"approve\":true}")).andExpect(status().isBadRequest());
            for (int repeat = 0; repeat < 2; repeat++) mvc.perform(post(endpoint).cookie(alice).header("X-Agent-Action", "1").contentType("application/json").content("{\"fingerprint\":\"" + fingerprint + "\",\"approve\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.plans[0].approvalStatus").value("APPROVED"));
            mvc.perform(post(endpoint).cookie(alice).header("X-Agent-Action", "1").contentType("application/json").content("{\"fingerprint\":\"" + fingerprint + "\",\"approve\":false}")).andExpect(status().isConflict());
            assertEquals(0, f.external.submits.get()); assertTrue(f.store.calls(runId).isEmpty());
            String otherRun = f.start().agentPayload().path("runId").asText();
            mvc.perform(post("/api/agent-runs/" + otherRun + "/plans/" + plan.id() + "/decision").cookie(alice).header("X-Agent-Action", "1")
                    .contentType("application/json").content("{\"fingerprint\":\"" + fingerprint + "\",\"approve\":true}")).andExpect(status().isNotFound());
        }
    }

    @Test void simultaneousOppositeDecisionsHaveOneWinner() throws Exception {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId); var start = new CountDownLatch(1);
            var threads = Executors.newFixedThreadPool(2);
            try {
                var futures = new ArrayList<Future<Boolean>>();
                for (boolean approve : List.of(true, false)) futures.add(threads.submit(() -> {
                    if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("test latch timeout");
                    try { f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), approve); return true; }
                    catch (IllegalArgumentException conflict) { return false; }
                }));
                start.countDown(); int winners = 0;
                for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) winners++;
                assertEquals(1, winners); assertTrue(Set.of("APPROVED", "REJECTED").contains(f.plan(runId).status()));
                assertEquals(0, f.external.submits.get());
            } finally { threads.shutdownNow(); }
        }
    }

    @Test void expiryAfterApprovalBeforeExecutionCannotStartOperations() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); PreparedPlanExecutionTest.approve(f, runId);
            f.external.clock.now = f.external.clock.now.plusSeconds(86401); f.plans.advance(f.plan(runId).id()); f.runs.drive(runId);
            assertEquals("EXPIRED", f.plan(runId).status()); assertEquals(0, f.external.submits.get()); assertEquals("FAILED", f.store.require(RUN, runId).status());
        }
    }

    @Test void pendingPlanNeedsCurrentBindingAndResourcesAtApproval() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); var plan = f.plan(runId);
            f.external.revoked = true;
            assertThrows(SecurityException.class, () -> f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), true));
            f.external.revoked = false; f.external.version.set(2);
            assertThrows(SecurityException.class, () -> f.plans.confirm(plan.id(), "alice", plan.data().path("fingerprint").asText(), true));
            assertEquals("PENDING", f.plan(runId).status()); assertEquals(0, f.external.submits.get());
        }
    }

    @Test void staleRunCannotCreateOrExecutePlans() {
        try (var f = new PreparedPlanFixture()) {
            String runId = f.start().agentPayload().path("runId").asText(); PreparedPlanExecutionTest.approve(f, runId);
            try (var scope = f.attach(runId)) {
                f.external.clock.now = f.external.clock.now.plusSeconds(61);
                var successor = f.store.claim(RUN, runId, f.external.clock.instant(), f.external.clock.instant().plusSeconds(60)); assertNotNull(successor);
                assertThrows(RunLeaseLostException.class, () -> f.plans.prepare("stale", PreparedPlanFixture.proposal(false)));
                assertThrows(RunLeaseLostException.class, () -> f.executor.execute(f.plan(runId).id()));
            }
            assertEquals(0, f.external.submits.get()); assertEquals(1, f.store.plans(runId).size());
        }
    }
}
