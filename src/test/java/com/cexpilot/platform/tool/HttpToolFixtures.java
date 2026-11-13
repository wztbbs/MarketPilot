package com.cexpilot.platform.tool;

import com.cexpilot.platform.tool.config.HttpToolServices;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class HttpToolFixtures {
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final String SECRET="local-service-secret-for-test";
    public static final String TOOL="""
            name: reference.lookup
            enabled: true
            description: 本地模拟参考记录
            transport: http
            service_id: reference
            method: POST
            path: /lookup
            side_effect: false
            timeout_ms: 2000
            max_attempts: 1
            max_age_ms: 60000
            input_schema:
              type: object
              additionalProperties: false
              required: [symbol]
              properties:
                symbol: {type: string, enum: [BTC, ETH]}
            output_schema:
              type: object
              additionalProperties: false
              required: [symbol, value]
              properties:
                symbol: {type: string}
                value: {type: number}
            """;
    public static final String AGENT="""
            id: reference
            route: reference
            description: 查询本地模拟参考记录
            enabled: true
            engine: react
            prompt: local_reference
            allowed_tools: [reference.lookup]
            execution:
              max_steps: 4
              max_model_calls: 6
              max_tool_calls: 3
              timeout_ms: 10000
            """;
    public static PathMatchingResourcePatternResolver loader(List<String> tools,List<String> agents) {
        return new PathMatchingResourcePatternResolver() {
            @Override public Resource[] getResources(String pattern) {
                var docs=pattern.contains("tools/")?tools:pattern.contains("agents/")?agents:List.<String>of();
                return docs.stream().map(s->new ByteArrayResource(s.getBytes(StandardCharsets.UTF_8))).toArray(Resource[]::new);
            }
        };
    }
    public static HttpToolServices services(int port) {
        var services=new HttpToolServices();var service=new HttpToolServices.Service();service.setBaseUrl("http://127.0.0.1:"+port);service.setToken(SECRET);
        services.setServices(Map.of("reference",service));return services;
    }
    public static ToolDefinition definition(String yaml){return ToolDefinitionLoader.load(loader(List.of(yaml),List.of())).get(0);}
    public static ToolContext context(){return new ToolContext("trace","conversation",java.time.ZoneOffset.UTC,Instant.now(),"reference","visitor",Set.of("reference.lookup"));}
    public static JsonNode json(String value){try{return JSON.readTree(value);}catch(Exception error){throw new IllegalArgumentException(error);}}
    public static String response(String symbol) {
        var envelope=JSON.createObjectNode().put("status","OK");envelope.putObject("data").put("symbol",symbol).put("value",123);
        envelope.putObject("evidence").put("source","local-mock").put("observed_at",Instant.now().toString())
                .put("valid_until",Instant.now().plusSeconds(30).toString()).put("unit","USDT").put("definition_version","mock-v1");
        return envelope.toString();
    }
}
