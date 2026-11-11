package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 启动时加载 classpath:tools/*.yml；错误配置阻止启动，不回退到 Java 描述。 */
public final class ToolDefinitionLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolDefinitionLoader() {}

    /**
     * 加载 classpath:tools/*.yml 中全部工具定义，按资源路径排序；任何配置错误抛 IllegalStateException 阻止启动。
     *
     * @param loader Spring 资源加载器
     * @return 工具定义列表（含 disabled 项）
     */
    public static List<ToolDefinition> load(ResourceLoader loader) {
        try {
            Resource[] resources = ResourcePatternUtils.getResourcePatternResolver(loader)
                    .getResources("classpath*:tools/*.yml");
            Arrays.sort(resources, Comparator.comparing(Resource::getDescription));
            List<ToolDefinition> definitions = new ArrayList<>();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    Object loaded = yaml.load(input);
                    if (!(loaded instanceof Map<?, ?> doc)
                            || !(doc.get("name") instanceof String name)
                            || !(doc.get("description") instanceof String description)
                            || !(doc.get("enabled") instanceof Boolean enabled)) {
                        throw new IllegalArgumentException("必须配置 name、description 和布尔型 enabled");
                    }
                    definitions.add(new ToolDefinition(name, enabled, description.trim(),
                            MAPPER.valueToTree(doc.get("input_schema")),
                            MAPPER.valueToTree(doc.get("output_schema")), http(doc)));
                } catch (Exception e) {
                    throw new IllegalStateException("工具配置错误 " + resource.getDescription() + ": " + e.getMessage(), e);
                }
            }
            return List.copyOf(definitions);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("加载工具 YAML 失败", e);
        }
    }
    /**
     * 解析 transport 字段：缺省/java 表示 Java 执行器（不允许出现 HTTP 字段）；
     * http 表示声明式只读 HTTP 工具，必须显式 side_effect=false，其他 transport 拒绝。
     *
     * @return HTTP 传输声明；Java 工具返回 null
     */
    private static HttpToolDefinition http(Map<?,?> doc) {
        Object transport=doc.get("transport");
        if(transport==null || "java".equals(transport)) {
            for(String key:List.of("service_id","method","path","timeout_ms","max_attempts","max_response_bytes","max_age_ms","side_effect"))
                if(doc.containsKey(key))throw new IllegalArgumentException("HTTP 字段必须声明 transport=http: "+key);
            return null;
        }
        if(!"http".equals(transport))throw new IllegalArgumentException("未知 Tool transport");
        var allowed=java.util.Set.of("name","enabled","description","input_schema","output_schema","transport","service_id","method","path",
                "timeout_ms","max_attempts","max_response_bytes","max_age_ms","side_effect");
        for(Object key:doc.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("未知 HTTP Tool 配置字段: "+key);
        if(!Boolean.FALSE.equals(doc.get("side_effect")))throw new IllegalArgumentException("当前 HTTP Tool 必须显式 side_effect=false");
        return new HttpToolDefinition((String)doc.get("service_id"),(String)doc.get("method"),(String)doc.get("path"),
                number(doc,"timeout_ms",5000),number(doc,"max_attempts",1),number(doc,"max_response_bytes",262144),number(doc,"max_age_ms",30000));
    }
    /** 取整数字段，缺省返回 fallback；类型不是整数抛 IllegalArgumentException。 */
    private static int number(Map<?,?> doc,String key,int fallback) {
        Object value=doc.get(key);if(value==null)return fallback;
        if(!(value instanceof Integer number))throw new IllegalArgumentException(key+" 必须是整数");
        return number;
    }

}
