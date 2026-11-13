package com.cexpilot.platform.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具执行结果：结构化事实 + 证据元数据。
 *
 * @param ok         是否成功
 * @param data       成功时的结构化数据；失败为 null
 * @param error      失败时的错误描述；成功为 null
 * @param status     HTTP 工具信封状态（OK/EMPTY/DENIED/TIMEOUT/...）；Java 工具为 null
 * @param provenance 证据元数据（source/observed_at/valid_until 等）；失败为 null
 */
public record ToolResult(boolean ok, JsonNode data, String error, String status, JsonNode provenance) {
    public ToolResult {
        data=data==null || data.isNull()?null:data.deepCopy();
        provenance=provenance==null || provenance.isNull()?null:provenance.deepCopy();
    }

    public ToolResult(boolean ok,JsonNode data,String error){this(ok,data,error,null,null);}
    /**
     * 按 HTTP 工具信封状态构造结果：仅 OK/EMPTY 视为成功。
     *
     * @param status     信封状态
     * @param data       成功时的 data；失败传 null
     * @param provenance 成功时的证据元数据；失败传 null
     */
    public static ToolResult http(String status,JsonNode data,JsonNode provenance) {
        boolean ok=java.util.Set.of("OK","EMPTY").contains(status);
        return new ToolResult(ok,ok?data:null,ok?null:status,status,ok?provenance:null);
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @return 成功结果（Java 工具路径，无 status/provenance） */
    public static ToolResult success(JsonNode data) {
        return new ToolResult(true, data, null);
    }

    /** @return 失败结果（Java 工具路径，error 为错误描述） */
    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * 序列化成发回给 LLM 的 tool message 内容。
     *
     * @return 含 ok/status/provenance/data/error 的 JSON 字符串（仅出现非空字段）
     */
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
