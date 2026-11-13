package com.cexpilot.business.cex.conversation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 生命周期测试存储桩：保存真实序列化快照，不模拟 MySQL 的事务/锁语义。 */
public class InMemoryTaskRepository extends ConversationRepository {
    public final Map<String, TaskVersion> tasks = new LinkedHashMap<>();
    public final Map<String, TaskVersion> snapshots = new LinkedHashMap<>();
    public final Map<String, TaskScope> scopes = new LinkedHashMap<>();
    private final Map<String, QueryRecord> questions = new LinkedHashMap<>();
    public final Map<String, Task> headers = new LinkedHashMap<>();
    private String active;
    private String running;
    private long queryNo;
    private long token;
    public boolean acceptCommit = true;

    public InMemoryTaskRepository() { super(null); }
    @Override public ConversationStateRow acquireTurn(String conversationId, String turnId, long lease) {
        if (running == null) { running = turnId; ++queryNo; ++token; }
        return new ConversationStateRow(queryNo, running, token);
    }
    @Override public void insertRunningTurn(String conversationId, long no, String turnId, String parent,
            String question, Instant time) {
        questions.put(turnId, new QueryRecord((int)no, question, null, null, LocalDateTime.ofInstant(time, ZoneOffset.UTC), turnId, "RUNNING"));
    }
    @Override public String latestCompletedTurnId(String c) {
        return questions.values().stream().filter(q -> q.turnId() != null && !"RUNNING".equals(q.turnStatus()))
                .max(Comparator.comparingInt(QueryRecord::queryNo)).map(QueryRecord::turnId).orElse(null);
    }
    @Override public boolean turnCompleted(String turnId) {
        var row=questions.get(turnId);return row!=null && row.turnStatus()!=null && !Set.of("RUNNING","WAITING").contains(row.turnStatus());
    }
    @Override public boolean ownsTurnForUpdate(String conversationId, String turnId, long fencing) {
        return acceptCommit && turnId.equals(running) && fencing == token;
    }
    @Override public boolean releaseTurn(String c, String t, long f) {
        if (t.equals(running) && f == token) { running = null; return true; }
        return false;
    }
    @Override public void touchConversation(String c, String title) {}
    @Override public TaskVersion findActiveTaskVersion(String c) { return active == null ? null : tasks.get(active); }
    @Override public TaskVersion findLatestTaskVersion(String c, String id) { return tasks.get(id); }
    @Override public Task findTask(String c, String id) { return headers.get(id); }
    @Override public TaskVersion latestSuccessfulTaskVersion(String c, String id) {
        var task = headers.get(id);
        return task == null || task.latestSuccessfulVersion() == null ? null : taskVersion(c, id, task.latestSuccessfulVersion());
    }
    @Override public void saveTaskVersion(TaskVersion state) {
        if (snapshots.values().stream().anyMatch(v -> v.taskId().equals(state.taskId()) && v.version() == state.version()))
            throw new IllegalStateException("重复版本");
        tasks.put(state.taskId(), TaskVersion.fromJson(state.toJson()));
        headers.put(state.taskId(), Task.advance(headers.get(state.taskId()), state));
    }
    @Override public void activateTask(String c, TaskVersion state) { active = state.taskId(); }
    @Override public void finishTaskQuery(String t, String status, String answer, String trace,
            TaskVersion state, TaskScope scope) {
        snapshots.put(t, TaskVersion.fromJson(state.toJson()));
        scopes.put(t, scope);
        var q = questions.get(t);
        if (q != null) questions.put(t, new QueryRecord(q.queryNo(), q.question(), answer, trace, q.createdAt(), t, status));
    }
    @Override public void finishTurn(String t, String status, String answer, String trace) {
        var q = questions.get(t);
        if (q != null) questions.put(t, new QueryRecord(q.queryNo(), q.question(), answer, trace, q.createdAt(), t, status));
    }
    @Override public List<TaskVersion> recentTaskLatestVersions(String c, int limit) {
        return tasks.values().stream().sorted(Comparator.comparingLong(TaskVersion::lastQueryNo).reversed()).limit(limit).toList();
    }
    @Override public TaskVersion firstTaskLatestVersion(String c) {
        return tasks.values().stream().min(Comparator.comparingLong(TaskVersion::firstQueryNo)).orElse(null);
    }
    @Override public List<TaskVersion> recentTaskVersions(String c, String id, int limit) {
        return snapshots.values().stream().filter(s -> s.taskId().equals(id))
                .sorted(Comparator.comparingLong(TaskVersion::version).reversed()).limit(limit).toList();
    }
    @Override public TaskVersion firstTaskVersion(String c, String id) {
        return snapshots.values().stream().filter(s -> s.taskId().equals(id))
                .min(Comparator.comparingLong(TaskVersion::version)).orElse(null);
    }
    @Override public TaskVersion taskVersion(String c, String id, long version) {
        return snapshots.values().stream().filter(s -> s.taskId().equals(id) && s.version() == version).findFirst().orElse(null);
    }
    @Override public TaskVersion firstExecutableTaskVersion(String c, String id) {
        return snapshots.values().stream().filter(s -> s.taskId().equals(id) && s.queryDefinition() != null)
                .min(Comparator.comparingLong(TaskVersion::version)).orElse(null);
    }
    @Override public List<QueryRecord> recentQueries(String c, int limit) {
        return questions.entrySet().stream().filter(e -> snapshots.containsKey(e.getKey())
                && "SUCCEEDED".equals(snapshots.get(e.getKey()).status()))
                .map(Map.Entry::getValue).sorted(Comparator.comparingInt(QueryRecord::queryNo).reversed())
                .limit(limit).sorted(Comparator.comparingInt(QueryRecord::queryNo)).toList();
    }
}
