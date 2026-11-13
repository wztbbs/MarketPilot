package com.cexpilot.platform.engine.dag;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 编译后的执行节点：类型化指标查询，或计算/旧版工具调用。
 * args 的字符串值可包含 {{nodeId.path}} 引用上游节点输出，运行期由 ReferenceResolver 解析。
 */
public record PlanNode(String id,
                       String tool,
                       JsonNode args,
                       List<String> dependsOn,
                       NodeOperation operation) {

    public PlanNode(String id, String tool, JsonNode args, List<String> dependsOn) {
        this(id, tool, args, dependsOn, null);
    }

    public PlanNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
