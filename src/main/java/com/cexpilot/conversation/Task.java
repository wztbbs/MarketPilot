package com.cexpilot.conversation;

/** 稳定任务身份与版本入口。完整状态只保存在 TaskVersion 中。 */
public record Task(String taskId, String conversationId, String originQuestion, long firstQueryNo,
                   long lastQueryNo, long latestVersion, Long latestSuccessfulVersion) {
    public static Task advance(Task previous, TaskVersion next) {
        if (next.version() != (previous == null ? 1 : previous.latestVersion() + 1))
            throw new IllegalArgumentException("任务版本必须在最新版本之后追加");
        if (previous != null && (!previous.taskId().equals(next.taskId())
                || !previous.conversationId().equals(next.conversationId())
                || !previous.originQuestion().equals(next.originQuestion())
                || previous.firstQueryNo() != next.firstQueryNo()))
            throw new IllegalArgumentException("任务稳定身份不能在追加版本时改变");
        Long successful = next.executionSummary() != null
                && next.executionSummary().sourceVersion() == next.version()
                && "SUCCEEDED".equals(next.executionSummary().status()) ? Long.valueOf(next.version())
                : previous == null ? null : previous.latestSuccessfulVersion();
        return new Task(next.taskId(), next.conversationId(), next.originQuestion(), next.firstQueryNo(),
                next.lastQueryNo(), next.version(), successful);
    }
}
