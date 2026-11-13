package com.cexpilot.platform.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 输出字段的结构契约（JSON Schema 子集），同时用于提示词和规划期路径校验。
 * 只证明路径在结构上合法，不保证可选字段本次存在、非 null 或序列有足够的行。
 * 无 type 的节点表示形状取决于运行时输入（如 min.item），其内部路径留给 ReferenceResolver。
 */
public final class ToolOutputSchema {
    private static final Set<String> KEYS = Set.of(
            "type", "description", "properties", "additionalProperties", "items", "prefixItems", "required");
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "number", "integer", "boolean", "null");

    private ToolOutputSchema() {}

    /**
     * 校验 output schema 的合法性（启动时调用）：关键字白名单、type 只允许单类型或类型+null、
     * object 必须显式 properties 与 additionalProperties、array 必须配置 items 等。
     *
     * @param schema 待校验 schema；非法抛 IllegalArgumentException
     */
    public static void validateSchema(JsonNode schema) {
        validateSchema(schema, "output_schema");
    }

    private static void validateSchema(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) fail(path, "必须是 schema 对象");
        schema.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) fail(path, "不支持的关键字 " + key);
        });
        if (schema.has("description") && !schema.get("description").isTextual()) fail(path, "description 必须是字符串");
        JsonNode type = schema.get("type");
        if (type != null) {
            List<JsonNode> types = new ArrayList<>();
            if (type.isArray()) type.forEach(types::add); else types.add(type);
            if (types.isEmpty()) fail(path, "type 不能为空");
            for (JsonNode value : types) {
                if (!value.isTextual() || !TYPES.contains(value.asText())) fail(path, "未知 type " + value);
            }
            // 本实现只支持单个具体类型与 null 的组合；复杂联合必须显式作为动态节点。
            if (types.stream().filter(t -> !"null".equals(t.asText())).count() > 1) {
                fail(path, "仅支持单类型或该类型与 null 的组合");
            }
        }
        if (hasType(schema, "object")) {
            if (!schema.path("properties").isObject() || !schema.path("additionalProperties").isBoolean()) {
                fail(path, "object 必须显式配置 properties 和布尔型 additionalProperties");
            }
            schema.get("properties").fields().forEachRemaining(e -> validateSchema(e.getValue(), path + "." + e.getKey()));
            if(schema.has("required")) {
                if(!schema.get("required").isArray())fail(path,"required 必须为数组");
                var required=new java.util.HashSet<String>();
                for(var key:schema.get("required"))if(!key.isTextual() || !schema.path("properties").has(key.asText()) || !required.add(key.asText()))fail(path,"required 字段非法");
            }
        } else if (schema.has("properties") || schema.has("additionalProperties") || schema.has("required")) {
            fail(path, "properties/additionalProperties 仅用于 object");
        }
        if (hasType(schema, "array")) {
            if (!schema.has("items")) fail(path, "array 必须配置 items");
            JsonNode items = schema.get("items");
            if (!(items.isBoolean() && !items.asBoolean())) validateSchema(items, path + "[]");
            if (schema.has("prefixItems")) {
                if (!schema.get("prefixItems").isArray() || schema.get("prefixItems").isEmpty()) {
                    fail(path, "prefixItems 必须是非空数组");
                }
                for (JsonNode item : schema.get("prefixItems")) validateSchema(item, path + "[列]");
            }
        } else if (schema.has("items") || schema.has("prefixItems")) {
            fail(path, "items/prefixItems 仅用于 array");
        }
    }

    /** 抛出带路径定位的 schema 校验错误。 */
    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }

    /**
     * 校验工具实际返回的 data 是否满足 output schema（运行时使用）；无 type 的动态节点跳过。
     *
     * @param value  实际返回的 data 节点
     * @param schema 已通过 validateSchema 的 output schema
     */
    public static void validateValue(JsonNode value,JsonNode schema) { validateValue(value,schema,"data"); }
    private static void validateValue(JsonNode value,JsonNode schema,String path) {
        if(value==null)fail(path,"缺少数据");
        if(!schema.has("type"))return;
        boolean matches=value.isNull()?hasType(schema,"null"):value.isObject()?hasType(schema,"object"):value.isArray()?hasType(schema,"array"):
                value.isTextual()?hasType(schema,"string"):value.isBoolean()?hasType(schema,"boolean"):
                value.isNumber() && (hasType(schema,"number") || value.isIntegralNumber() && hasType(schema,"integer"));
        if(!matches)fail(path,"数据类型不匹配");
        if(value.isObject()) {
            for(var key:schema.path("required"))if(!value.has(key.asText()))fail(path,"缺少字段 "+key.asText());
            value.fields().forEachRemaining(entry->{
                JsonNode child=schema.path("properties").get(entry.getKey());
                if(child!=null)validateValue(entry.getValue(),child,path+"."+entry.getKey());
                else if(!schema.path("additionalProperties").asBoolean())fail(path,"未知字段 "+entry.getKey());
            });
        }
        if(value.isArray())for(int i=0;i<value.size();i++) {
            JsonNode item=i<schema.path("prefixItems").size()?schema.path("prefixItems").get(i):schema.path("items");
            if(item.isBoolean())fail(path,"数组长度超出固定结构");
            validateValue(value.get(i),item,path+"["+i+"]");
        }
    }

    /**
     * 规划期校验引用路径（如 .data.list.0.close）在结构上是否可达。ref.path 与 ReferenceResolver 一致：
     * 空路径取整个 data；.error 是信封的错误字段。无 type 的动态节点视为可达。
     *
     * @param schema 工具的 output schema
     * @param path   引用路径
     * @return 错误描述；null 表示路径合法或无法静态判定
     */
    public static String referenceError(JsonNode schema, String path) {
        if (path == null || path.isEmpty() || path.equals(".data") || path.equals(".error")) return null;
        if (!path.startsWith(".data.")) return "输出根路径应为 data，字段路径不能省略 data";
        String current = "data";
        for (String segment : path.substring(6).split("\\.", -1)) {
            if (!schema.has("type")) return null; // 动态形状：不能以未知当作不存在。
            if (hasType(schema, "object")) {
                JsonNode child = schema.path("properties").get(segment);
                if (child == null) {
                    if (schema.path("additionalProperties").asBoolean()) return null;
                    return current + " 没有字段 " + segment + "；可用字段："
                            + String.join(", ", fieldNames(schema.path("properties")));
                }
                schema = child;
            } else if (hasType(schema, "array")) {
                // Jackson JSON Pointer 的数组下标不接受负数、前导零或 int 溢出。
                int index;
                try {
                    if (!segment.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return current + " 是数组，须使用非负整数下标（如 .0），不能用 " + segment;
                }
                JsonNode prefix = schema.path("prefixItems");
                if (index < prefix.size()) {
                    schema = prefix.get(index);
                } else {
                    JsonNode items = schema.get("items");
                    if (items.isBoolean()) return current + " 的固定列下标只能为 0.." + (prefix.size() - 1);
                    schema = items;
                }
            } else {
                return current + " 是 " + schema.path("type") + "，不能继续引用 " + segment;
            }
            current += "." + segment;
        }
        return null;
    }

    /**
     * 紧凑展示 schema 的层级和固定列，避免向 Planner 注入重复的 schema 关键字。
     *
     * @return 形如 "{a:number(描述), b:array<...>}" 的单行结构描述
     */
    public static String describe(JsonNode schema) {
        String shape;
        if (hasType(schema, "object")) {
            List<String> fields = new ArrayList<>();
            schema.path("properties").fields().forEachRemaining(e -> fields.add(e.getKey() + ":" + describe(e.getValue())));
            if (schema.path("additionalProperties").asBoolean()) fields.add("其他动态字段");
            shape = "{" + String.join(", ", fields) + "}";
        } else if (hasType(schema, "array")) {
            if (schema.has("prefixItems")) {
                List<String> columns = new ArrayList<>();
                for (int i = 0; i < schema.get("prefixItems").size(); i++) {
                    columns.add(i + ":" + describe(schema.get("prefixItems").get(i)));
                }
                shape = "[" + String.join(", ", columns) + "]";
            } else {
                shape = "array<" + (schema.get("items").isObject() ? describe(schema.get("items")) : "无元素") + ">";
            }
        } else {
            shape = schema.has("type") ? schema.get("type").asText(schema.get("type").toString()) : "动态形状";
        }
        String description = schema.path("description").asText("");
        return description.isBlank() ? shape : shape + "(" + description + ")";
    }

    /** @return schema 的 type（可为数组）是否包含指定类型 */
    private static boolean hasType(JsonNode schema, String type) {
        JsonNode value = schema.path("type");
        if (value.isTextual()) return type.equals(value.asText());
        for (JsonNode element : value) if (type.equals(element.asText())) return true;
        return false;
    }

    /** @return properties 的字段名列表，用于错误提示 */
    private static List<String> fieldNames(JsonNode properties) {
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
