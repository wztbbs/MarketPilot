package com.cexpilot.metric;

/** 字段本轮来源、原始来源性质和继承基准；轮次/版本标识由服务端填充，userText 仅兼容旧原文标注。 */
public record ConditionSource(ConditionOrigin origin, ConditionOrigin requirementOrigin,
        String sourceTurnId, String sourceTaskId, Long sourceVersion, String userText,
        String requirementTurnId, String requirementTaskId, Long requirementVersion) {
    public ConditionSource(ConditionOrigin origin, ConditionOrigin requirementOrigin,
            String sourceTurnId, String sourceTaskId, Long sourceVersion, String userText) {
        this(origin, requirementOrigin, sourceTurnId, sourceTaskId, sourceVersion, userText, null, null, null);
    }

    public static ConditionSource of(ConditionOrigin origin) {
        return new ConditionSource(origin, origin, null, null, null, null);
    }

    public ConditionSource inherited(String turnId, String taskId, long version) {
        return new ConditionSource(ConditionOrigin.INHERITED, requirementOrigin,
                turnId, taskId, version, userText,
                requirementTurnId != null ? requirementTurnId : sourceTurnId != null ? sourceTurnId : turnId,
                requirementTaskId != null ? requirementTaskId : sourceTaskId != null ? sourceTaskId : taskId,
                requirementVersion != null ? requirementVersion : sourceVersion != null ? sourceVersion : version);
    }

    public ConditionSource identify(String turnId, String taskId, long version) {
        return sourceTurnId != null ? this : new ConditionSource(origin, requirementOrigin,
                turnId, taskId, version, userText,
                requirementTurnId != null ? requirementTurnId : turnId,
                requirementTaskId != null ? requirementTaskId : taskId,
                requirementVersion != null ? requirementVersion : version);
    }
}
