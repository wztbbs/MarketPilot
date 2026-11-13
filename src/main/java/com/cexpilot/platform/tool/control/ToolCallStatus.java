package com.cexpilot.platform.tool.control;

/** 一次已冻结工具调用的状态；与所属 Run 的状态分别管理。 */
public enum ToolCallStatus {
    /** 等待用户确认冻结的调用内容。 */
    CONFIRMATION_REQUIRED,
    /** 已满足确认条件，等待提交。 */
    READY,
    /** 正在提交、轮询或查单；外部调用前先持久化此状态。 */
    EXECUTING,
    /** 外部任务已受理，等待后续查询结果。 */
    WAITING,
    /** 外部是否受理或完成尚不确定，需要查单或补偿。 */
    UNKNOWN,
    /** 工具调用成功，已保存结果。 */
    SUCCEEDED,
    /** 工具调用已确定失败，已保存结果。 */
    FAILED,
    /** 用户拒绝调用。 */
    REJECTED,
    /** 系统等待已过期；不代表外部动作未发生。 */
    EXPIRED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, REJECTED, EXPIRED -> true;
            default -> false;
        };
    }
}
