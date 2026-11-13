package com.cexpilot.platform.execution.config;

import com.cexpilot.platform.prompt.PromptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 按服务端访客标识稳定分组；不改变后台 Monitor，也不重置请求预算。 */
@Configuration
@ConfigurationProperties(prefix = "harness.rollout")
public class HarnessRolloutConfig {
    /** 灰度分组：CORE = 内置能力始终开放；HTTP_AGENT = 声明式 HTTP Agent 按访客灰度。 */
    public enum Group { CORE, HTTP_AGENT }
    /** adaptive Query（多轮续规划）灰度百分比，0~100。 */
    private int adaptiveQueryPercent = 100;
    /** 声明式 HTTP Agent 灰度百分比，0~100。 */
    private int httpAgentsPercent = 100;
    /** 分析转监控交接灰度百分比，0~100。 */
    private int handoffPercent = 100;
    /** 分组哈希盐，调整可整体重排灰度桶。 */
    private String cohortSalt = "harness-v1";

    public int getAdaptiveQueryPercent() { return adaptiveQueryPercent; }
    public void setAdaptiveQueryPercent(int value) { adaptiveQueryPercent = value; }
    public int getHttpAgentsPercent() { return httpAgentsPercent; }
    public void setHttpAgentsPercent(int value) { httpAgentsPercent = value; }
    public int getHandoffPercent() { return handoffPercent; }
    public void setHandoffPercent(int value) { handoffPercent = value; }
    public String getCohortSalt() { return cohortSalt; }
    public void setCohortSalt(String value) { cohortSalt = value; }

    /** 启动时校验：百分比在 0~100，cohortSalt 为合法标识。 */
    @jakarta.annotation.PostConstruct
    public void validate() {
        for (int percent : new int[]{adaptiveQueryPercent, httpAgentsPercent, handoffPercent})
            if (percent < 0 || percent > 100) throw new IllegalArgumentException("harness.rollout 百分比必须在 0～100");
        if (cohortSalt == null || !cohortSalt.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("非法 harness.rollout.cohort-salt");
    }

    /**
     * 一个访客的灰度决策快照。
     *
     * @param adaptiveQuery 是否启用 adaptive Query 续规划
     * @param httpAgents    是否开放声明式 HTTP Agent 路由
     * @param handoff       是否启用分析转监控交接
     */
    public record Decision(boolean adaptiveQuery, boolean httpAgents, boolean handoff) {
        /** @return 该分组是否对本次决策开放；CORE 始终开放 */
        public boolean allows(Group group) { return group == Group.CORE || httpAgents; }
        /** @return 决策的 JSON 表示，用于 trace 落库 */
        public ObjectNode toJson() {
            return new ObjectMapper().createObjectNode().put("adaptive_query", adaptiveQuery)
                    .put("http_agents", httpAgents).put("handoff", handoff);
        }
    }

    /**
     * 按访客标识计算本轮灰度决策（同一 visitor 结果稳定）。
     *
     * @param visitor 服务端可信访客标识；非法标识在所有百分比介于 0~100 之间时落入关闭组
     * @return 三项开关的决策快照
     */
    public Decision decide(String visitor) {
        validate();
        return new Decision(inCohort(visitor, adaptiveQueryPercent), inCohort(visitor, httpAgentsPercent),
                inCohort(visitor, handoffPercent));
    }

    /** SHA-256(salt:visitor) 前 4 字节取模 10000 分桶，判断是否落入给定百分比。 */
    private boolean inCohort(String visitor, int percent) {
        if (percent == 0) return false;
        if (percent == 100) return true;
        if (visitor == null || !visitor.matches("[A-Za-z0-9_-]{1,62}")) return false;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((cohortSalt + ":" + visitor).getBytes(StandardCharsets.UTF_8));
            long value = ((hash[0] & 255L) << 24) | ((hash[1] & 255L) << 16)
                    | ((hash[2] & 255L) << 8) | (hash[3] & 255L);
            int bucket = (int) (value % 10000);
            return bucket < percent * 100;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("缺少 SHA-256", impossible);
        }
    }

    /**
     * 复制请求配置，避免一次灰度决策污染 Spring 单例或其他访客。
     *
     * @param source   全局 Query 预算配置
     * @param decision 本轮灰度决策；adaptiveQuery 关闭时 adaptiveEnabled 强制为 false
     * @return 本轮专用的独立配置副本
     */
    public QueryExecutionConfig queryConfig(QueryExecutionConfig source, Decision decision) {
        var result = new QueryExecutionConfig();
        result.setAdaptiveEnabled(source.isAdaptiveEnabled() && decision.adaptiveQuery());
        result.setMaxContinuations(source.getMaxContinuations());
        result.setMaxModelCalls(source.getMaxModelCalls());
        result.setMaxToolCalls(source.getMaxToolCalls());
        result.setTimeoutMs(source.getTimeoutMs());
        result.setMaxObservedTokens(source.getMaxObservedTokens());
        return result;
    }

    /** @return 灰度配置指纹，参与 Harness 整体版本计算 */
    public String version() {
        return PromptStore.fingerprint(adaptiveQueryPercent + ":" + httpAgentsPercent + ":" + handoffPercent + ":" + cohortSalt);
    }
}
