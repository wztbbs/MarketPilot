package com.cexpilot.platform.run.fixture;

import com.cexpilot.platform.run.DurableStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.Properties;

/** 集成测试启动的真实 JVM；父测试会 force-kill，随后以相同数据库启动新的 JVM。 */
public final class RunContractWorker {
    public static JdbcTemplate localMysql(Path config) throws Exception {
        var settings = new Properties(); try (var input = Files.newInputStream(config)) { settings.load(input); }
        String url = settings.getProperty("url", "");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cexpilot_run_contract(?:\\?.*)?"))
            throw new IllegalArgumentException("此验证仅允许独立的本地 MySQL 测试库");
        return new JdbcTemplate(new DriverManagerDataSource(url, settings.getProperty("username"), settings.getProperty("password")));
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0], reference = args[1]; var jdbc = localMysql(Path.of(args[2]));
        var clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(Long.parseLong(args[4])));
        Runnable afterCommit = Boolean.parseBoolean(args[5]) ? () -> {
            System.out.println("BUSINESS_COMMITTED"); System.out.flush();
            try { Thread.sleep(120000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } : () -> {};
        try (var fixture = new RunContractFixture(jdbc, URI.create(args[3]), clock, afterCommit, false)) {
            String runId;
            if ("start".equals(mode)) {
                var result = fixture.start(reference); runId = result.agentPayload().path("runId").asText();
                System.out.println("RUN " + runId + " " + result.turnStatus()); System.out.flush();
            } else runId = reference;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                fixture.reconciler.scan();
                var run = fixture.store.require(DurableStore.Kind.RUN, runId);
                if (run.data().path("completionPublished").asBoolean()) {
                    System.out.println("PUBLISHED " + runId + " " + run.status()); System.out.flush();
                    if (!"SUCCEEDED".equals(run.status())) throw new IllegalStateException("业务运行失败");
                    return;
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("本地恢复验证超时");
        }
    }
}
