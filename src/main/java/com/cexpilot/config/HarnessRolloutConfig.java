package com.cexpilot.config;

import com.cexpilot.prompt.PromptStore;
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
    public enum Group { CORE, HTTP_AGENT }
    private int adaptiveQueryPercent = 100;
    private int httpAgentsPercent = 100;
    private int handoffPercent = 100;
    private String cohortSalt = "harness-v1";

    public int getAdaptiveQueryPercent() { return adaptiveQueryPercent; }
    public void setAdaptiveQueryPercent(int value) { adaptiveQueryPercent = value; }
    public int getHttpAgentsPercent() { return httpAgentsPercent; }
    public void setHttpAgentsPercent(int value) { httpAgentsPercent = value; }
    public int getHandoffPercent() { return handoffPercent; }
    public void setHandoffPercent(int value) { handoffPercent = value; }
    public String getCohortSalt() { return cohortSalt; }
    public void setCohortSalt(String value) { cohortSalt = value; }

    @jakarta.annotation.PostConstruct
    public void validate() {
        for (int percent : new int[]{adaptiveQueryPercent, httpAgentsPercent, handoffPercent})
            if (percent < 0 || percent > 100) throw new IllegalArgumentException("harness.rollout 百分比必须在 0～100");
        if (cohortSalt == null || !cohortSalt.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("非法 harness.rollout.cohort-salt");
    }

    public record Decision(boolean adaptiveQuery, boolean httpAgents, boolean handoff) {
        public boolean allows(Group group) { return group == Group.CORE || httpAgents; }
        public ObjectNode toJson() {
            return new ObjectMapper().createObjectNode().put("adaptive_query", adaptiveQuery)
                    .put("http_agents", httpAgents).put("handoff", handoff);
        }
    }

    public Decision decide(String visitor) {
        validate();
        return new Decision(inCohort(visitor, adaptiveQueryPercent), inCohort(visitor, httpAgentsPercent),
                inCohort(visitor, handoffPercent));
    }

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

    /** 复制请求配置，避免一次灰度决策污染 Spring 单例或其他访客。 */
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

    public String version() {
        return PromptStore.fingerprint(adaptiveQueryPercent + ":" + httpAgentsPercent + ":" + handoffPercent + ":" + cohortSalt);
    }
}
