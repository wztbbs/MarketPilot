package com.cexpilot.business.cex.query;

import com.cexpilot.platform.engine.dag.PlanValidator;

import com.cexpilot.platform.tool.calculation.MinTool;
import com.cexpilot.platform.tool.calculation.SumTool;
import com.cexpilot.platform.tool.calculation.RelativeChangeTool;
import com.cexpilot.platform.tool.calculation.DifferenceTool;
import com.cexpilot.platform.tool.calculation.AvgTool;
import com.cexpilot.platform.tool.calculation.MaxTool;
import com.cexpilot.platform.tool.calculation.CompareTool;
import com.cexpilot.platform.tool.calculation.RatioTool;
import com.cexpilot.platform.tool.calculation.AnnualizeTool;
import com.cexpilot.platform.llm.LlmConfig;
import com.cexpilot.platform.engine.dag.DagConfig;
import com.cexpilot.business.cex.metric.OrderBookMetricProvider;
import com.cexpilot.business.cex.metric.TakerMetricProvider;
import com.cexpilot.business.cex.metric.MetricQuery;
import com.cexpilot.business.cex.metric.FundingMetric;
import com.cexpilot.business.cex.metric.OiMetricProvider;
import com.cexpilot.business.cex.metric.MetricResult;
import com.cexpilot.business.cex.metric.MetricProviderRegistry;
import com.cexpilot.business.cex.metric.MetricSelector;
import com.cexpilot.business.cex.metric.MarkPriceSnapshot;
import com.cexpilot.business.cex.metric.FundingMetricProvider;
import com.cexpilot.business.cex.metric.TakerMetric;
import com.cexpilot.business.cex.metric.TickerMetric;
import com.cexpilot.business.cex.metric.TimeRangeQuery;
import com.cexpilot.business.cex.metric.OiMetric;
import com.cexpilot.business.cex.metric.OrderBookSnapshot;
import com.cexpilot.business.cex.metric.TickerMetricProvider;
import com.cexpilot.business.cex.metric.KlineMetricProvider;
import com.cexpilot.business.cex.metric.MarkPriceMetric;
import com.cexpilot.business.cex.metric.MarkPriceMetricProvider;
import com.cexpilot.business.cex.metric.KlineMetric;
import com.cexpilot.business.cex.metric.MetricProvider;
import com.cexpilot.business.cex.market.Exchange;
import com.cexpilot.business.cex.market.kline.KlineQueryRequest;
import com.cexpilot.business.cex.market.kline.KlineQueryResult;
import com.cexpilot.business.cex.market.kline.KlineQueryService;
import com.cexpilot.business.cex.market.model.Candle;
import com.cexpilot.business.cex.market.series.SeriesCoverage;
import com.cexpilot.business.cex.time.CandleInterval;
import com.cexpilot.business.cex.time.TimeRangeResolver;
import java.time.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import com.cexpilot.platform.llm.ChatResponse;
import com.cexpilot.platform.llm.ToolSpec;
import com.cexpilot.platform.llm.LlmClient;
import com.cexpilot.platform.llm.ChatMessage;
import com.cexpilot.platform.prompt.PromptStore;
import com.cexpilot.platform.tool.ToolRegistry;
import com.cexpilot.platform.tool.ToolResult;
import com.cexpilot.platform.tool.AgentTool;
import com.cexpilot.platform.execution.RequestContext;
import com.cexpilot.platform.tool.ToolContext;
import com.cexpilot.platform.tool.ToolDefinitionLoader;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.*;
import java.util.function.BiFunction;

