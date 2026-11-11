package com.cexpilot.agent;

import com.cexpilot.conversation.PlanningContext;
import com.cexpilot.evidence.EvidenceSession;
import com.cexpilot.metric.CompiledPlan;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.runtime.TraceSink;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Consumer;

/**
 * 一次 Agent 执行的完整入参：输入数据、基础设施与回写回调打包传递，避免 execute 签名随需求逐参膨胀。
 * 由调用方（生产为 AskService）组装；sink/requestContext/selectedAgent 必须非空，其余回调与状态可为 null。
 *
 * @param question 本轮用户原始问题；Query 的独立问题改写由 Router 另行产生，不覆盖原文
 * @param history 服务端渲染的历史问答文本，用于指代理解和回答；可为空文本或 null，不是结构化任务状态
 * @param traceId 本轮执行的 Trace 标识，关联路由、模型、工具和交接等事件
 * @param sink 非空的 Trace 事件写入器；测试中可传收集器或空操作实现
 * @param delta 答案文本输出回调；可为 null。Query 可逐段输出，其他 Agent 通常在完成后输出一次
 * @param requestContext 本轮的固定时间基准：requestTime 为全链共用的“现在”，userZone 为用户时区（可缺省）；
 *                       非空，与 LLM 请求无关，Planner、工具、监控调度都以其为准
 * @param progress 面向用户的执行进度回调，如“正在获取数据”；可为 null，与答案文本回调分开
 * @param compiledPlanSink Query 有效编译计划的回传回调，供调用方提交查询状态或记录失败现场；可为 null，
 *                         adaptive 追加计划时可多次调用，回传的是当时的累积计划，非 Query Agent 不使用
 * @param planningContext Query 结构化规划输入，包含任务/版本、活动 definition、历史事实候选等；可为 null 表示无此输入，
 *                        摘要可用于路由/交接，完整对象仅封装为 Query 私有输入
 * @param session 事实与业务操作所属的 conversationId/turnId；可为 null，
 *                但 HTTP 工具、监控草稿及分析转监控交接需要有效会话
 * @param routingState 服务端读取的路由状态，如 active_agent、monitor_context；可为 null，不是行情事实，
 *                     执行前会补充服务端最新状态
 * @param selectedAgent 非空的路由结果回调，接收 query/monitor/clarify 或扩展 route（对外 route 而非内部 Agent id）；
 *                      路由确定后、Agent 执行前调用，即使随后执行失败也已告知调用方
 * @param visitor 服务端入口取得或生成的可信访客标识，用于稳定灰度、工具身份透传和监控归属；
 *                可为 null，不能由模型参数提供；生产 AskController 会提供该标识
 */
public record AgentExecutionRequest(
        String question,
        String history,
        String traceId,
        TraceSink sink,
        Consumer<String> delta,
        RequestContext requestContext,
        Consumer<String> progress,
        Consumer<CompiledPlan> compiledPlanSink,
        PlanningContext planningContext,
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
        private Consumer<CompiledPlan> compiledPlanSink;
        private PlanningContext planningContext;
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
        public Builder compiledPlanSink(Consumer<CompiledPlan> compiledPlanSink) { this.compiledPlanSink = compiledPlanSink; return this; }
        public Builder planningContext(PlanningContext planningContext) { this.planningContext = planningContext; return this; }
        public Builder session(EvidenceSession session) { this.session = session; return this; }
        public Builder routingState(JsonNode routingState) { this.routingState = routingState; return this; }
        public Builder selectedAgent(Consumer<String> selectedAgent) { this.selectedAgent = selectedAgent; return this; }
        public Builder visitor(String visitor) { this.visitor = visitor; return this; }

        public AgentExecutionRequest build() {
            java.util.Objects.requireNonNull(sink, "sink 必须非空");
            java.util.Objects.requireNonNull(requestContext, "requestContext 必须非空");
            java.util.Objects.requireNonNull(selectedAgent, "selectedAgent 必须非空");
            return new AgentExecutionRequest(question, history, traceId, sink, delta, requestContext, progress,
                    compiledPlanSink, planningContext, session, routingState, selectedAgent, visitor);
        }
    }
}
