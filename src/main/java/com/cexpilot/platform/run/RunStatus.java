package com.cexpilot.platform.run;

/** Agent 一次持久化运行的状态；数据库与 API 使用枚举名称。 */
public enum RunStatus {
    /** 已登记，等待执行。 */
    READY,
    /** 正在持有租约的执行片段中运行。 */
    RUNNING,
    /** 等待工具确认或执行结果，当前不占用执行线程。 */
    WAITING,
    /** 运行成功，业务结果提交可能仍需重试。 */
    SUCCEEDED,
    /** 运行失败，业务结果提交可能仍需重试。 */
    FAILED
}
