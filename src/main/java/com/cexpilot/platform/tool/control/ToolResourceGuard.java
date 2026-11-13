package com.cexpilot.platform.tool.control;

import com.cexpilot.platform.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 在提交副作用前查询真实资源版本。返回键为定义中的 JSON Pointer；业务执行器仍须使用 CAS/If-Match。 */
public interface ToolResourceGuard {
    boolean supports(String toolName);
    Map<String, JsonNode> currentVersions(String toolName, JsonNode arguments, ToolContext context);
}
