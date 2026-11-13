package com.cexpilot.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RunOwnershipTest {
    @Test void pausedClaimantCannotAdoptSuccessorsFenceOrWriteItsCheckpoint() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        com.cexpilot.platform.run.fixture.RunContractFixture.schema(jdbc);
        var current=new DurableStore(jdbc,json);var now=Instant.parse("2026-11-12T00:00:00Z");
        current.create(DurableStore.Kind.RUN,"run","alice",null,"READY",now,json.createObjectNode());
        var afterUpdate=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var delayed=new DurableStore(jdbc,json){
            @Override public Entry require(Kind kind,String id){
                afterUpdate.countDown();
                try{if(!resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("测试同步超时");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return super.require(kind,id);
            }
        };
        var executor=Executors.newSingleThreadExecutor();
        try {
            var old=executor.submit(()->delayed.claim(DurableStore.Kind.RUN,"run",now,now.plusSeconds(1)));
            assertTrue(afterUpdate.await(5,TimeUnit.SECONDS));
            var successor=current.claim(DurableStore.Kind.RUN,"run",now.plusSeconds(2),now.plusSeconds(30));
            assertNotNull(successor);assertEquals(2,successor.fence());resume.countDown();
            assertNull(old.get(5,TimeUnit.SECONDS),"旧线程不能把新执行者的领取结果视为自己的租约");
            try(var stale=new RunScope(current,json,Clock.fixed(now.plusSeconds(2),ZoneOffset.UTC),"run",1)){
                assertThrows(IllegalStateException.class,()->stale.progress(new RunCheckpoint("STALE",json.createObjectNode())));
            }
            assertFalse(current.require(DurableStore.Kind.RUN,"run").data().has("checkpoint"));
        } finally {resume.countDown();executor.shutdownNow();}
    }
}
