package com.cexpilot.platform.tool;

import com.cexpilot.platform.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 YAML 元数据与同名 Java 执行器绑定；disabled 工具不暴露、不执行。 */
@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList,ResourceLoader loader) {this(toolList,ToolDefinitionLoader.load(loader));}
    public ToolRegistry(List<AgentTool> toolList,ResourceLoader loader,org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.tool.config.HttpToolServices> services) {
        this(toolList,ToolDefinitionLoader.load(loader),services.getIfAvailable(com.cexpilot.platform.tool.config.HttpToolServices::new));
    }

    @Autowired
    public ToolRegistry(List<AgentTool> toolList,ResourceLoader loader,org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.tool.config.HttpToolServices> services,
                        List<ToolDefinitionSource> sources) {
        this(toolList, combined(loader,sources), services.getIfAvailable(com.cexpilot.platform.tool.config.HttpToolServices::new));
    }
    private static List<ToolDefinition> combined(ResourceLoader loader,List<ToolDefinitionSource> sources) {
        var definitions=new java.util.ArrayList<>(ToolDefinitionLoader.load(loader));sources.forEach(source->definitions.addAll(source.definitions()));return definitions;
    }
    public ToolRegistry(List<AgentTool> toolList,List<ToolDefinition> configured) {this(toolList,configured,new com.cexpilot.platform.tool.config.HttpToolServices());}
    /**
     * 绑定 YAML 定义与执行器：enabled 的 Java 工具按同名匹配执行器，HTTP 工具现场构造 HttpAgentTool
     * （不允许覆盖已有 Java 执行器）；缺执行器、缺 YAML 或重复定义都直接启动失败。
     *
     * @param toolList   Spring 容器中的 Java 执行器
     * @param configured YAML 加载的全部定义（含 disabled）
     * @param services   HTTP 工具的服务地址/凭证配置
     */
    public ToolRegistry(List<AgentTool> toolList,List<ToolDefinition> configured,com.cexpilot.platform.tool.config.HttpToolServices services) {        Map<String, AgentTool> executors = new LinkedHashMap<>();
        for (AgentTool tool : toolList) {
            if (executors.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("重复工具执行器: " + tool.name());
        }
        Map<String, ToolDefinition> all = new LinkedHashMap<>();
        for (ToolDefinition definition : configured) {
            if (all.putIfAbsent(definition.name(), definition) != null)
                throw new IllegalStateException("重复工具 YAML: " + definition.name());
            if (definition.enabled()) {
                AgentTool executor = executors.get(definition.name());
                if(definition.http()!=null) {
                    if(executor!=null)throw new IllegalStateException("HTTP Tool 不能覆盖 Java 执行器: "+definition.name());
                    executor=new HttpAgentTool(definition,services);
                }
                if (executor == null) throw new IllegalStateException("工具缺少执行器: " + definition.name());
                tools.put(definition.name(), executor);
                definitions.put(definition.name(), definition);
            }
        }
        for (String name : executors.keySet()) {
            if (!all.containsKey(name)) throw new IllegalStateException("工具缺少 YAML: " + name);
        }
    }

    /** @return 已启用工具的执行器；未注册或已禁用返回 null */
    public AgentTool get(String name) { return tools.get(name); }

    /** 执行器仅供入口和类型检查使用；引擎实际执行统一走 invoke。 */
    private org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.tool.control.ToolGateway> gateway;
    @Autowired void gateway(org.springframework.beans.factory.ObjectProvider<com.cexpilot.platform.tool.control.ToolGateway> gateway) { this.gateway = gateway; }
    public ToolDefinition definition(String name) {
        var definition = definitions.get(name);
        if (definition == null) throw new IllegalArgumentException("工具未注册或已禁用: " + name);
        return definition;
    }
    public java.util.Set<String> readTools() {
        return definitions.values().stream().filter(d -> d.control().kind() == com.cexpilot.platform.tool.control.ToolControl.Kind.READ)
                .map(ToolDefinition::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public ToolResult invoke(String name, JsonNode args, ToolContext context, String position) {
        if (gateway != null) return gateway.getObject().invoke(name, args, context, position);
        // 无容器的本地同步构造仅提供只读能力；操作工具必须装配持久化入口。
        var definition = definition(name);
        if (context == null || !context.allowedTools().contains(name) || context.agentId() == null)
            return new ToolResult(false, null, "Agent 未绑定工具", "DENIED", null);
        if (definition.control().kind() != com.cexpilot.platform.tool.control.ToolControl.Kind.READ || get(name) instanceof AsyncAgentTool)
            return new ToolResult(false, null, "此工具需要可恢复运行入口", "DURABLE_RUN_REQUIRED", null);
        return get(name).execute(prepareArguments(name, args), context);
    }

    /**
     * @param name 工具名
     * @return 输出 schema 的深拷贝；未注册返回 null
     */
    public JsonNode outputSchema(String name) {
        ToolDefinition definition = definitions.get(name);
        return definition == null ? null : definition.outputSchema().deepCopy();
    }

    /**
     * @param name 工具名
     * @return 暴露给模型的工具规格；未注册返回 null
     */
    public ToolSpec spec(String name) {
        ToolDefinition definition = definitions.get(name);
        return definition == null ? null : definition.spec();
    }

    /** @return 全部已启用工具的规格列表（供注入提示词/工具清单） */
    public List<ToolSpec> specs() { return definitions.values().stream().map(ToolDefinition::spec).toList(); }

    /** 引用已解析后再次校验，并应用同一份 YAML 的默认值。 */
    public JsonNode prepareArguments(String name, JsonNode args) {
        ToolSpec spec = spec(name);
        if (spec == null) throw new IllegalArgumentException("未注册或已禁用的工具: " + name);
        List<String> errors = ToolArguments.validate(args, spec.inputSchema(), false);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return ToolArguments.withDefaults(args, spec.inputSchema());
    }

    /** @return 该工具是否由 HTTP 声明式执行器承载 */
    public boolean isHttp(String name){return get(name) instanceof HttpAgentTool;}
    /**
     * @param name 工具名；未注册抛 IllegalArgumentException
     * @return 工具契约指纹（描述+输入/输出 schema+HTTP 配置）
     */
    public String contractVersion(String name){
        var definition=definitions.get(name);
        if(definition==null)throw new IllegalArgumentException("未知工具");
        return definition.contractVersion();
    }
    /** @return 已启用工具数量 */
    public int size() { return tools.size(); }
}
