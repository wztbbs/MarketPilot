package com.cexpilot.platform.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.Set;

/**
 * 规划类模型输出的 JSON 解析与校验工具：容忍 ``` 围栏与首尾噪声，
 * 开启严格重复键检测与 BigDecimal 浮点（保留阈值十进制精度）。
 */
public final class PlanningJson {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private PlanningJson() {}
    /** 新规划协议保留阈值十进制精度；不改变旧 Query Planner 的解析行为。 */
    /**
     * 解析模型输出为 JSON 树：先剥离 ``` 围栏，整体解析失败时回退截取首个 { 到末个 } 的子串。
     *
     * @param content 模型原始输出；null 或无法解析抛 IllegalArgumentException
     * @return 解析后的 JSON 树
     */
    public static JsonNode parse(String content) {
        if(content==null) throw new ModelOutputException("规划输出为空");
        String raw=content.trim();
        if(raw.startsWith("```")) {
            int newline=raw.indexOf('\n'); if(newline>=0) raw=raw.substring(newline+1);
            if(raw.endsWith("```")) raw=raw.substring(0,raw.length()-3).trim();
        }
        try { return JSON.readTree(raw); }
        catch(com.fasterxml.jackson.core.JsonProcessingException first) {
            int start=raw.indexOf('{'),end=raw.lastIndexOf('}');
            if(start>=0 && end>start) try { return JSON.readTree(raw.substring(start,end+1)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException second) { first.addSuppressed(second); }
            throw new ModelOutputException("规划输出不是有效 JSON 对象",first);
        }
    }
    /**
     * 校验节点是对象且所有字段都在白名单内。
     *
     * @param node    待校验节点
     * @param allowed 允许的字段名集合
     * @param name    错误信息中的对象称谓（如 "Agent 决策"）
     */
    public static void object(JsonNode node,Set<String> allowed,String name) {
        if(node==null || !node.isObject()) throw new ModelOutputException(name+" 必须是对象");
        node.fieldNames().forEachRemaining(key->{ if(!allowed.contains(key)) throw new ModelOutputException(name+" 未知字段："+key); });
    }
    /**
     * 取非空且长度受限的字符串字段。
     *
     * @param node  父对象
     * @param field 字段名
     * @param limit 最大字符数
     * @return 字段文本；缺失、非字符串、空白或超长抛 IllegalArgumentException
     */
    public static String text(JsonNode node,String field,int limit) {
        var value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>limit)
            throw new ModelOutputException(field+" 必须为非空字符串，最长 "+limit+" 字符");
        return value.asText();
    }
}
