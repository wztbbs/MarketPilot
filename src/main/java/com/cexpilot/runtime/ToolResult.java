package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolResult(boolean ok, JsonNode data, String error, String status, JsonNode provenance) {

    public ToolResult(boolean ok,JsonNode data,String error){this(ok,data,error,null,null);}
    public static ToolResult http(String status,JsonNode data,JsonNode provenance) {
        boolean ok=java.util.Set.of("OK","EMPTY").contains(status);
        return new ToolResult(ok,ok?data:null,ok?null:status,status,ok?provenance:null);
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ToolResult success(JsonNode data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /** 序列化成发回给 LLM 的 tool message 内容。 */
    public String toMessageContent() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", ok);
        if(status!=null)node.put("status",status);
        if(provenance!=null)node.set("provenance",provenance);
        if (data != null) {
            node.set("data", data);
        }
        if (error != null) {
            node.put("error", error);
        }
        return node.toString();
    }
}
