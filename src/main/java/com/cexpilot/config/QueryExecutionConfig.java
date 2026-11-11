package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Query 请求级预算（query.execution.*）；关闭 adaptive 可回退原单次 DAG，预算仍生效。 */
@Configuration
@ConfigurationProperties(prefix="query.execution")
public class QueryExecutionConfig {
    /** 是否启用 adaptive 多轮续规划。 */
    private boolean adaptiveEnabled=true;
    /** adaptive 模式下允许的最大续规划轮数，0~8。 */
    private int maxContinuations=2;
    /** 单请求最大模型调用次数（含路由/规划/修复/回答），≥2。 */
    private int maxModelCalls=8;
    /** 单请求最大工具节点次数，≥1。 */
    private int maxToolCalls=8;
    /** 单请求整体超时（毫秒）。 */
    private long timeoutMs=180000;
    /** 单请求可观测 token 上限（用量未知时按耗尽处理）。 */
    private int maxObservedTokens=48000;
    public boolean isAdaptiveEnabled(){return adaptiveEnabled;}
    public void setAdaptiveEnabled(boolean v){adaptiveEnabled=v;}
    public int getMaxContinuations(){return maxContinuations;}
    public void setMaxContinuations(int v){maxContinuations=v;}
    public int getMaxModelCalls(){return maxModelCalls;}
    public void setMaxModelCalls(int v){maxModelCalls=v;}
    public int getMaxToolCalls(){return maxToolCalls;}
    public void setMaxToolCalls(int v){maxToolCalls=v;}
    public long getTimeoutMs(){return timeoutMs;}
    public void setTimeoutMs(long v){timeoutMs=v;}
    public int getMaxObservedTokens(){return maxObservedTokens;}
    public void setMaxObservedTokens(int v){maxObservedTokens=v;}
    /** 启动/装载时校验预算取值范围；非法抛 IllegalArgumentException。 */
    @jakarta.annotation.PostConstruct
    public void validate(){
        if(maxContinuations<0 || maxContinuations>8 || maxModelCalls<2 || maxToolCalls<1 || timeoutMs<1 || maxObservedTokens<1)
            throw new IllegalArgumentException("非法 query.execution 预算");
    }
}
