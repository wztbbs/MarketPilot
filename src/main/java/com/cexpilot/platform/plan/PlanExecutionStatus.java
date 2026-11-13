package com.cexpilot.platform.plan;

public enum PlanExecutionStatus {
    NOT_STARTED, RUNNING, WAITING, SUCCEEDED, FAILED, PARTIAL;
    public boolean isTerminal() { return this == SUCCEEDED || this == FAILED || this == PARTIAL; }
}
