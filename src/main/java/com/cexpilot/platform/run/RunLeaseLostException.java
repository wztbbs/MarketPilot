package com.cexpilot.platform.run;

/** 本执行者已失去运行所有权，必须停止；不得转换成模型修复或节点失败继续执行。 */
public final class RunLeaseLostException extends IllegalStateException {
    public RunLeaseLostException(String message) { super(message); }
}
