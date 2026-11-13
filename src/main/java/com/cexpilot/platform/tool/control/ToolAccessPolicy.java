package com.cexpilot.platform.tool.control;

import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.JsonNode;

/** 业务授权及资源版本检查扩展点；首次提案、确认、实际执行均检查。拒绝通过异常表达，确认不能覆盖拒绝。 */
public interface ToolAccessPolicy {
    void check(ToolDefinition definition, JsonNode frozenArguments, ToolContext trustedContext);
    /** 准备阶段可先检查绑定；依赖上游结果的参数权限，解析后仍须完整 check。 */
    default void checkBinding(ToolDefinition definition, ToolContext trustedContext) {}
}
