package com.cexpilot.platform.run;

import com.cexpilot.platform.run.fixture.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.cexpilot.platform.run.DurableStore.Kind.*;

/** 显式执行的本地集成测试：真实 MySQL + 独立模拟服务 + force-kill/restart 的 JVM。 */
class BusinessRunMySqlIT {
    static Path configuration;
    static JdbcTemplate jdbc;
    static DurableStore store;
    static RunTestEvidence evidence;
    static final ThreadLocal<String> scenario = new ThreadLocal<>();
    @org.junit.jupiter.api.extension.RegisterExtension
    static final org.junit.jupiter.api.extension.TestWatcher outcomes=new org.junit.jupiter.api.extension.TestWatcher(){
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context){if(evidence!=null)evidence.result(context.getRequiredTestMethod().getName(),"PASS",null);}
        public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context,Throwable cause){if(evidence!=null)evidence.result(context.getRequiredTestMethod().getName(),"FAIL",cause);}
        public void testAborted(org.junit.jupiter.api.extension.ExtensionContext context,Throwable cause){if(evidence!=null)evidence.result(context.getRequiredTestMethod().getName(),"ABORTED",cause);}
    };
    @BeforeEach void begin(TestInfo test){scenario.set(test.getTestMethod().orElseThrow().getName());evidence.event(scenario.get(),"test_started",RunContractFixture.JSON.createObjectNode());}
    @AfterAll static void finished(){if(evidence!=null)evidence.finish();}
    @BeforeAll static void database() throws Exception {
        String directory=System.getProperty("run.contract.evidence");
        if(directory==null)directory="importants/回归测试/运行恢复/"+java.time.Instant.now().toString().replace(':','-')+"-"+UUID.randomUUID();
        evidence=new RunTestEvidence(Path.of(directory));
        String setting = System.getProperty("run.mysql.config");
        assertNotNull(setting, "必须指定 -Drun.mysql.config=<独立本地 MySQL 配置文件>；不回退 H2");
        configuration = Path.of(setting).toAbsolutePath(); jdbc = RunContractWorker.localMysql(configuration);
        String serverVersion=jdbc.queryForObject("SELECT VERSION()",String.class);
        assertTrue(serverVersion.startsWith("8."), "需要真实 MySQL 8");
        evidence.event("suite","mysql_verified",RunContractFixture.JSON.createObjectNode().put("serverVersion",serverVersion).put("database","cexpilot_run_contract"));
        RunContractFixture.schema(jdbc); store = new DurableStore(jdbc, RunContractFixture.JSON);
    }
    @Test void killedWhileWaitingResumesOriginalPlanWithoutCallback() throws Exception {
        String doc = "wait-" + UUID.randomUUID();
        try (var service = new RunContractService(); var first = worker("start", doc, service, 0, false)) {
            assertTrue(service.accepted.await(15, TimeUnit.SECONDS)); String id = runFor(service.lastInvocationId);
            await(() -> "WAITING".equals(store.require(RUN, id).status()) && store.calls(id).get(0).data().hasNonNull("jobId"));
            var originalTime = store.require(RUN, id).data().path("requestTime").asText(); snapshot("before_kill",id,service);first.kill();snapshot("after_kill",id,service);
            service.done = true;
            try (var restarted = worker("resume", id, service, 0, false)) { restarted.success(); }
            assertCompleted(id, doc, service); assertEquals(originalTime, jdbc.queryForObject("SELECT request_time FROM run_contract_business_result WHERE run_id=?", String.class, id));
            assertEquals(0, service.recoveries.get(), "已有 jobId 的运行直接轮询原任务");snapshot("after_recovery",id,service);
        }
    }
    @Test void acceptanceBeforeLocalJobIdPersistenceRecoversByOriginalInvocationId() throws Exception {
        String doc = "accepted-" + UUID.randomUUID();
        try (var service = new RunContractService()) {
            service.delaySubmissionResponse = true;
            String id;
            try (var first = worker("start", doc, service, 0, false)) {
                assertTrue(service.accepted.await(15, TimeUnit.SECONDS)); id = runFor(service.lastInvocationId);
                var call = store.calls(id).get(0); assertEquals("EXECUTING", call.status()); assertFalse(call.data().hasNonNull("jobId"));
                assertEquals("WAITING_EXPORT", store.require(RUN, id).data().at("/checkpoint/phase").asText());
                snapshot("before_kill",id,service);first.kill();snapshot("after_kill",id,service);
            }
            service.releaseSubmissionResponse.countDown(); service.done = true;
            // 新 JVM 的可控时钟推进租约时间；数据库、外部受理记录与进程退出均是真实发生。
            try (var restarted = worker("resume", id, service, 120, false)) { restarted.success(); }
            assertCompleted(id, doc, service); assertTrue(service.recoveries.get() >= 1);
            assertEquals(service.lastInvocationId, store.calls(id).get(0).id());snapshot("after_recovery",id,service);
        }
    }
    @Test void crashAfterBusinessCommitRetriesPublicationWithoutDuplicateBusinessEffect() throws Exception {
        String doc = "commit-" + UUID.randomUUID(); String id;
        try (var service = new RunContractService(); var first = worker("start", doc, service, 0, true)) {
            assertTrue(service.accepted.await(15, TimeUnit.SECONDS)); id = runFor(service.lastInvocationId); service.done = true;
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM run_contract_business_result WHERE run_id=?", Integer.class, id) == 1);
            assertFalse(store.require(RUN, id).data().path("businessCommitted").asBoolean());snapshot("before_kill",id,service);first.kill();snapshot("after_kill",id,service);
            try (var restarted = worker("resume", id, service, 120, false)) { restarted.success(); }
            assertCompleted(id, doc, service);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM run_contract_commit_attempt WHERE run_id=?", Integer.class, id));snapshot("after_recovery",id,service);
        }
    }
    static void assertCompleted(String id, String doc, RunContractService service) {
        var run = store.require(RUN, id); assertEquals("SUCCEEDED", run.status());
        assertTrue(run.data().path("businessCommitted").asBoolean()); assertTrue(run.data().path("completionPublished").asBoolean());
        assertEquals("EXPORT_COMPLETE", run.data().at("/checkpoint/phase").asText());
        assertEquals(doc, run.data().at("/privateInput/documentId").asText()); assertEquals(doc, run.data().at("/privateOutput/documentId").asText());
        assertEquals(1, run.data().at("/budget/tool_nodes").asInt()); assertFalse(run.data().toString().contains("requestCallback"));
        assertEquals(1, service.effects.get()); assertEquals(1, service.submissions.get()); assertEquals(1, service.plans(doc));
        assertEquals("contract_business", service.job().agent()); assertEquals("alice", service.job().principal());
        assertEquals(7, service.job().arguments().path("version").asInt());
        var rows = jdbc.queryForList("SELECT * FROM run_contract_business_result WHERE run_id=?", id); assertEquals(1, rows.size());
        assertEquals(doc, rows.get(0).get("document_id")); assertEquals("receipt-" + doc, rows.get(0).get("receipt"));
        assertEquals(8, ((Number) rows.get(0).get("next_version")).intValue());
    }
    static String runFor(String invocationId) throws Exception {
        await(() -> !jdbc.queryForList("SELECT run_id FROM platform_tool_call WHERE id=?", String.class, invocationId).isEmpty());
        return jdbc.queryForObject("SELECT run_id FROM platform_tool_call WHERE id=?", String.class, invocationId);
    }
    static Worker worker(String mode, String reference, RunContractService service, long clockOffset, boolean pauseCommit) throws Exception {
        Path logs = evidence.directory().resolve("processes"); Files.createDirectories(logs);
        Path log = logs.resolve(mode + "-" + UUID.randomUUID() + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
                RunContractWorker.class.getName(), mode, reference, configuration.toString(), service.uri().toString(), Long.toString(clockOffset), Boolean.toString(pauseCommit));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();var process=builder.start();
        evidence.event(scenario.get(),"process_started",RunContractFixture.JSON.createObjectNode().put("pid",process.pid()).put("mode",mode)
                .put("reference",reference).put("clockOffsetSeconds",clockOffset).put("pauseAfterCommit",pauseCommit)
                .put("stdout",evidence.directory().relativize(log).toString()));
        return new Worker(process,log,scenario.get(),mode);
    }
    static void snapshot(String stage,String id,RunContractService service){
        var run=store.require(RUN,id);String doc=run.data().at("/privateInput/documentId").asText();
        var counters=RunContractFixture.JSON.createObjectNode().put("planningCalls",service.plans(doc)).put("submissionCalls",service.submissions.get())
                .put("externalEffects",service.effects.get()).put("recoveryCalls",service.recoveries.get());
        evidence.snapshot(scenario.get(),stage,run,store.calls(id),jdbc.queryForList("SELECT * FROM run_contract_business_result WHERE run_id=?",id),
                jdbc.queryForObject("SELECT COUNT(*) FROM run_contract_commit_attempt WHERE run_id=?",Integer.class,id),counters);
    }
    record Worker(Process process,Path log,String scenario,String mode) implements AutoCloseable {
        void kill() throws Exception {stop(true);}
        private void stop(boolean faultInjection) throws Exception {
            boolean alive=process.isAlive();
            if(!alive && !faultInjection)return;
            evidence.event(scenario,"force_kill_requested",RunContractFixture.JSON.createObjectNode().put("pid",process.pid()).put("mode",mode).put("alive",alive).put("faultInjection",faultInjection));
            if(faultInjection)assertTrue(alive,"故障注入必须真实终止仍在运行的 JVM");
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS),"测试 JVM 未退出");
            evidence.event(scenario,"force_kill_finished",RunContractFixture.JSON.createObjectNode().put("pid",process.pid()).put("exitCode",process.exitValue()).put("faultInjection",faultInjection));
        }
        void success() throws Exception {
            boolean exited=process.waitFor(30,TimeUnit.SECONDS);
            var details=RunContractFixture.JSON.createObjectNode().put("pid",process.pid()).put("exited",exited);
            if(exited)details.put("exitCode",process.exitValue());
            evidence.event(scenario,"recovery_process_finished",details);
            assertTrue(exited,"恢复 JVM 超时，日志: "+log);assertEquals(0,process.exitValue(),()->"恢复 JVM 失败，日志: "+log);
        }
        public void close() throws Exception {stop(false);}
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "本地集成验证未在限时内达到预期状态");
    }
}
