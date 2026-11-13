package com.cexpilot.business.cex.metric;

import com.cexpilot.business.cex.time.TimeSpec;

/**
 * range_statistic / time_series 形态的时间参数。
 */
public record TimeQueryParams(TimeSpec time, String interval, Boolean includeUnclosed)
        implements QueryParams {
}
