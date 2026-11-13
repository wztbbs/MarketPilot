package com.cexpilot.platform.engine.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 类型化业务节点端口；业务提供自己的不可变请求，底座只使用调度和输出契约。 */
public interface NodeOperation {
    String executionName();
    JsonNode outputSchema();
    void describe(ObjectNode node);
    void validate(PlanNode node, List<String> errors);
}
