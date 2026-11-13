package com.cexpilot.platform.context;

import com.cexpilot.platform.agent.AgentInput;
import com.cexpilot.platform.execution.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneOffset;

/** 无业务依赖的默认视图；应用可以替换，但不必构造任何查询或监控对象。 */
public final class BasicAgentContextProvider implements AgentContextProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override public ObjectNode routerView(String history, JsonNode state, RequestContext request) {
        var result = state != null && state.isObject() ? ((ObjectNode) state).deepCopy() : JSON.createObjectNode();
        result.put("recent_dialogue", history == null ? "" : history)
                .put("request_time", request.requestTime().toString())
                .put("timezone", (request.userZone() == null ? ZoneOffset.UTC : request.userZone()).getId());
        return result;
    }
    @Override public ObjectNode executionContext(String history, AgentInput input, JsonNode state, RequestContext request) {
        return routerView(history, state, request);
    }
}
