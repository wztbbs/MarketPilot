package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Query 请求级预算；关闭 adaptive 可回退原单次 DAG，预算仍生效。 */
@Configuration
@ConfigurationProperties(prefix="query.execution")
public class QueryExecutionConfig {
    private boolean adaptiveEnabled=true;
    private int maxContinuations=2;
    private int maxModelCalls=8;
    private int maxToolCalls=8;
    private long timeoutMs=180000;
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
    @jakarta.annotation.PostConstruct
    public void validate(){
        if(maxContinuations<0 || maxContinuations>8 || maxModelCalls<2 || maxToolCalls<1 || timeoutMs<1 || maxObservedTokens<1)
            throw new IllegalArgumentException("非法 query.execution 预算");
    }
}
