package com.cexpilot.business.demo;

import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Map;

/** 独立模拟业务表，重启后保留任务、幂等键和结果；不访问交易所、不发信。 */
public class DemoTickets {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DemoProperties config;

    public DemoTickets(JdbcTemplate jdbc, Clock clock, DemoProperties config) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.config = config;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS demo_ticket(
                    id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(64) NOT NULL,
                    title VARCHAR(200) NOT NULL, ready_at BIGINT NOT NULL,
                    assignee VARCHAR(100), version BIGINT NOT NULL)
                """);
    }

    public ToolExecution create(JsonNode args, ToolContext context, boolean async) {
        config.check(context.visitorId());
        String id = context.invocationId();
        if (id == null) throw new SecurityException("Demo 操作需要持久化调用标识");
        long readyAt = clock.millis() + (async ? config.getDelaySeconds() * 1000 : 0);
        // invocationId 作为幂等键，重复提交不新增工单、不延长就绪时间。
        jdbc.update("INSERT IGNORE INTO demo_ticket(id,owner_id,title,ready_at,version) VALUES (?,?,?,?,1)",
                id, context.visitorId(), args.path("title").asText(), readyAt);
        var row = row(id, context);
        if (!row.get("title").equals(args.path("title").asText()))
            throw new IllegalArgumentException("同一调用不能替换参数");
        return poll(id, context);
    }

    public ToolExecution poll(String id, ToolContext context) {
        var row = row(id, context);
        if (!id.equals(context.invocationId())) throw new SecurityException("Demo 任务归属错误");
        if (((Number) row.get("ready_at")).longValue() > clock.millis()) return ToolExecution.accepted(id, 1000);
        return ToolExecution.completed(ToolResult.success(JSON.createObjectNode().put("ticket_id", id)
                .put("version", ((Number) row.get("version")).longValue()).put("title", row.get("title").toString())));
    }

    public ToolExecution recover(JsonNode args, ToolContext context) {
        config.check(context.visitorId());
        // 模拟按幂等键查单：未知结果时只查询，不能重新创建。
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM demo_ticket WHERE id=? AND owner_id=?",
                Integer.class, context.invocationId(), context.visitorId());
        return count == 0 ? ToolExecution.unknown(1000) : poll(context.invocationId(), context);
    }

    public ToolResult assign(JsonNode args, ToolContext context) {
        String id = args.path("ticket_id").asText();
        var row = row(id, context);
        String assignee = args.path("assignee").asText();
        long version = args.path("version").asLong();
        if ("FAIL".equals(assignee)) return ToolResult.failure("Demo 合成失败：未指派，后续操作应停止");
        if (assignee.equals(row.get("assignee")) && ((Number) row.get("version")).longValue() == version + 1)
            return assigned(id, assignee, version + 1);
        int changed = jdbc.update("""
                UPDATE demo_ticket SET assignee=?,version=version+1
                WHERE id=? AND owner_id=? AND version=? AND ready_at<=?
                """, assignee, id, context.visitorId(), version, clock.millis());
        return changed == 1 ? assigned(id, assignee, version + 1) : ToolResult.failure("Demo 工单版本变化或尚未就绪");
    }

    private ToolResult assigned(String id, String assignee, long version) {
        return ToolResult.success(JSON.createObjectNode().put("ticket_id", id).put("assignee", assignee).put("version", version));
    }

    private Map<String, Object> row(String id, ToolContext context) {
        config.check(context.visitorId());
        var rows = jdbc.queryForList("SELECT * FROM demo_ticket WHERE id=? AND owner_id=?", id, context.visitorId());
        if (rows.isEmpty()) throw new SecurityException("Demo 工单不存在");
        return rows.get(0);
    }
}
