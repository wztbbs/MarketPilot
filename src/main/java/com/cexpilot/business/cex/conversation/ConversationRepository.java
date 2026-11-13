package com.cexpilot.business.cex.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    private final com.cexpilot.business.cex.time.DatabaseTime databaseTime;
    public ConversationRepository(JdbcTemplate jdbc) { this(jdbc, com.cexpilot.business.cex.time.DatabaseTime.legacyDefault()); }
    @org.springframework.beans.factory.annotation.Autowired
    public ConversationRepository(JdbcTemplate jdbc, com.cexpilot.business.cex.time.DatabaseTime databaseTime) {
        this.jdbc = jdbc;
        this.databaseTime = databaseTime;
    }

    public com.fasterxml.jackson.databind.JsonNode routingState(String conversationId) {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var rows=jdbc.query("SELECT active_agent_type,monitor_planning_json FROM conversation_state WHERE conversation_id=?",(rs,n)->{
            var state=mapper.createObjectNode().put("active_agent",rs.getString("active_agent_type")==null?"query":rs.getString("active_agent_type"));
            String saved=rs.getString("monitor_planning_json");
            try {
                var monitor=saved==null?mapper.createObjectNode():mapper.readTree(saved);
                if(monitor.isTextual()) monitor=mapper.readTree(monitor.textValue());
                state.set("monitor_context",monitor); return state;
            } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("监控规划上下文损坏",error); }
        },conversationId);
        return rows.isEmpty()?mapper.createObjectNode():rows.get(0);
    }

    public boolean turnCompleted(String turnId) {
        var rows=jdbc.queryForList("SELECT turn_status FROM conversation_query WHERE turn_id=? FOR UPDATE",String.class,turnId);
        return !rows.isEmpty() && rows.get(0)!=null && !java.util.Set.of("RUNNING","WAITING").contains(rows.get(0));
    }
    public void saveAgentOutcome(String turnId,String agent,com.fasterxml.jackson.databind.JsonNode payload) {
        jdbc.update("UPDATE conversation_query SET agent_type=?,agent_payload_json=? WHERE turn_id=?",
                agent,payload==null?null:payload.toString(),turnId);
    }

    /** 调用方须在同一事务内持有当前会话行锁，lease 释放后也不能让新请求插入。 */
    public void saveRoutingState(String conversationId,long token,String agent,com.fasterxml.jackson.databind.JsonNode monitor) {
        if("clarify".equals(agent)) return;
        jdbc.update("""
                UPDATE conversation_state SET active_agent_type=?,monitor_planning_json=COALESCE(?,monitor_planning_json)
                WHERE conversation_id=? AND fencing_token=?
                """,agent,monitor==null?null:monitor.toString(),conversationId,token);
    }

    public boolean conversationExists(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count != null && count > 0;
    }

    public void createConversation(String conversationId, String title, String visitorId) {
        jdbc.update(
                "INSERT INTO conversation (conversation_id, title, visitor_id) VALUES (?, ?, ?)",
                conversationId, title, visitorId);
    }

    public boolean ownedBy(String id, String visitor) {
        if (visitor == null || visitor.isBlank()) return false;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE conversation_id=? AND visitor_id=?", Integer.class, id, visitor);
        return count != null && count > 0;
    }

    public record Summary(String conversationId, String title, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.time.OffsetDateTime createdAt,
                          @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.time.OffsetDateTime lastActiveAt) {}

    public List<Summary> list(String visitor, String search, int limit, int offset) {
        if (visitor == null || visitor.isBlank()) return List.of();
        return jdbc.query("""
                SELECT c.conversation_id,c.title,c.created_at,c.last_active_at FROM conversation c
                WHERE c.visitor_id=? AND (?='' OR LOCATE(?,COALESCE(c.title,''))>0)
                  AND EXISTS (SELECT 1 FROM conversation_query q WHERE q.conversation_id=c.conversation_id)
                ORDER BY c.last_active_at DESC,c.conversation_id DESC LIMIT ? OFFSET ?
                """, (rs,n)->new Summary(rs.getString("conversation_id"),rs.getString("title"),
                databaseTime.offset(rs.getObject("created_at", java.time.LocalDateTime.class)),
                databaseTime.offset(rs.getObject("last_active_at", java.time.LocalDateTime.class))),
                visitor,search,search,limit,offset);
    }

    public void initializeTitle(String id, String title) {
        jdbc.update("UPDATE conversation SET title=COALESCE(title,?) WHERE conversation_id=?", title,id);
    }

    /** 访客最近活跃的会话；没有返回 null。 */
    public String findLatestByVisitorId(String visitorId) {
        List<String> ids = jdbc.query(
                "SELECT conversation_id FROM conversation WHERE visitor_id = ? ORDER BY last_active_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("conversation_id"), visitorId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 会话的全部问答，时间正序（历史回显用，不限条数）。 */
    public List<QueryRecord> allQueries(String conversationId) {
        return jdbc.query("""
                        SELECT query_no, question, answer, trace_id, created_at, turn_id, turn_status, agent_type, agent_payload_json
                        FROM conversation_query
                        WHERE conversation_id = ?
                        ORDER BY query_no ASC
                        """,
                (rs, rowNum) -> new QueryRecord(
                        rs.getInt("query_no"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("trace_id"),
                        rs.getObject("created_at", java.time.LocalDateTime.class),
                        rs.getString("turn_id"), rs.getString("turn_status"),rs.getString("agent_type"),readAgentPayload(rs.getString("agent_payload_json"))),
                conversationId);
    }

    /** 已完成轮次包含澄清、失败与普通说明；不把未完成/被接管的 RUNNING 当成页面已看到的父轮次。 */
    public String latestCompletedTurnId(String conversationId) {
        List<String> ids = jdbc.query("""
                SELECT turn_id FROM conversation_query WHERE conversation_id = ?
                  AND turn_id IS NOT NULL AND turn_status IN ('SUCCEEDED', 'NEEDS_CLARIFICATION', 'FAILED')
                ORDER BY query_no DESC LIMIT 1
                """, (rs, n) -> rs.getString("turn_id"), conversationId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void touchConversation(String conversationId, String title) {
        jdbc.update(
                "UPDATE conversation SET last_active_at = NOW(), title = COALESCE(title, ?) WHERE conversation_id = ?",
                title, conversationId);
    }

    /** 会话状态行（conversation_state）。lastQueryNo 为"最后已分配的序号"。 */
    public record ConversationStateRow(long lastQueryNo, String runningTurnId, long fencingToken) {
    }

    /**
     * 原子地尝试获取会话执行占用并推进序号：
     * 无占用或租约已过期时占用成功（running_turn_id 置为本轮 turnId、last_query_no+1、fencing_token+1）；
     * 有未过期占用时行不变。调用方比对返回行的 runningTurnId 与本次 turnId 判断是否拿到占用。
     * 单行 upsert，靠 InnoDB 行锁保证并发下只有一个请求占用成功。
     *
     * <p>首次建行用 INSERT...SELECT COALESCE(MAX(query_no),0)+1 初始化（R6）：存量老会话的
     * 历史序号直接衔接，不会再从 1 开始撞 uk_query_conv_no（beginTurn 事务回滚后无法自愈）。
     *
     * <p>赋值顺序安全（R7）：MySQL 单条 UPDATE 从左到右求值、后面的赋值能看到前面的新值，
     * 因此条件里被引用的列必须尚未被本语句修改。lease_until 排在最后赋值——前面的 IF 求值时
     * 它还是旧值；running_turn_id 排在 lease_until 之前，lease_until 的 IF 用
     * "lease_until IS NULL OR lease_until <= NOW(6)" 判空闲（releaseTurn 释放时置 NOW(6)，
     * 不再置 NULL），对运行中/已释放/租约过期/历史 NULL 租约四种行状态都只依赖旧值，路径无关。
     */
    public ConversationStateRow acquireTurn(String conversationId, String turnId, long leaseMillis) {
        jdbc.update("""
                        INSERT INTO conversation_state (conversation_id, version, last_query_no, running_turn_id, lease_until, fencing_token, updated_at)
                        SELECT ?, 0, COALESCE(MAX(query_no), 0) + 1, ?, DATE_ADD(NOW(6), INTERVAL ? MICROSECOND), 1, NOW(6)
                        FROM conversation_query WHERE conversation_id = ?
                        ON DUPLICATE KEY UPDATE
                            last_query_no   = IF(running_turn_id IS NULL OR lease_until IS NULL OR lease_until <= NOW(6), last_query_no + 1, last_query_no),
                            fencing_token   = IF(running_turn_id IS NULL OR lease_until IS NULL OR lease_until <= NOW(6), fencing_token + 1, fencing_token),
                            running_turn_id = IF(running_turn_id IS NULL OR lease_until IS NULL OR lease_until <= NOW(6), ?, running_turn_id),
                            lease_until     = IF(running_turn_id IS NULL OR lease_until IS NULL OR lease_until <= NOW(6), DATE_ADD(NOW(6), INTERVAL ? MICROSECOND), lease_until),
                            updated_at      = NOW(6)
                        """,
                conversationId, turnId, leaseMillis * 1000, conversationId, turnId, leaseMillis * 1000);
        return jdbc.queryForObject("""
                        SELECT last_query_no, running_turn_id, fencing_token
                        FROM conversation_state WHERE conversation_id = ?
                        """,
                (rs, rowNum) -> new ConversationStateRow(
                        rs.getLong("last_query_no"), rs.getString("running_turn_id"), rs.getLong("fencing_token")),
                conversationId);
    }

    /** 登记 RUNNING 轮次：question 先写，answer 与 trace_id 在提交时后补。
     *  started_at 由应用时钟（requestTime）写入，不用 NOW()：应用写入的 Timestamp 两个方向
     *  都走 JDBC 会话时区转换、往返自洽；DB 时钟写出的值在服务器时区与会话时区不一致时会错位。 */
    public void insertRunningTurn(String conversationId, long queryNo, String turnId, String parentTurnId,
                                  String question, java.time.Instant requestTime) {
        jdbc.update("""
                        INSERT INTO conversation_query (conversation_id, query_no, question, turn_id, parent_turn_id, turn_status, started_at)
                        VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
                        """,
                conversationId, queryNo, question, turnId, parentTurnId, java.sql.Timestamp.from(requestTime));
    }

    /** 普通轮次只保存交互结果，不产生或复制任务状态。 */
    public void finishTurn(String turnId, String status, String answer, String traceId) {
        jdbc.update("""
                UPDATE conversation_query SET turn_status = ?, answer = ?, trace_id = ?, finished_at = NOW(6)
                WHERE turn_id = ?
                """, status, answer, traceId, turnId);
    }

    /** 同一短事务内锁住执行占用；旧执行者不能更新任务、版本或活动指针。 */
    public boolean ownsTurnForUpdate(String conversationId, String turnId, long token) {
        List<Boolean> rows = jdbc.query("""
                SELECT running_turn_id, fencing_token FROM conversation_state
                WHERE conversation_id = ? FOR UPDATE
                """, (rs, n) -> turnId.equals(rs.getString("running_turn_id"))
                        && token == rs.getLong("fencing_token"), conversationId);
        return !rows.isEmpty() && rows.get(0);
    }

    private static final String LATEST_VERSION_JOIN = """
            SELECT v.version_json FROM conversation_task t
            JOIN conversation_task_version v ON v.task_id = t.task_id AND v.version = t.latest_version
                AND v.conversation_id = t.conversation_id
            """;

    public Task findTask(String conversationId, String taskId) {
        var rows = jdbc.query("""
                SELECT * FROM conversation_task WHERE conversation_id = ? AND task_id = ?
                """, (rs, n) -> new Task(rs.getString("task_id"), rs.getString("conversation_id"),
                rs.getString("origin_question"), rs.getLong("first_query_no"), rs.getLong("last_query_no"),
                rs.getLong("latest_version"), rs.getObject("latest_successful_version") == null
                        ? null : rs.getLong("latest_successful_version")), conversationId, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TaskVersion findActiveTaskVersion(String conversationId) {
        var rows = jdbc.query(LATEST_VERSION_JOIN + """
                JOIN conversation_state s ON s.active_task_id = t.task_id AND s.conversation_id = t.conversation_id
                WHERE s.conversation_id = ?
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public TaskVersion findLatestTaskVersion(String conversationId, String taskId) {
        var rows = jdbc.query(LATEST_VERSION_JOIN + " WHERE t.conversation_id = ? AND t.task_id = ?",
                (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<TaskVersion> recentTaskLatestVersions(String conversationId, int limit) {
        return jdbc.query(LATEST_VERSION_JOIN + " WHERE t.conversation_id = ? ORDER BY t.last_query_no DESC LIMIT ?",
                (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, limit);
    }
    public TaskVersion firstTaskLatestVersion(String conversationId) {
        var rows = jdbc.query(LATEST_VERSION_JOIN + " WHERE t.conversation_id = ? ORDER BY t.first_query_no ASC LIMIT 1",
                (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<TaskVersion> recentTaskVersions(String conversationId, String taskId, int limit) {
        return jdbc.query("""
                SELECT version_json FROM conversation_task_version WHERE conversation_id = ? AND task_id = ?
                ORDER BY version DESC LIMIT ?
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId, limit);
    }
    public TaskVersion firstTaskVersion(String conversationId, String taskId) {
        var rows = jdbc.query("""
                SELECT version_json FROM conversation_task_version WHERE conversation_id = ? AND task_id = ?
                ORDER BY version ASC LIMIT 1
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public TaskVersion taskVersion(String conversationId, String taskId, long version) {
        var rows = jdbc.query("""
                SELECT version_json FROM conversation_task_version WHERE conversation_id = ? AND task_id = ? AND version = ?
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public TaskVersion latestSuccessfulTaskVersion(String conversationId, String taskId) {
        var rows = jdbc.query("""
                SELECT v.version_json FROM conversation_task t
                JOIN conversation_task_version v ON v.task_id = t.task_id AND v.version = t.latest_successful_version
                    AND v.conversation_id = t.conversation_id
                WHERE t.conversation_id = ? AND t.task_id = ?
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public TaskVersion firstExecutableTaskVersion(String conversationId, String taskId) {
        var rows = jdbc.query("""
                SELECT version_json FROM conversation_task_version
                WHERE conversation_id = ? AND task_id = ? AND has_query_definition = TRUE ORDER BY version ASC LIMIT 1
                """, (rs, n) -> TaskVersion.fromJson(rs.getString("version_json")), conversationId, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** 必须在持有会话执行权的提交事务内调用。版本 INSERT 不允许覆盖历史。 */
    public void saveTaskVersion(TaskVersion version) {
        Task task = Task.advance(findTask(version.conversationId(), version.taskId()), version);
        jdbc.update("""
                INSERT INTO conversation_task_version
                    (task_id, conversation_id, version, base_version, source_turn_id, has_query_definition, version_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, version.taskId(), version.conversationId(), version.version(), version.baseVersion(),
                version.turnId(), version.queryDefinition() != null, version.toJson());
        jdbc.update("""
                INSERT INTO conversation_task (task_id, conversation_id, origin_question, first_query_no,
                    last_query_no, latest_version, latest_successful_version) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE last_query_no = VALUES(last_query_no), latest_version = VALUES(latest_version),
                    latest_successful_version = VALUES(latest_successful_version)
                """, task.taskId(), task.conversationId(), task.originQuestion(), task.firstQueryNo(),
                task.lastQueryNo(), task.latestVersion(), task.latestSuccessfulVersion());
    }
    public void finishTaskQuery(String turnId, String status, String answer, String traceId,
                                TaskVersion version, TaskScope scope) {
        jdbc.update("""
                UPDATE conversation_query SET turn_status = ?, answer = ?, trace_id = ?,
                    task_id = ?, task_version = ?, task_action = ?, finished_at = NOW(6) WHERE turn_id = ?
                """, status, answer, traceId, version.taskId(), version.version(), scope.action().name(), turnId);
    }
    public void activateTask(String conversationId, TaskVersion version) {
        jdbc.update("UPDATE conversation_state SET active_task_id = ? WHERE conversation_id = ?",
                version.taskId(), conversationId);
    }

    /**
     * 释放执行占用并推进版本。fencing token 校验：不匹配说明租约已过期被新请求接管，
     * 旧执行者不得覆盖新状态，返回 false。
     *
     * <p>释放后 lease_until 置 NOW(6) 而非 NULL（R7）：空闲行的判定统一为"租约已过期"，
     * acquireTurn 的 IF 条件不再依赖会被同语句改写的 running_turn_id。
     */
    public boolean releaseTurn(String conversationId, String turnId, long fencingToken) {
        return jdbc.update("""
                        UPDATE conversation_state
                        SET running_turn_id = NULL, lease_until = NOW(6), version = version + 1, updated_at = NOW(6)
                        WHERE conversation_id = ? AND running_turn_id = ? AND fencing_token = ?
                        """,
                conversationId, turnId, fencingToken) > 0;
    }

    private com.fasterxml.jackson.databind.JsonNode readAgentPayload(String raw) {
        if(raw==null) return null;
        try {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var node=mapper.readTree(raw);
            return node.isTextual()?mapper.readTree(node.textValue()):node;
        }
        catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid saved agent payload",invalid); }
    }

    public List<QueryRecord> recentQueries(String conversationId, int limit) {
        // 只把明确标记为成功的轮次注入上下文：
        // RUNNING 行（含当前这轮）与 FAILED 行不进 prompt，保持与"成功后追加聊天记录"的旧口径一致。
        // 发生时间优先取应用时钟写入的 started_at；轮次功能上线前的旧行回退 created_at（DB 时钟，可能带时区偏移）。
        List<QueryRecord> desc = jdbc.query("""
                        SELECT query_no, question, answer, trace_id, COALESCE(started_at, created_at) AS occurred_at
                        FROM conversation_query
                        WHERE conversation_id = ?
                          AND turn_status = 'SUCCEEDED'
                        ORDER BY query_no DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new QueryRecord(
                        rs.getInt("query_no"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("occurred_at").toLocalDateTime()),
                conversationId, limit);
        // 翻转为时间正序，让最早的在前
        java.util.Collections.reverse(desc);
        return desc;
    }
}
