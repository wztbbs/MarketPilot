package com.cexpilot.platform.agent;

import com.cexpilot.platform.execution.config.QueryExecutionConfig;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** 启动时读取受版本管理的 agents/*.yml；不热更新，不接受请求中提交配置。 */
public final class AgentDefinitionLoader {
    private AgentDefinitionLoader(){}

    /**
     * 加载并校验 classpath 下所有 agents/*.yml，按资源路径排序保证确定性。
     * 每个文件必须含 id/route/description/enabled/engine/prompt/allowed_tools/execution，
     * engine 仅支持 react（由底座 ReactEngine 执行），预算超上限（模型 32 次/工具 64 次/30 分钟）直接拒绝。
     *
     * @param loader Spring 资源加载器，用于扫描 classpath*:agents/*.yml
     * @return 按资源排序的声明式 Agent 定义列表（含 disabled 项）；任何配置错误抛 IllegalStateException 阻止启动
     */
    public static List<ConfiguredAgentDefinition> load(ResourceLoader loader) {
        return load(loader,"classpath*:agents/*.yml");
    }
    public static List<ConfiguredAgentDefinition> load(ResourceLoader loader, String pattern) {
        try {
            var resources=ResourcePatternUtils.getResourcePatternResolver(loader).getResources(pattern);
            Arrays.sort(resources,Comparator.comparing(org.springframework.core.io.Resource::getDescription));
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);var yaml=new Yaml(new SafeConstructor(options));
            var result=new ArrayList<ConfiguredAgentDefinition>();
            for(var resource:resources)try(var input=resource.getInputStream()) {
                if(!(yaml.load(input) instanceof Map<?,?> doc))throw new IllegalArgumentException("Agent YAML 必须为对象");
                keys(doc,Set.of("id","route","description","enabled","engine","prompt","allowed_tools","execution","tool_approval"));
                if(!(doc.get("enabled") instanceof Boolean enabled) || !(doc.get("execution") instanceof Map<?,?> execution)
                        || !(doc.get("allowed_tools") instanceof List<?> allowed))throw new IllegalArgumentException("缺少 Agent 字段");
                if(!"react".equals(doc.get("engine")) && !"plan".equals(doc.get("engine")))
                    throw new IllegalArgumentException("仅支持 react/plan 引擎");
                keys(execution,Set.of("max_steps","max_model_calls","max_tool_calls","timeout_ms","max_observed_tokens"));
                var tools=new LinkedHashSet<String>();
                for(Object tool:allowed)if(!(tool instanceof String name) || !tools.add(name))throw new IllegalArgumentException("allowed_tools 必须为无重复的工具列表");
                var limits=new QueryExecutionConfig();limits.setMaxModelCalls(number(execution,"max_model_calls",8));
                limits.setMaxToolCalls(number(execution,"max_tool_calls",8));limits.setTimeoutMs(number(execution,"timeout_ms",180000));
                limits.setMaxObservedTokens(number(execution,"max_observed_tokens",48000));
                if(limits.getMaxModelCalls()>32 || limits.getMaxToolCalls()>64 || limits.getTimeoutMs()>1800000)throw new IllegalArgumentException("Agent 预算过大");
                var engine="react".equals(doc.get("engine"))?EngineKind.REACT:EngineKind.PLAN;
                result.add(new ConfiguredAgentDefinition(new AgentDefinition(text(doc,"id"),text(doc,"route"),text(doc,"description")),enabled,
                        engine,text(doc,"prompt"),tools,number(execution,"max_steps",6),limits,approvalMode(doc)));
            } catch(RuntimeException error){throw new IllegalStateException("Agent 配置错误: "+resource.getDescription(),error);}
            return List.copyOf(result);
        } catch(java.io.IOException error){throw new IllegalStateException("加载 Agent 配置失败",error);}
    }
    /** 白名单校验 map 的键；出现未知键即抛 IllegalArgumentException。 */
    private static void keys(Map<?,?> map,Set<String> allowed){for(Object key:map.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("未知 Agent 配置字段: "+key);}
    /** 取非空字符串字段；缺失或空白抛 IllegalArgumentException。 */
    private static String text(Map<?,?> map,String key){if(map.get(key) instanceof String text && !text.isBlank())return text;throw new IllegalArgumentException("缺少 "+key);}
    /** 取整数字段，缺省时返回 fallback；类型不是整数抛 IllegalArgumentException。 */
    private static int number(Map<?,?> map,String key,int fallback){if(!map.containsKey(key))return fallback;if(map.get(key) instanceof Integer number)return number;throw new IllegalArgumentException(key+" 必须为整数");}
    private static com.cexpilot.platform.plan.ToolApprovalMode approvalMode(Map<?,?> doc) {
        if (!doc.containsKey("tool_approval")) return com.cexpilot.platform.plan.ToolApprovalMode.PER_CALL;
        return switch (text(doc,"tool_approval")) {
            case "per_call" -> com.cexpilot.platform.plan.ToolApprovalMode.PER_CALL;
            case "prepared_plan" -> com.cexpilot.platform.plan.ToolApprovalMode.PREPARED_PLAN;
            default -> throw new IllegalArgumentException("tool_approval 仅支持 per_call/prepared_plan");
        };
    }
}