/** 本地假取数与真实 YAML/算子；不访问网络。 */
public final class MetricTestSupport {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    public static final String DAY = "{\"type\":\"calendar_period\",\"unit\":\"day\",\"offset\":-1,\"segment\":\"full\",\"extent\":\"full_period\"}";
    public static JsonNode json(String value) {
        try { return JSON.readTree(value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static ObjectNode metric(String id, String name, String shape, String... exchanges) {
        ObjectNode metric = JSON.createObjectNode().put("id", id).put("metric", name).put("query_shape", shape);
        var ex = metric.putArray("exchanges");
        for (String exchange : exchanges) ex.add(exchange);
        metric.putObject("instrument").put("market_type", "perpetual").put("base", "BTC").put("quote", "USDT");
        metric.set("time", json(DAY));
        metric.put("interval", "1h");
        return metric;
    }
    public static ObjectNode plan(ObjectNode... groups) {
        ObjectNode plan = JSON.createObjectNode();
        var metrics = plan.putArray("metrics");
        for (var group : groups) metrics.add(group);
        plan.putArray("calculations");
        return plan;
    }
    public static ObjectNode scalarPlan() { return plan(metric("m1", "trade.turnover", "range_statistic", "binance")); }
    public static void calculation(ObjectNode plan, String id, String op, String input) {
        ((ArrayNode) plan.get("calculations")).addObject().put("id", id).put("operator", op).set("input", json(input));
    }
    public static String envelope(JsonNode plan) {
        return JSON.createObjectNode().put("in_domain", true).putNull("reply").set("plan", plan).toString();
    }
    public static AgentTool source(String name, BiFunction<JsonNode, ToolContext, ToolResult> action) {
        return new AgentTool() {
            public String name() { return name; }
            public ToolResult execute(JsonNode args, ToolContext context) { return action.apply(args, context); }
        };
    }
    public static ToolRegistry registry(AgentTool... overrides) {
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        for (AgentTool tool : List.of(new AvgTool(), new SumTool(), new MinTool(), new MaxTool(), new CompareTool(),
                new DifferenceTool(), new RatioTool(), new RelativeChangeTool(), new AnnualizeTool())) tools.put(tool.name(), tool);
        for (AgentTool tool : overrides) tools.put(tool.name(), tool);
        var definitions = ToolDefinitionLoader.load(LOADER).stream().filter(d -> tools.containsKey(d.name())).toList();
        return new ToolRegistry(new ArrayList<>(tools.values()), definitions);
    }
    public static MetricProvider provider(BiFunction<MetricQuery, RequestContext, MetricResult> action) {
        return namedProvider(KlineMetricProvider.NAME, KlineMetric::valueOf, action);
    }
    public static MetricProvider namedProvider(String name, java.util.function.Function<String, MetricSelector> selector,
                                        BiFunction<MetricQuery, RequestContext, MetricResult> action) {
        return new MetricProvider() {
            public String name() { return name; }
            public MetricSelector selector(String selectorName) { return selector.apply(selectorName); }
            public MetricResult query(MetricQuery query, RequestContext context) { return action.apply(query, context); }
        };
    }
    public static MetricProviderRegistry providers(MetricProvider provider) { return new MetricProviderRegistry(List.of(provider)); }
    public static MetricProviderRegistry providers() { return providers(provider((q,c) -> metricResult(q,c,24,true))); }
    /** 覆盖目录内全部 provider 的编译期桩：selector 可解析，query 不应被编译器调用。 */
    public static MetricProviderRegistry catalogProviders() {
        BiFunction<MetricQuery, RequestContext, MetricResult> unused = (q,c) -> {
            throw new UnsupportedOperationException("编译期桩不取数: " + q.binding().metric());
        };
        return new MetricProviderRegistry(List.of(
                provider((q,c) -> metricResult(q,c,24,true)),
                namedProvider(OiMetricProvider.NAME, OiMetric::valueOf, unused),
                namedProvider(MarkPriceMetricProvider.NAME, name -> {
                    try {
                        return MarkPriceMetric.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        return MarkPriceSnapshot.valueOf(name);
                    }
                }, unused),
                namedProvider(TakerMetricProvider.NAME, TakerMetric::valueOf, unused),
                namedProvider(TickerMetricProvider.NAME, TickerMetric::valueOf, unused),
                namedProvider(OrderBookMetricProvider.NAME, OrderBookSnapshot::valueOf, unused),
                namedProvider(FundingMetricProvider.NAME, FundingMetric::valueOf, unused)));
    }
    public static MetricResult metricResult(MetricQuery query, RequestContext context, int count, boolean complete) {
        KlineQueryService service = mock(KlineQueryService.class);
        when(service.query(any(), any(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(klineResult(query, context, count, complete));
        return new KlineMetricProvider(service).query(query,context);
    }
    public static KlineQueryResult klineResult(MetricQuery query, RequestContext context, int count, boolean complete) {
        var timeQuery = (TimeRangeQuery) query;
        var range = new TimeRangeResolver(Clock.fixed(context.requestTime(),context.userZone()))
                .resolve(context.userZone(),timeQuery.time(),context.requestTime());
        var interval = timeQuery.intervalCode() == null ? CandleInterval.parse("1h") : CandleInterval.parse(timeQuery.intervalCode());
        List<Candle> candles = new ArrayList<>();
        for (int i=0;i<count;i++) candles.add(new Candle(range.startInclusive().toEpochMilli()+i*interval.duration().toMillis(),
                BigDecimal.valueOf(100+i),BigDecimal.valueOf(102+i),BigDecimal.valueOf(99+i),BigDecimal.valueOf(101+i),
                BigDecimal.TEN,BigDecimal.valueOf(i==0 ? (timeQuery.exchange()==Exchange.BINANCE ? 120 : 100) : 0),true));
        var request = new KlineQueryRequest(timeQuery.exchange(),timeQuery.base(),interval,range,timeQuery.includeUnclosed());
        var coverage = new SeriesCoverage(count,count,complete ? List.of() : List.of(range.startInclusive().toEpochMilli()),
                List.of(),List.of(),false,false,null,range.endExclusive().toEpochMilli(),false);
        return new KlineQueryResult(request,request,candles,coverage);
    }
    public static DagPlanner planner(LlmClient llm, ToolRegistry registry, DagConfig config) {
        return new DagPlanner(llm, registry, new LlmConfig(), config,
                new PromptStore(LOADER), new PlanValidator(registry, config), catalogProviders());
    }
    public static class Script implements LlmClient {
        final Queue<String> outputs = new ArrayDeque<>();
        final List<List<ChatMessage>> calls = new ArrayList<>();
        JsonNode format;
        Script(String... outputs) { this.outputs.addAll(List.of(outputs)); }
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            if (tools != null) throw new AssertionError("Planner/Answer 不应携带 API tools");
            calls.add(List.copyOf(messages));
            return new ChatResponse(outputs.isEmpty() ? "not json" : outputs.remove(), List.of(), 10, 5);
        }
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools, JsonNode format) {
            this.format = format;
            return chat(messages, tools);
        }
    }
}
