package com.cexpilot.platform.llm;

/** 仅表示模型输出或模型参数校验失败，允许反馈修复；执行、存储异常必须继续上抛。 */
public final class ModelOutputException extends IllegalArgumentException {
    public ModelOutputException(String message) { super(message); }
    public ModelOutputException(String message, Throwable cause) { super(message, cause); }
}
