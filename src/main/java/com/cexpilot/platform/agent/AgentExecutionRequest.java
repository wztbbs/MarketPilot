package com.cexpilot.platform.agent;

import com.cexpilot.platform.evidence.EvidenceSession;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.observability.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * 一次 Agent 执行的公共请求。调用方提供可信身份、时间、会话、流式回调和应用输入。
 * input 的具体类型由应用定义；底座只交给上下文适配器和目标 Agent，不解析业务内容。
 * sink/requestContext/selectedAgent 非空；其他字段可为空。
 * selectedAgent 接收实际路由标识，visitor 是服务端提供的身份标识，不来自模型参数。
 */
public record AgentExecutionRequest(
        String question,
        String history,
        String traceId,
        TraceSink sink,
        Consumer<String> delta,
        RequestContext requestContext,
        Consumer<String> progress,
        AgentInput input,
        EvidenceSession session,
        JsonNode routingState,
        Consumer<String> selectedAgent,
        String visitor) {

    /**
     * 以必需项起建：selectedAgent 缺省为空操作，其余可选项缺省为 null，按字段名链式补齐。
     * 必填校验只在 build 时进行，便于测试只关心个别字段。
     */
    public static Builder builder(String question, String traceId, TraceSink sink) {
        return new Builder(question, traceId, sink);
    }

    public static final class Builder {
        private final String question;
        private final String traceId;
        private final TraceSink sink;
        private String history;
        private Consumer<String> delta;
        private RequestContext requestContext;
        private Consumer<String> progress;
        private AgentInput input;
        private EvidenceSession session;
        private JsonNode routingState;
        private Consumer<String> selectedAgent = route -> {};
        private String visitor;

        private Builder(String question, String traceId, TraceSink sink) {
            this.question = question;
            this.traceId = traceId;
            this.sink = sink;
        }

        public Builder history(String history) { this.history = history; return this; }
        public Builder delta(Consumer<String> delta) { this.delta = delta; return this; }
        public Builder requestContext(RequestContext requestContext) { this.requestContext = requestContext; return this; }
        public Builder progress(Consumer<String> progress) { this.progress = progress; return this; }
        public Builder input(AgentInput input) { this.input = input; return this; }
        public Builder session(EvidenceSession session) { this.session = session; return this; }
        public Builder routingState(JsonNode routingState) { this.routingState = routingState; return this; }
        public Builder selectedAgent(Consumer<String> selectedAgent) { this.selectedAgent = selectedAgent; return this; }
        public Builder visitor(String visitor) { this.visitor = visitor; return this; }

        public AgentExecutionRequest build() {
            java.util.Objects.requireNonNull(sink, "sink 必须非空");
            java.util.Objects.requireNonNull(requestContext, "requestContext 必须非空");
            java.util.Objects.requireNonNull(selectedAgent, "selectedAgent 必须非空");
            return new AgentExecutionRequest(question, history, traceId, sink, delta, requestContext, progress,
                    input, session, routingState, selectedAgent, visitor);
        }
    }
}
