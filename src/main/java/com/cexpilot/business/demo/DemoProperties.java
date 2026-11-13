package com.cexpilot.business.demo;

import java.util.Set;

public class DemoProperties {
    private boolean enabled;
    private Set<String> visitors = Set.of();
    private long delaySeconds = 15;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getVisitors() { return visitors; }
    public void setVisitors(Set<String> visitors) { this.visitors = Set.copyOf(visitors); }
    public long getDelaySeconds() { return delaySeconds; }
    public void setDelaySeconds(long delaySeconds) {
        if (delaySeconds < 1 || delaySeconds > 3600)
            throw new IllegalArgumentException("demo 延迟须为 1~3600 秒");
        this.delaySeconds = delaySeconds;
    }
    public boolean allows(String visitor) { return visitor != null && visitors.contains(visitor); }
    public void check(String visitor) {
        if (!allows(visitor)) throw new SecurityException("Demo 未对当前访客开放");
    }
}
