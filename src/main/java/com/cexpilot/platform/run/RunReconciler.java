package com.cexpilot.platform.run;

import com.cexpilot.platform.tool.control.ToolGateway;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.*;

/** 推拉结合：事件只提前 nextAttempt；到期扫描补偿漏消息、完成未恢复以及进程重启后的过期租约。 */
@Component
public class RunReconciler implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunReconciler.class);
    private final DurableStore store; private final ToolGateway tools; private final RunCoordinator runs; private final Clock clock;
    private final com.cexpilot.platform.plan.PreparedPlanService plans;
    private final Set<String> local=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
            r->{var thread=new Thread(r,"platform-resume");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public RunReconciler(DurableStore store,ToolGateway tools,RunCoordinator runs,Clock clock) {this(store,tools,runs,clock,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public RunReconciler(DurableStore store,ToolGateway tools,RunCoordinator runs,Clock clock,com.cexpilot.platform.plan.PreparedPlanService plans) {
        this.store=store;this.tools=tools;this.runs=runs;this.clock=clock;this.plans=plans;
    }
    /** 扫描入口只处理调度失败；工作线程的异常由 reconcile 收口。 */
    @Scheduled(fixedDelayString="${platform.run.scan-interval-ms:1000}") public void scan() {
        try {
            scheduleDueWork();
        } catch (RuntimeException failure) {
            log.error("持久化运行扫描失败，等待下一轮扫描", failure);
        }
    }

    private void scheduleDueWork() {
        for(var kind:DurableStore.Kind.values()) for(String id:store.due(kind,clock.instant(),32)) {
            String key=kind+":"+id;if(!local.add(key))continue;
            try { workers.execute(() -> reconcile(kind, id, key)); }
            catch(RejectedExecutionException full) {local.remove(key);return;}
        }
    }

    /** 异步任务的异常不能依赖发起扫描的线程处理；数据库到期记录保留后续补偿机会。 */
    private void reconcile(DurableStore.Kind kind, String id, String key) {
        try {
            switch (kind) {
                case CALL -> tools.process(id);
                case RUN -> runs.drive(id);
                case PLAN -> {
                    if (plans == null) throw new IllegalStateException("方案审批服务未装配");
                    plans.advance(id);
                }
            }
        } catch (RuntimeException failure) {
            log.error("持久化任务推进失败 kind={} id={}", kind, id, failure);
        } finally {
            local.remove(key);
        }
    }
    @PreDestroy @Override public void close(){workers.shutdownNow();}
}
