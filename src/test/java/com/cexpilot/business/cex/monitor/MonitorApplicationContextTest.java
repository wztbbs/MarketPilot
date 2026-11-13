package com.cexpilot.business.cex.monitor;

import com.cexpilot.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 默认关闭时整应用仍能启动，且新线程池不影响既有 SSE 执行器。使用完整新库 schema。 */
@SpringBootTest(classes=Application.class,properties={
        "spring.datasource.url=jdbc:h2:mem:monitor_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "monitor.enabled=false","logging.file.name=target/monitor-context.log",
        "harness.rollout.adaptive-query-percent=0","harness.rollout.http-agents-percent=25"
})
@AutoConfigureMockMvc
class MonitorApplicationContextTest {
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitorConfig config;
    @Autowired MockMvc mvc;
    @Test void rolloutPropertiesBindWithoutChangingGlobalQueryConfiguration() {
        var rollout=context.getBean(com.cexpilot.platform.execution.config.HarnessRolloutConfig.class);
        assertEquals(0,rollout.getAdaptiveQueryPercent());assertEquals(25,rollout.getHttpAgentsPercent());
        var query=context.getBean(com.cexpilot.platform.execution.config.QueryExecutionConfig.class);
        assertTrue(query.isAdaptiveEnabled());
        assertFalse(rollout.queryConfig(query,rollout.decide("visitor")).isAdaptiveEnabled());
    }
    @Test void httpEvidenceDoesNotDisplaceQueryCandidates() throws Exception {
        var store=context.getBean(com.cexpilot.business.cex.evidence.EvidenceStore.class);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var now=java.time.Instant.now();
        String conversation="http-evidence-test";
        try {
            var queryFact=store.saveDerivedFact(conversation,"turn","trace","avg",
                    mapper.createObjectNode().put("value",123),java.util.List.of(),now.minusSeconds(60)).orElseThrow();
            var provenance=mapper.createObjectNode().put("contract_version","test-v1")
                    .put("observed_at",now.toString());
            com.cexpilot.business.cex.evidence.EvidenceFact httpFact=null;
            for(int i=0;i<55;i++) httpFact=store.saveToolFact(conversation,"turn","trace","test_agent","test.lookup",
                    mapper.createObjectNode().put("value",i),provenance,now).orElseThrow();
            // H2 的 CAST(VARCHAR AS JSON) 会编码成 JSON 字符串；转为 MySQL 实际存储的 JSON 对象。
            for(var row:jdbc.queryForList("SELECT artifact_id,metadata_json,payload_json FROM conversation_artifact WHERE conversation_id=?",conversation)) {
                String id=(String)row.get("artifact_id");
                String metadata=jdbc.queryForObject("SELECT metadata_json FROM conversation_artifact WHERE artifact_id=?",String.class,id);
                String payload=jdbc.queryForObject("SELECT payload_json FROM conversation_artifact WHERE artifact_id=?",String.class,id);
                jdbc.update("UPDATE conversation_artifact SET metadata_json=? FORMAT JSON,payload_json=? FORMAT JSON WHERE artifact_id=?",
                        mapper.readTree(metadata).asText(),mapper.readTree(payload).asText(),id);
            }
            assertEquals(java.util.List.of(queryFact.factId()),store.listReusable(conversation,now).stream()
                    .map(com.cexpilot.business.cex.evidence.EvidenceFact::factId).toList());
            assertEquals(54,store.loadForConversation(conversation,httpFact.factId()).orElseThrow().payload().path("data").path("value").asInt());
            assertTrue(store.loadForConversation("another-conversation",httpFact.factId()).isEmpty());
            assertEquals(55,jdbc.queryForObject("SELECT COUNT(*) FROM conversation_artifact WHERE conversation_id=? AND kind='HTTP_TOOL'",
                    Integer.class,conversation));
        } finally {
            jdbc.update("DELETE FROM conversation_artifact WHERE conversation_id=?",conversation);
        }
    }
    @Test void registeredAgentsUseProductionAdaptersWithCompatibleRoutes() {
        var registry=context.getBean(com.cexpilot.platform.agent.AgentRegistry.class);
        assertSame(context.getBean(com.cexpilot.business.cex.query.MarketAnalysisAgent.class),registry.require("query"));
        assertSame(context.getBean(MonitorAgent.class),registry.require("monitor"));
        assertEquals("market_analysis",registry.require("query").definition().id());
        assertEquals(2,registry.definitions().size());
        assertNotNull(context.getBean(com.cexpilot.platform.agent.AgentDispatcher.class).promptVersion());
    }
    @Test void fullSchemaAndExistingExecutorRemainAvailable() {
        assertFalse(config.isEnabled());
        assertInstanceOf(TaskExecutor.class,context.getBean("applicationTaskExecutor"));
        assertNotNull(context.getBean(MonitorScheduler.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM monitor_rule",Integer.class));
    }
    @Test void homepageSuggestionsAreAvailableWithoutVisitorOrLlm() throws Exception {
        mvc.perform(get("/api/home/suggestions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].query").isNotEmpty())
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void removedLegacyRoutesAndAssetsAreNoLongerExposed() throws Exception {
        for(String path:java.util.List.of("/api/monitors","/api/monitors/removed","/monitors.html","/monitors.js"))
            mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitors").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}
