package com.cexpilot.platform.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** 业务定义的执行游标和状态；状态格式由运行适配器的 version 约束。 */
public record RunCheckpoint(String phase, ObjectNode state) {
    public RunCheckpoint {
        if (phase == null || !phase.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}"))
            throw new IllegalArgumentException("非法检查点阶段");
        state = Objects.requireNonNull(state, "检查点状态不能为空").deepCopy();
    }
    @Override public ObjectNode state() { return state.deepCopy(); }
}
