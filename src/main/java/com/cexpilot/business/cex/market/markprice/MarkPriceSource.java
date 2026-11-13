package com.cexpilot.business.cex.market.markprice;

import com.cexpilot.business.cex.market.Exchange;
import com.cexpilot.business.cex.market.model.Candle;
import com.cexpilot.business.cex.market.series.SeriesCapability;
import com.cexpilot.business.cex.time.CandleInterval;

import java.util.List;

/**
 * 交易所标记/指数价格 K 线数据源统一契约，约束与 KlineSource 相同：
 * 适配器负责参数映射、分页游标和归一化，不自行宣称区间完整。
 */
public interface MarkPriceSource {

    Exchange exchange();

    SeriesCapability capability();

    /**
     * 拉取 openTime 落在 [startMs, endMs) 内的价格 K 线，按 openTime 升序去重。
     * 因预算或游标异常中止时保留 abortReason。
     */
    FetchResult fetch(String base, PriceType priceType, CandleInterval interval, long startMs, long endMs);

    record FetchResult(List<Candle> candles, String abortReason) {
    }
}
