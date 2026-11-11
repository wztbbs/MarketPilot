package com.cexpilot.conversation;

/** 一次规划输入内的版本候选。candidateRef 只在所属 PlanningContext 内有效。 */
public record TaskVersionCandidate(String candidateRef, TaskVersion taskVersion) {
    public TaskVersionCandidate {
        if (candidateRef == null || candidateRef.isBlank() || taskVersion == null)
            throw new IllegalArgumentException("版本候选必须有引用和完整版本");
    }
}
