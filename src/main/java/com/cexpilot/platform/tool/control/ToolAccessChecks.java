package com.cexpilot.platform.tool.control;

import com.cexpilot.platform.tool.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 方案准备、确认和实际工具执行复用同一套 Tool 权限及资源版本规则。 */
public final class ToolAccessChecks {
    private final ObjectMapper json;
    private final List<ToolAccessPolicy> policies;
    private final List<ToolResourceGuard> guards;
    public ToolAccessChecks(ObjectMapper json, List<ToolAccessPolicy> policies, List<ToolResourceGuard> guards) {
        this.json = json; this.policies = List.copyOf(policies); this.guards = List.copyOf(guards);
    }
    private void checkIdentity(ToolDefinition definition, ToolContext context) {
        if (context == null || context.agentId() == null || !context.allowedTools().contains(definition.name())) throw new SecurityException("Agent 未绑定工具");
        if (definition.control().kind() == ToolControl.Kind.OPERATION && (context.visitorId() == null || context.visitorId().isBlank()))
            throw new SecurityException("操作缺少可信用户身份");
    }
    public void checkBinding(ToolDefinition definition, ToolContext context) {
        checkIdentity(definition, context); policies.forEach(policy -> policy.checkBinding(definition, context));
    }
    public void authorize(ToolDefinition definition, JsonNode args, ToolContext context) {
        checkIdentity(definition, context); policies.forEach(policy -> policy.check(definition, args, context));
    }
    public void checkResources(ToolDefinition definition, JsonNode args, ToolContext context) {
        var expected = resourceVersions(definition, args);
        if (expected.isEmpty()) return;
        var matching = guards.stream().filter(guard -> guard.supports(definition.name())).toList();
        if (matching.size() != 1 || !expected.equals(json.valueToTree(matching.get(0).currentVersions(definition.name(), args, context))))
            throw new SecurityException("目标版本变化或未配置资源校验器");
    }
    public ObjectNode resourceVersions(ToolDefinition definition, JsonNode args) {
        var result = json.createObjectNode();
        for (String pointer : definition.control().resourceVersions()) {
            var value = args.at(pointer);
            if (value.isMissingNode() || value.isNull() || value.isContainerNode()) throw new IllegalArgumentException("缺少目标资源版本");
            result.set(pointer, value.deepCopy());
        }
        return result;
    }
}
