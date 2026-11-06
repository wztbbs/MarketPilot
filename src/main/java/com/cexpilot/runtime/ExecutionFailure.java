package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;

/** 包装执行期异常并携带 {@link TurnCounters}；编排层解包后用原始异常落 trace。 */
public final class ExecutionFailure extends RuntimeException {

    public final TurnCounters counters;
    public final com.cexpilot.conversation.TaskScope taskScope;
    public final JsonNode evidence;

    public ExecutionFailure(Throwable cause, TurnCounters counters) { this(cause, counters, null); }
    public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.conversation.TaskScope taskScope) {
        this(cause, counters, taskScope, null);
    }
    public ExecutionFailure(Throwable cause, TurnCounters counters, com.cexpilot.conversation.TaskScope taskScope, JsonNode evidence) {
        super(cause.getMessage(), cause);
        this.counters = counters;
        this.taskScope = taskScope;
        this.evidence = evidence == null ? null : evidence.deepCopy();
    }
}

