package com.cexpilot.business.demo;

import com.cexpilot.Application;
import com.cexpilot.platform.agent.AgentRegistry;
import com.cexpilot.platform.execution.config.HarnessRolloutConfig;
import com.cexpilot.platform.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 使用完整生产装配与本地数据库；不调用模型、交易所或邮件服务。 */
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo_context;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "monitor.enabled=false", "demo.enabled=true", "demo.visitors=demo-tester",
        "harness.rollout.http-agents-percent=100", "logging.file.name=target/demo-context.log"
})
class DemoApplicationContextTest {
    @Autowired AgentRegistry agents;
    @Autowired ToolRegistry tools;
    @Autowired HarnessRolloutConfig rollout;
    @Autowired JdbcTemplate jdbc;

    @Test void enabledApplicationRegistersBothDemoEnginesWithoutReplacingCexAgents() {
        assertNotNull(agents.require("query"));
        assertNotNull(agents.require("monitor"));
        assertNotNull(agents.requireAllowed("demo_react", "demo-tester"));
        assertNotNull(agents.requireAllowed("demo_plan", "demo-tester"));
        assertEquals(4, agents.definitions("demo-tester", rollout.decide("demo-tester")).size());
        assertEquals(2, agents.definitions("other", rollout.decide("other")).size());
        assertThrows(SecurityException.class, () -> agents.requireAllowed("demo_plan", "other"));
        assertNotNull(tools.get("demo.ticket.create"));
        assertNotNull(tools.get("demo.ticket.create_async"));
        assertNotNull(tools.get("demo.ticket.assign"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM demo_ticket", Integer.class));
    }
}
