package com.cexpilot.platform.context;

import com.cexpilot.platform.agent.*;
import com.cexpilot.platform.execution.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 应用的上下文投影和私有输入装配端口。底座不读取业务命名空间内部结构。 */
public interface AgentContextProvider {
    ObjectNode routerView(String history, JsonNode state, RequestContext request);
    ObjectNode executionContext(String history, AgentInput input, JsonNode state, RequestContext request);
    default AgentRunContext.PrivateInput inputFor(ConversationAgent agent, AgentInput input, String resolvedQuestion) {
        return input instanceof AgentRunContext.PrivateInput privateInput ? privateInput : null;
    }
}
