package com.cexpilot.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

/**
 * JSON 保存异构检查点，调度字段单列索引。revision + fencing_token 防止旧执行者覆盖接管后的状态。
 */
@Repository
public class DurableStore {
    public enum Kind {
        RUN("platform_run"), CALL("platform_tool_call"), PLAN("platform_prepared_plan");
        final String table;

        Kind(String table) {
            this.table = table;
        }
    }

    /** 通用存储记录保留原始状态字符串；使用方按记录种类转为 RunStatus、ToolCallStatus 或 PlanApprovalStatus。 */
    public record Entry(String id, String owner, String runId, String status, long revision, long fence,
                        Instant nextAttempt, Instant leaseUntil, ObjectNode data) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public DurableStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    /** 仅用于数据库记录的短事务；外部工具、业务权限服务与资源查询不得放进回调。 */
    public <T> T transaction(java.util.function.Supplier<T> action) { return transactions.execute(ignored -> action.get()); }

    /** 调用方必须处于短事务中，统一按 RUN → PLAN → CALL 顺序加锁。 */
    public Entry lock(Kind kind, String id) {
        jdbc.queryForList("SELECT id FROM " + kind.table + " WHERE id=? FOR UPDATE", String.class, id);
        return require(kind, id);
    }

    public Entry create(Kind kind, String id, String owner, String runId, String status, Instant next, ObjectNode data) {
        jdbc.update("INSERT INTO " + kind.table + " (id,owner_id,run_id,status,revision,fencing_token,next_attempt_at,payload_text) VALUES (?,?,?,?,0,0,?,?)",
                id, owner, runId, status, timestamp(next), data.toString());
        return require(kind, id);
    }

    public Entry find(Kind kind, String id) {
        return jdbc.query("SELECT * FROM " + kind.table + " WHERE id=?", (r, i) -> {
            try {
                return new Entry(r.getString("id"), r.getString("owner_id"), r.getString("run_id"), r.getString("status"),
                        r.getLong("revision"), r.getLong("fencing_token"), instant(r.getObject("next_attempt_at", LocalDateTime.class)),
                        instant(r.getObject("lease_until", LocalDateTime.class)), (ObjectNode) json.readTree(r.getString("payload_text")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("执行记录损坏", e);
            }
        }, id).stream().findFirst().orElse(null);
    }

    public Entry require(Kind kind, String id) {
        var entry = find(kind, id);
        if (entry == null) throw new IllegalArgumentException("执行记录不存在");
        return entry;
    }

    public boolean update(Kind kind, Entry entry, String status, Instant next, Instant lease, ObjectNode data) {
        return jdbc.update("UPDATE " + kind.table + " SET status=?,next_attempt_at=?,lease_until=?,payload_text=?,revision=revision+1 WHERE id=? AND revision=? AND fencing_token=?",
                status, timestamp(next), timestamp(lease), data.toString(), entry.id(), entry.revision(), entry.fence()) == 1;
    }

    public Entry claim(Kind kind, String id, Instant now, Instant leaseUntil) {
        var before = find(kind, id);
        if (before == null) return null;
        int changed = jdbc.update("UPDATE " + kind.table + " SET lease_until=?,fencing_token=fencing_token+1,revision=revision+1 WHERE id=? AND revision=? AND fencing_token=? AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)",
                timestamp(leaseUntil), id, before.revision(), before.fence(), timestamp(now), timestamp(now));
        if (changed == 0) return null;
        var claimed = require(kind, id);
        // UPDATE 成功后本线程也可能暂停很久；不能把接管者的新 token 当成自己的领取结果。
        return claimed.fence() == before.fence() + 1 ? claimed : null;
    }

    public List<String> due(Kind kind, Instant now, int limit) {
        return jdbc.queryForList("SELECT id FROM " + kind.table + " WHERE next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT ?",
                String.class, timestamp(now), timestamp(now), limit);
    }

    public List<Entry> calls(String runId) {
        return jdbc.queryForList("SELECT id FROM platform_tool_call WHERE run_id=? ORDER BY id", String.class, runId)
                .stream().map(id -> require(Kind.CALL, id)).toList();
    }

    public List<Entry> plans(String runId) {
        return jdbc.queryForList("SELECT id FROM platform_prepared_plan WHERE run_id=? ORDER BY id", String.class, runId)
                .stream().map(id -> require(Kind.PLAN, id)).toList();
    }

    public ObjectNode journal(String runId, String key) {
        var values = jdbc.queryForList("SELECT payload_text FROM platform_run_checkpoint WHERE run_id=? AND checkpoint_key=?", String.class, runId, key);
        try {
            return values.isEmpty() ? null : (ObjectNode) json.readTree(values.get(0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 检查点必须由仍持租约的运行写入；单条 SQL 的 INSERT SELECT 同时完成 fencing 检查。
     */
    public void checkpoint(String runId, long fence, String key, ObjectNode value, Instant now) {
        int changed = jdbc.update("INSERT INTO platform_run_checkpoint (run_id,checkpoint_key,payload_text) SELECT id,?,? FROM platform_run WHERE id=? AND fencing_token=? AND lease_until>?",
                key, value.toString(), runId, fence, timestamp(now));
        if (changed != 1) throw new RunLeaseLostException("运行租约已失效");
    }

    public boolean holds(String runId, long fence, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM platform_run WHERE id=? AND fencing_token=? AND lease_until>?",
                Boolean.class, runId, fence, timestamp(now)));
    }

    public void wake(String runId, Instant now) {
        // 执行中只置调度时间，不撤销租约；扫表会补偿消息与 WAITING 落库之间的竞争。
        jdbc.update("UPDATE platform_run SET next_attempt_at=? WHERE id=? AND status IN (?,?,?)",
                timestamp(now), runId, RunStatus.READY.name(), RunStatus.RUNNING.name(), RunStatus.WAITING.name());
    }

    private static LocalDateTime timestamp(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
