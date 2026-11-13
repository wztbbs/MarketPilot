package com.cexpilot.platform.tool;

import com.cexpilot.platform.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义。YAML 是工具描述、输入参数和输出字段契约的唯一来源。
 *
 * @param name         工具名，非空，与 Java 执行器同名绑定
 * @param enabled      是否启用；禁用的工具不暴露给模型、不允许执行
 * @param description  面向模型的能力描述，非空
 * @param inputSchema  输入参数的 JSON Schema 子集，构造时校验并深拷贝
 * @param outputSchema 输出结构的 JSON Schema 子集，构造时校验并深拷贝
 * @param http         HTTP 传输声明；null 表示 Java 执行器
 */
public record ToolDefinition(String name, boolean enabled, String description, JsonNode inputSchema,
                             JsonNode outputSchema, HttpToolDefinition http, com.cexpilot.platform.tool.control.ToolControl control) {
    public ToolDefinition(String name, boolean enabled, String description, JsonNode inputSchema, JsonNode outputSchema, HttpToolDefinition http) {
        this(name, enabled, description, inputSchema, outputSchema, http, com.cexpilot.platform.tool.control.ToolControl.read());
    }
    public ToolDefinition(String name,boolean enabled,String description,JsonNode inputSchema,JsonNode outputSchema) {
        this(name,enabled,description,inputSchema,outputSchema,null);
    }
    public ToolDefinition {
        java.util.Objects.requireNonNull(control, "工具必须声明执行控制语义");
        if (http != null && control.kind() == com.cexpilot.platform.tool.control.ToolControl.Kind.OPERATION
                && (!"POST".equals(http.method()) || http.maxAttempts() > 1))
            throw new IllegalArgumentException("HTTP 操作必须使用 POST，单次提交；重试由调用记录控制");
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具 name / description 不能为空");
        }
        ToolArguments.validateSchema(inputSchema);
        inputSchema = inputSchema.deepCopy();
        ToolOutputSchema.validateSchema(outputSchema);
        outputSchema = outputSchema.deepCopy();
    }

    public String contractVersion() {
        return com.cexpilot.platform.prompt.PromptStore.fingerprint(description+":"+inputSchema+":"+outputSchema+":"+http+":"+control);
    }
    /** @return 暴露给模型的工具规格（名称、描述、输入 schema 的深拷贝） */
    public ToolSpec spec() {
        return new ToolSpec(name, description, inputSchema.deepCopy());
    }
}
