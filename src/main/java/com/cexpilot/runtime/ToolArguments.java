package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 当前工具使用的扁平 JSON Schema 子集：类型、枚举、默认值、范围、正则、required。object 仅校验是对象，嵌套结构由执行器严格校验。 */
public final class ToolArguments {
    private static final Set<String> ROOT_KEYS = Set.of("type", "properties", "required", "additionalProperties");
    private static final Set<String> PROPERTY_KEYS = Set.of("type", "description", "enum", "default", "minimum", "maximum", "pattern", "properties", "additionalProperties", "required", "items");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array");
    private static final Pattern REF = Pattern.compile("\\{\\{[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*}}");

    private ToolArguments() {}

    /**
     * 校验 input schema 的合法性（启动时调用）：类型白名单、关键字白名单、
     * pattern/minimum/maximum 仅用于匹配类型、enum 值类型一致、default 自身合法等。
     *
     * @param schema 待校验的 schema；非法抛 IllegalArgumentException
     */
    public static void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject()) {
            throw new IllegalArgumentException("input_schema 必须是含 properties 的 object schema");
        }
        checkKeys(schema, ROOT_KEYS);
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean()) {
            throw new IllegalArgumentException("additionalProperties 必须是布尔值");
        }
        JsonNode properties = schema.get("properties");
        if (schema.has("required")) {
            if (!schema.get("required").isArray()) throw new IllegalArgumentException("required 必须是数组");
            for (JsonNode key : schema.get("required")) {
                if (!key.isTextual() || !properties.has(key.asText())) {
                    throw new IllegalArgumentException("required 引用了未定义参数: " + key);
                }
            }
        }
        properties.fields().forEachRemaining(entry -> {
            JsonNode rule = entry.getValue();
            if (!rule.isObject() || !TYPES.contains(rule.path("type").asText())) {
                throw new IllegalArgumentException("参数类型不支持: " + entry.getKey());
            }
            checkKeys(rule, PROPERTY_KEYS);
            if(!"object".equals(rule.path("type").asText()) && (rule.has("properties") || rule.has("required") || rule.has("additionalProperties")))
                throw new IllegalArgumentException("对象字段只能用于 object");
            if(!"array".equals(rule.path("type").asText()) && rule.has("items"))throw new IllegalArgumentException("items 只能用于 array");
            if (rule.has("pattern")) {
                if (!rule.get("pattern").isTextual() || !"string".equals(rule.path("type").asText())) {
                    throw new IllegalArgumentException("pattern 仅支持字符串参数");
                }
                Pattern.compile(rule.get("pattern").asText());
            }
            for (String bound : List.of("minimum", "maximum")) {
                if (rule.has(bound) && (!rule.get(bound).isNumber()
                        || !Set.of("number", "integer").contains(rule.path("type").asText()))) {
                    throw new IllegalArgumentException(bound + " 仅支持数值参数");
                }
            }
            if (rule.has("minimum") && rule.has("maximum")
                    && rule.get("minimum").decimalValue().compareTo(rule.get("maximum").decimalValue()) > 0) {
                throw new IllegalArgumentException("minimum 大于 maximum");
            }
            if (rule.has("enum")) {
                if (!rule.get("enum").isArray() || rule.get("enum").isEmpty()) {
                    throw new IllegalArgumentException("enum 必须是非空数组");
                }
                for (JsonNode value : rule.get("enum")) {
                    if (!typeMatches(value, rule.path("type").asText())) {
                        throw new IllegalArgumentException("enum 值类型错误: " + entry.getKey());
                    }
                }
            }
            if("object".equals(rule.path("type").asText()) && rule.has("properties")) {
                ObjectNode nested=rule.deepCopy();nested.remove(List.of("description","default","enum"));validateSchema(nested);
            }
            if("array".equals(rule.path("type").asText())) {
                JsonNode item=rule.get("items");
                if(item==null || !item.isObject())throw new IllegalArgumentException("array 必须配置 items");
                ObjectNode wrapper=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type","object");
                wrapper.putObject("properties").set("item",item);validateSchema(wrapper);
            }
            if (rule.has("default")) {
                List<String> errors = new ArrayList<>();
                validateValue(entry.getKey(), rule.get("default"), rule, errors);
                if (!errors.isEmpty()) throw new IllegalArgumentException("非法 default: " + errors);
            }
        });
    }

    /** 校验 schema 只使用了受支持的关键字。 */
    private static void checkKeys(JsonNode node, Set<String> supported) {
        node.fieldNames().forEachRemaining(key -> {
            if (!supported.contains(key)) throw new IllegalArgumentException("不支持的 schema 字段: " + key);
        });
    }

    /**
     * 返回应用了 schema 默认值的参数副本（含嵌套 object/array 的递归默认值），不修改入参。
     *
     * @param args   原始参数对象，必须是 object
     * @param schema 已通过 validateSchema 的 input schema
     * @return 补齐默认值后的新对象
     */
    public static ObjectNode withDefaults(JsonNode args, JsonNode schema) {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("args 必须是对象");
        ObjectNode result = args.deepCopy();
        schema.path("properties").fields().forEachRemaining(entry -> {
            if (!result.has(entry.getKey()) && entry.getValue().has("default")) {
                result.set(entry.getKey(), entry.getValue().get("default").deepCopy());
            }
        });
        result.fields().forEachRemaining(entry->{
            JsonNode rule=schema.path("properties").get(entry.getKey());
            if(rule!=null)result.set(entry.getKey(),nestedDefaults(entry.getValue(),rule));
        });
        return result;
    }
    /** 递归为嵌套 object/array 填充默认值；其他值原样返回。 */
    private static JsonNode nestedDefaults(JsonNode value,JsonNode rule) {
        if(value.isObject() && rule.has("properties"))return withDefaults(value,rule);
        if(value.isArray() && rule.has("items")) {
            var array=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
            value.forEach(item->array.add(nestedDefaults(item,rule.get("items"))));return array;
        }
        return value;
    }

    /**
     * 校验实际参数：必填、未知参数（按 additionalProperties）、类型、枚举、范围与正则。
     *
     * @param args            实际参数对象
     * @param schema          input schema
     * @param allowReferences 为 true 时允许形如 {{node.field}} 的引用占位字符串跳过类型校验（规划期使用）
     * @return 错误信息列表；为空表示校验通过
     */
    public static List<String> validate(JsonNode args, JsonNode schema, boolean allowReferences) {
        List<String> errors = new ArrayList<>();
        if (args == null || !args.isObject()) return List.of("args 必须是对象");
        JsonNode values = withDefaults(args, schema);
        for (JsonNode key : schema.path("required")) {
            if (!values.hasNonNull(key.asText())) errors.add("缺少必填参数: " + key.asText());
        }
        values.fields().forEachRemaining(entry -> {
            JsonNode rule = schema.path("properties").get(entry.getKey());
            if (rule == null) {
                if (!schema.path("additionalProperties").asBoolean(true)) errors.add("未知参数: " + entry.getKey());
            } else if (!(allowReferences && entry.getValue().isTextual()
                    && REF.matcher(entry.getValue().asText()).matches())) {
                validateValue(entry.getKey(), entry.getValue(), rule, errors);
            }
        });
        return errors;
    }

    /** @return JSON 值是否符合 schema 声明的类型 */
    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    /** 校验单个值并把错误追加到 errors；object/array 递归校验。 */
    private static void validateValue(String name, JsonNode value, JsonNode rule, List<String> errors) {
        if (!typeMatches(value, rule.path("type").asText())) {
            errors.add(name + " 类型必须为 " + rule.path("type").asText());
            return;
        }
        if(value.isObject() && rule.has("properties"))errors.addAll(validate(value,rule,false));
        if(value.isArray())for(JsonNode item:value)validateValue(name+"[]",item,rule.path("items"),errors);
        if (rule.has("enum")) {
            boolean found = false;
            for (JsonNode option : rule.get("enum")) if (option.equals(value)) found = true;
            if (!found) errors.add(name + " 不在枚举范围内");
        }
        if (value.isNumber()) {
            if (rule.has("minimum") && value.decimalValue().compareTo(rule.get("minimum").decimalValue()) < 0)
                errors.add(name + " 小于 minimum");
            if (rule.has("maximum") && value.decimalValue().compareTo(rule.get("maximum").decimalValue()) > 0)
                errors.add(name + " 大于 maximum");
        }
        if (rule.has("pattern") && !Pattern.compile(rule.get("pattern").asText()).matcher(value.asText()).find()) {
            errors.add(name + " 不符合格式 " + rule.get("pattern").asText());
        }
    }
}
