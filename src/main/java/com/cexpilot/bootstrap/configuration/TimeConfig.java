package com.cexpilot.bootstrap.configuration;

import com.cexpilot.business.cex.time.TimeRangeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间基础设施：requestTime 统一由程序注入的 Clock 提供，LLM 不参与当前时间的确定。
 */
@Configuration
public class TimeConfig {

    /** @return UTC 系统时钟，requestTime 的唯一来源；LLM 不参与当前时间的确定 */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }

    /** 构造时间窗口解析器，基于注入的统一 Clock。 */
    @Bean
    public TimeRangeResolver timeRangeResolver(Clock clock) {
        return new TimeRangeResolver(clock);
    }
}
