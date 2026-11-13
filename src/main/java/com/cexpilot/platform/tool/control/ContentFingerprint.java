package com.cexpilot.platform.tool.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 对对象键顺序及十进制表示规范化，保证 JSON 落库往返后指纹稳定。 */
public final class ContentFingerprint {
    private ContentFingerprint() {}
    public static String of(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node == null) return NullNode.instance;
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode(); var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add); keys.forEach(key -> result.set(key, canonical(node.get(key)))); return result;
        }
        if (node.isArray()) { var result = JsonNodeFactory.instance.arrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        return node;
    }
}
