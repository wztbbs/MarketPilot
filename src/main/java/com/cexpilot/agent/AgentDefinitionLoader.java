package com.cexpilot.agent;

import com.cexpilot.config.QueryExecutionConfig;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** 启动时读取受版本管理的 agents/*.yml；不热更新，不接受请求中提交配置。 */
public final class AgentDefinitionLoader {
    private AgentDefinitionLoader(){}
    public static List<ConfiguredAgentDefinition> load(ResourceLoader loader) {
        try {
            var resources=ResourcePatternUtils.getResourcePatternResolver(loader).getResources("classpath*:agents/*.yml");
            Arrays.sort(resources,Comparator.comparing(org.springframework.core.io.Resource::getDescription));
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);var yaml=new Yaml(new SafeConstructor(options));
            var result=new ArrayList<ConfiguredAgentDefinition>();
            for(var resource:resources)try(var input=resource.getInputStream()) {
                if(!(yaml.load(input) instanceof Map<?,?> doc))throw new IllegalArgumentException("Agent YAML 必须为对象");
                keys(doc,Set.of("id","route","description","enabled","prompt","allowed_tools","execution"));
                if(!(doc.get("enabled") instanceof Boolean enabled) || !(doc.get("execution") instanceof Map<?,?> execution)
                        || !(doc.get("allowed_tools") instanceof List<?> allowed))throw new IllegalArgumentException("缺少 Agent 字段");
                keys(execution,Set.of("mode","max_steps","max_model_calls","max_tool_calls","timeout_ms","max_observed_tokens"));
                if(!"http_tool_loop".equals(execution.get("mode")))throw new IllegalArgumentException("仅支持 http_tool_loop 执行模式");
                var tools=new LinkedHashSet<String>();
                for(Object tool:allowed)if(!(tool instanceof String name) || !tools.add(name))throw new IllegalArgumentException("allowed_tools 必须为无重复的工具列表");
                var limits=new QueryExecutionConfig();limits.setMaxModelCalls(number(execution,"max_model_calls",8));
                limits.setMaxToolCalls(number(execution,"max_tool_calls",8));limits.setTimeoutMs(number(execution,"timeout_ms",180000));
                limits.setMaxObservedTokens(number(execution,"max_observed_tokens",48000));
                if(limits.getMaxModelCalls()>32 || limits.getMaxToolCalls()>64 || limits.getTimeoutMs()>1800000)throw new IllegalArgumentException("Agent 预算过大");
                result.add(new ConfiguredAgentDefinition(new AgentDefinition(text(doc,"id"),text(doc,"route"),text(doc,"description")),enabled,
                        text(doc,"prompt"),tools,number(execution,"max_steps",6),limits));
            } catch(RuntimeException error){throw new IllegalStateException("Agent 配置错误: "+resource.getDescription(),error);}
            return List.copyOf(result);
        } catch(java.io.IOException error){throw new IllegalStateException("加载 Agent 配置失败",error);}
    }
    private static void keys(Map<?,?> map,Set<String> allowed){for(Object key:map.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("未知 Agent 配置字段: "+key);}
    private static String text(Map<?,?> map,String key){if(map.get(key) instanceof String text && !text.isBlank())return text;throw new IllegalArgumentException("缺少 "+key);}
    private static int number(Map<?,?> map,String key,int fallback){if(!map.containsKey(key))return fallback;if(map.get(key) instanceof Integer number)return number;throw new IllegalArgumentException(key+" 必须为整数");}
}
