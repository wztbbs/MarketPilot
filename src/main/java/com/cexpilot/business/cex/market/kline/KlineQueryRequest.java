package com.cexpilot.business.cex.market.kline;

import com.cexpilot.business.cex.market.Exchange;
import com.cexpilot.business.cex.time.CandleInterval;
import com.cexpilot.business.cex.time.TimeRange;

/**
 * 一次 K 线查询的完整要求。经过 SeriesQueryPolicy 检查后，
 * range 可能已被外扩到粒度边界（effective request）。
 *
 * @param includeUnclosed 是否允许包含尚未收盘的 K 线；false 时未收盘根被剔除并标注
 */
public record KlineQueryRequest(Exchange exchange,
                                String base,
                                CandleInterval interval,
                                TimeRange range,
                                boolean includeUnclosed,
                                com.cexpilot.business.cex.market.MarketType marketType) {
    public KlineQueryRequest(Exchange exchange, String base, CandleInterval interval, TimeRange range,
                             boolean includeUnclosed) {
        this(exchange, base, interval, range, includeUnclosed, com.cexpilot.business.cex.market.MarketType.PERPETUAL);
    }
    public KlineQueryRequest { java.util.Objects.requireNonNull(marketType); }
}
